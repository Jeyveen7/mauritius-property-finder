from typing import Literal, Protocol

from pydantic import BaseModel, Field


class Confidence(BaseModel):
    """0 = not found or a guess, 1 = stated unambiguously in the post."""

    type: float = Field(ge=0, le=1)
    locality: float = Field(ge=0, le=1)
    size: float = Field(ge=0, le=1)
    price: float = Field(ge=0, le=1)


class ExtractedPost(BaseModel):
    type: Literal["land", "apartment", "house"] | None
    locality: str | None = Field(description="Town or village as written in the post")
    size_value: float | None
    size_unit: Literal["m2", "perch", "toise", "arpent"] | None
    size_is_plot: bool | None = Field(description="True for land/plot area, false for built floor area")
    price_whole: int | None = Field(description="Asking price in whole currency units, e.g. 4200000 for Rs 4.2M")
    price_currency: Literal["MUR", "EUR", "USD"] | None
    negotiable: bool
    scheme: Literal["PDS", "IRS", "RES", "SMART_CITY", "IHS", "G+2"] | None
    bedrooms: int | None
    confidence: Confidence


class ExtractionFailed(Exception):
    pass


class Extractor(Protocol):
    name: str

    def extract(self, text: str) -> ExtractedPost: ...
