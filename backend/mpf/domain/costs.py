"""Monthly repayment and up-front cash estimate. An illustration, not a loan offer."""

from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Decimal

from mpf.domain.money import Money


@dataclass(frozen=True)
class CostEstimate:
    loan: Money
    monthly: Money
    upfront: Money  # deposit + registration duty
    annual_rate_pct: Decimal
    registration_duty_pct: Decimal


def estimate(price: Money, *, deposit_pct: int, term_years: int,
             annual_rate_pct: Decimal, registration_duty_pct: Decimal) -> CostEstimate:
    if not 0 <= deposit_pct < 100:
        raise ValueError("deposit_pct must be in [0, 100)")
    if term_years <= 0:
        raise ValueError("term_years must be positive")

    def cents(x: Decimal) -> int:
        return int(x.to_integral_value(ROUND_HALF_EVEN))

    p = Decimal(price.minor)
    loan = p * (100 - deposit_pct) / 100
    r = annual_rate_pct / 100 / 12
    n = term_years * 12
    monthly = loan / n if r == 0 else loan * r / (1 - (1 + r) ** -n)
    upfront = p * deposit_pct / 100 + p * registration_duty_pct / 100
    return CostEstimate(
        loan=Money(cents(loan), price.currency),
        monthly=Money(cents(monthly), price.currency),
        upfront=Money(cents(upfront), price.currency),
        annual_rate_pct=annual_rate_pct,
        registration_duty_pct=registration_duty_pct,
    )
