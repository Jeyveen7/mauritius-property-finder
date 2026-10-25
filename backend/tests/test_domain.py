from datetime import date
from decimal import Decimal

import pytest

from mpf.domain import costs, dedup, eligibility, units
from mpf.domain.geo import coast_distance_m, distance_m
from mpf.domain.money import FxTable, Money
from mpf.domain.text import norm
from mpf.extract.rules import RulesExtractor

FX = FxTable(day=date(2026, 10, 1), mur_per_unit={"EUR": Decimal("52.5"), "USD": Decimal("45.5")})
RULES = eligibility.Rules(
    version_id=1, label="test", open_schemes=frozenset({"PDS", "SMART_CITY", "IRS", "RES", "IHS"}),
    g2_min_price_mur=Decimal(6_000_000), g2_min_floors=2, residency_min_usd=Decimal(375_000),
    permit_holder_min_usd=Decimal(500_000), registration_duty_pct=Decimal(5),
)


def test_units_round_trip_and_half_arpent_slack():
    assert units.to_m2(12, "perch") == Decimal("506.52")
    assert units.to_m2(1, "arpent") == Decimal("4220.87")
    assert units.at_least(Decimal("2110"), Decimal("0.5"), "arpent")
    assert not units.at_least(Decimal("2000"), Decimal("0.5"), "arpent")


def test_money_is_integer_and_converts_through_mur():
    price = Money.of("352000", "EUR")
    assert price.minor == 35_200_000
    assert FX.convert(price, "MUR") == Money(1_848_000_000, "MUR")
    assert FX.convert(Money.of(4_550_000, "MUR"), "USD") == Money.of(100_000, "USD")


@pytest.mark.parametrize("kwargs, status, route, residency", [
    (dict(type="house", scheme="PDS", floors_above_ground=None, price=Money.of(1_360_000, "USD")), "yes", "scheme:PDS", True),
    (dict(type="apartment", scheme="SMART_CITY", floors_above_ground=None, price=Money.of(300_000, "EUR")), "yes", "scheme:SMART_CITY", False),
    (dict(type="apartment", scheme="G+2", floors_above_ground=3, price=Money.of(14_800_000, "MUR")), "yes", "g2", False),
    (dict(type="apartment", scheme="G+2", floors_above_ground=None, price=Money.of(7_900_000, "MUR")), "unknown", None, False),
    (dict(type="apartment", scheme="G+2", floors_above_ground=3, price=Money.of(5_000_000, "MUR")), "no", None, False),
    (dict(type="apartment", scheme="G+2", floors_above_ground=1, price=Money.of(9_000_000, "MUR")), "no", None, False),
    (dict(type="land", scheme=None, floors_above_ground=None, price=Money.of(5_900_000, "MUR")), "no", None, False),
])
def test_eligibility(kwargs, status, route, residency):
    e = eligibility.evaluate(RULES, FX, **kwargs)
    assert (e.status, e.route, e.residency) == (status, route, residency)


def test_cost_estimate_matches_annuity_formula():
    est = costs.estimate(Money.of(8_400_000, "MUR"), deposit_pct=20, term_years=20,
                         annual_rate_pct=Decimal(6), registration_duty_pct=Decimal(5))
    r, n, loan = 0.005, 240, 6_720_000
    expected = loan * r / (1 - (1 + r) ** -n)
    assert abs(est.monthly.amount - Decimal(str(round(expected, 2)))) <= Decimal("0.01")
    assert est.upfront == Money.of(1_680_000 + 420_000, "MUR")


def _cand(**over):
    base = dict(type="land", lat=-20.015, lon=57.595, geo_precision="exact", locality_id=2,
                price_mur_minor=590_000_000, plot_m2=Decimal(507), floor_m2=None, bedrooms=None,
                title="Residential plot near the beach")
    return dedup.Candidate(**{**base, **over})


def test_dedup_merges_same_place_and_separates_others():
    assert dedup.decide(dedup.score(_cand(), _cand(price_mur_minor=595_000_000))) == "merge"
    assert dedup.score(_cand(), _cand(type="house")) == 0
    assert dedup.score(_cand(), _cand(lat=-20.05)) == 0  # ~4 km away
    same_village = dedup.score(_cand(geo_precision="locality"), _cand(geo_precision="locality",
                                                                      title="Terrain près de la plage"))
    assert dedup.decide(same_village) in ("review", "new")
    with_photo = dedup.score(_cand(geo_precision="locality", image_hashes=("ffff0000ffff0000",)),
                             _cand(geo_precision="locality", title="Other words", image_hashes=("ffff0000ffff0001",)))
    assert dedup.decide(with_photo) == "merge"


def test_geo():
    assert 9_000 < distance_m(-20.165, 57.505, -20.245, 57.49) < 10_000  # Port Louis to Ebène
    assert coast_distance_m(-20.315, 57.52) > 5_000  # Curepipe is inland
    assert coast_distance_m(-20.42, 57.71) < 2_500  # Pointe d'Esny is on the coast


def test_norm_ignores_accents_and_punctuation():
    assert norm("Pointe d'Esny") == "pointe d esny"
    assert norm("Mahébourg") == "mahebourg"


@pytest.mark.parametrize("text, price, currency, size, unit", [
    ("Terrain résidentiel à vendre Pointe aux Piments, 12 perches. Rs 4.2M à discuter.", 4_200_000, "MUR", 12, "perch"),
    ("Land for sale Grand Baie 506m2, price Rs 4,200,000 negotiable", 4_200_000, "MUR", 506, "m2"),
    ("Villa 3 chambres Tamarin, 1 arpent, €850k", 850_000, "EUR", 1, "arpent"),
])
def test_rules_extractor(text, price, currency, size, unit):
    ext = RulesExtractor(["Pointe aux Piments", "Grand Baie", "Tamarin"])
    out = ext.extract(text)
    assert (out.price_whole, out.price_currency, out.size_value, out.size_unit) == (price, currency, size, unit)
    assert out.locality is not None
    assert out.confidence.price > 0
