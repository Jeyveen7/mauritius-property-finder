"""Money as integer minor units (cents) with an ISO currency. No floats."""

from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_EVEN, Decimal

CURRENCIES = ("MUR", "EUR", "USD")


@dataclass(frozen=True)
class Money:
    minor: int
    currency: str

    def __post_init__(self):
        if self.currency not in CURRENCIES:
            raise ValueError(f"unsupported currency {self.currency}")

    @classmethod
    def of(cls, amount: Decimal | int | str, currency: str) -> "Money":
        minor = (Decimal(str(amount)) * 100).to_integral_value(ROUND_HALF_EVEN)
        return cls(int(minor), currency)

    @property
    def amount(self) -> Decimal:
        return Decimal(self.minor) / 100


@dataclass(frozen=True)
class FxTable:
    """MUR per unit of each foreign currency, as published on `day`."""

    day: date
    mur_per_unit: dict[str, Decimal]

    def rate(self, currency: str) -> Decimal:
        if currency == "MUR":
            return Decimal(1)
        try:
            return self.mur_per_unit[currency]
        except KeyError:
            raise LookupError(f"no FX rate for {currency} on {self.day}") from None

    def convert(self, money: Money, to: str) -> Money:
        if money.currency == to:
            return money
        mur_minor = Decimal(money.minor) * self.rate(money.currency)
        minor = (mur_minor / self.rate(to)).to_integral_value(ROUND_HALF_EVEN)
        return Money(int(minor), to)
