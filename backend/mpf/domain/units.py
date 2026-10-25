"""Mauritian land units. Everything is stored in m²; these convert for display and parsing."""

from decimal import Decimal

M2_PER_UNIT = {
    "m2": Decimal("1"),
    "perch": Decimal("42.21"),
    "toise": Decimal("3.7987"),
    "arpent": Decimal("4220.87"),
}

# Spellings seen in posts, mapped to the unit keys above.
UNIT_ALIASES = {
    "m2": "m2", "m²": "m2", "sqm": "m2", "metres carres": "m2", "metres carre": "m2",
    "perch": "perch", "perches": "perch", "perche": "perch",
    "toise": "toise", "toises": "toise",
    "arpent": "arpent", "arpents": "arpent",
}


def to_m2(value: Decimal | float | int, unit: str) -> Decimal:
    return (Decimal(str(value)) * M2_PER_UNIT[unit]).quantize(Decimal("0.01"))


def from_m2(m2: Decimal | float | int, unit: str) -> Decimal:
    return Decimal(str(m2)) / M2_PER_UNIT[unit]


def at_least(plot_m2: Decimal | float | None, value: Decimal | float, unit: str) -> bool:
    """True if the plot is at least `value` `unit`s, with 1% slack so a plot sold
    as "half an arpent" (2,110 m²) still counts as half an arpent (2,110.4 m²)."""
    if plot_m2 is None:
        return False
    return Decimal(str(plot_m2)) >= to_m2(value, unit) * Decimal("0.99")
