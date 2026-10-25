"""The Claude extractor's request shape and failure handling, with a stub client
(no network). A live check runs only with MPF_LIVE_CLAUDE=1."""

import os
from types import SimpleNamespace

import pytest

from mpf.extract import ExtractedPost, ExtractionFailed
from mpf.extract.claude import ClaudeExtractor
from mpf.extract.schema import Confidence

SAMPLE = ExtractedPost(type="land", locality="Pointe aux Piments", size_value=12, size_unit="perch",
                       size_is_plot=True, price_whole=4_200_000, price_currency="MUR", negotiable=True,
                       scheme=None, bedrooms=None,
                       confidence=Confidence(type=1, locality=1, size=1, price=0.9))


class StubClient:
    def __init__(self, response):
        self.calls = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(parse=self._parse))
        self.response = response

    def _parse(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


def test_request_uses_structured_output_with_fallback():
    stub = StubClient(SimpleNamespace(stop_reason="end_turn", parsed_output=SAMPLE))
    out = ClaudeExtractor(model="claude-opus-5-5", effort="low", client=stub).extract("Terrain 12 perches Rs 4.2M")
    assert out == SAMPLE
    call = stub.calls[0]
    assert call["model"] == "claude-opus-5-5"
    assert call["output_format"] is ExtractedPost
    assert call["output_config"] == {"effort": "low"}
    assert (call["fallbacks"], call["betas"]) == ("default", ["server-side-fallback-2026-07-01"])
    assert "<advert>" in call["messages"][0]["content"]
    assert "thinking" not in call


@pytest.mark.parametrize("stop_reason, parsed", [("refusal", None), ("max_tokens", None), ("end_turn", None)])
def test_unusable_replies_raise(stop_reason, parsed):
    stub = StubClient(SimpleNamespace(stop_reason=stop_reason, parsed_output=parsed))
    with pytest.raises(ExtractionFailed):
        ClaudeExtractor(model="claude-opus-5-5", effort="low", client=stub).extract("x" * 20)


@pytest.mark.skipif(os.environ.get("MPF_LIVE_CLAUDE") != "1", reason="calls the Claude API; set MPF_LIVE_CLAUDE=1")
def test_live_kreol_post():
    out = ClaudeExtractor(model="claude-opus-5-5", effort="low").extract(
        "Lavant terin 15 lapers Belle Mare, Rs 8,4M, kontak dan inbox")
    assert out.locality and "belle" in out.locality.lower()
    assert out.price_whole == 8_400_000 and out.size_unit == "perch"
