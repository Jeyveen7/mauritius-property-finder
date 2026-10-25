"""Can a non-citizen buy this listing? Rules come from a versioned `rules_version`
row, never from code, so a Finance Act change needs no release.

The answer is three-valued: when a fact the rule depends on is missing (e.g. the
number of floors for a G+2 apartment) the answer is "unknown", never a guess.
"""

from dataclasses import dataclass
from decimal import Decimal

from mpf.domain.money import FxTable, Money


@dataclass(frozen=True)
class Rules:
    version_id: int
    label: str
    open_schemes: frozenset[str]
    g2_min_price_mur: Decimal
    g2_min_floors: int
    residency_min_usd: Decimal
    permit_holder_min_usd: Decimal
    registration_duty_pct: Decimal

    @classmethod
    def from_row(cls, row: dict) -> "Rules":
        r = row["rules"]
        return cls(
            version_id=row["id"],
            label=row["label"],
            open_schemes=frozenset(r["open_schemes"]),
            g2_min_price_mur=Decimal(str(r["g2"]["min_price_mur"])),
            g2_min_floors=int(r["g2"]["min_floors_above_ground"]),
            residency_min_usd=Decimal(str(r["residency_min_usd"])),
            permit_holder_min_usd=Decimal(str(r["permit_holder_min_usd"])),
            registration_duty_pct=Decimal(str(r["registration_duty_pct"])),
        )


@dataclass(frozen=True)
class Eligibility:
    status: str  # "yes" | "no" | "unknown"
    route: str | None  # "scheme:PDS", "g2", ... when status == "yes"
    residency: bool  # purchase also qualifies for a residence permit
    reason: str  # stable code the app translates
    rules_version: str

    def to_json(self) -> dict:
        return {"status": self.status, "route": self.route, "residency": self.residency,
                "reason": self.reason, "rules_version": self.rules_version}


def evaluate(rules: Rules, fx: FxTable, *, type: str, scheme: str | None,
             floors_above_ground: int | None, price: Money) -> Eligibility:
    usd = fx.convert(price, "USD").amount
    mur = fx.convert(price, "MUR").amount
    residency = usd >= rules.residency_min_usd

    if scheme in rules.open_schemes:
        return Eligibility("yes", f"scheme:{scheme}", residency, "open_scheme", rules.label)

    if type == "apartment" and scheme == "G+2":
        if mur < rules.g2_min_price_mur:
            return Eligibility("no", None, False, "g2_below_min_price", rules.label)
        if floors_above_ground is None:
            return Eligibility("unknown", None, False, "g2_floors_unknown", rules.label)
        if floors_above_ground < rules.g2_min_floors:
            return Eligibility("no", None, False, "g2_too_few_floors", rules.label)
        return Eligibility("yes", "g2", residency, "g2_apartment", rules.label)

    # Outside the schemes only certain permit holders may buy one home, with approval.
    return Eligibility("no", None, False, "citizens_and_permit_holders", rules.label)
