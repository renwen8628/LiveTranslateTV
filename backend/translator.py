"""Subtitle translators. Providers are interchangeable behind `Translator`."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Protocol

log = logging.getLogger("translator")


@dataclass
class ContextLine:
    source: str
    translation: str


@dataclass
class Translation:
    source: str  # English after OCR correction
    translation: str  # empty when the input was not dialogue
    model: str = ""
    input_tokens: int = 0
    output_tokens: int = 0


class Translator(Protocol):
    name: str

    async def translate(self, text: str, context: list[ContextLine], game: str | None) -> Translation: ...


class FakeTranslator:
    """Offline stand-in for testing the TV <-> backend plumbing without an API key."""

    name = "fake"

    async def translate(self, text: str, context: list[ContextLine], game: str | None) -> Translation:
        return Translation(source=text, translation=f"【测试】{text}", model="fake")


SYSTEM_PROMPT = """\
You translate video game subtitles into natural, idiomatic Simplified Chinese (zh-CN). \
The translation is shown on screen while the player is watching, so it must read like \
professional Chinese game localization: fluent, concise, and true to each character's voice.

The English comes from OCR of the game screen and often contains recognition errors: \
misread letters ("rn" for "m", "l" for "I", "0" for "O"), missing or extra spaces, stray \
brackets or symbols, and sometimes two consecutive subtitle lines merged into one. Work out \
the intended English from the line itself and the recent context before translating.

Conventions:
- A leading "Name:" is the speaker. Keep the name in English exactly as the game spells it \
(fix OCR errors), then a full-width colon "：", then the Chinese. Example: "Park：快走！"
- Square brackets mark dialogue the game already translated from another language. \
Translate it like normal speech and drop the brackets.
- If the line holds two speakers' lines, translate both, separated by a space.
- Keep the tone: casual banter stays casual, swearing stays rough but natural, military \
terms use standard Chinese military vocabulary. Do not soften, explain, or add anything.
- Use the recent lines for consistent names, terms, and who is talking to whom.
- If the input is not dialogue (menu labels, button hints, HUD numbers, random characters), \
return empty strings for both fields.

Reply with the corrected English in "source" and the Chinese in "translation"."""

OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "source": {"type": "string"},
        "translation": {"type": "string"},
    },
    "required": ["source", "translation"],
    "additionalProperties": False,
}


class ClaudeTranslator:
    """Claude via the Anthropic Python SDK. Credentials come from the environment
    (ANTHROPIC_API_KEY) and never leave this machine."""

    name = "claude"

    def __init__(self, model: str = "claude-opus-5-5", effort: str = "low", timeout_s: float = 15.0):
        import anthropic  # imported here so the fake translator works without the SDK

        self._anthropic = anthropic
        # Short timeout and one retry: a subtitle that arrives 30 s late is useless.
        self._client = anthropic.AsyncAnthropic(timeout=timeout_s, max_retries=1)
        self.model = model
        self.effort = effort

    async def translate(self, text: str, context: list[ContextLine], game: str | None) -> Translation:
        parts = []
        if game:
            parts.append(f"Game: {game}")
        if context:
            parts.append("Recent lines, oldest first:")
            parts += [f"- EN: {c.source}\n  ZH: {c.translation}" for c in context]
        parts.append(f"Translate this subtitle line:\n{text}")

        response = await self._client.beta.messages.create(
            model=self.model,
            # Room for adaptive thinking plus a one-line JSON answer.
            max_tokens=2000,
            system=SYSTEM_PROMPT,
            messages=[{"role": "user", "content": "\n\n".join(parts)}],
            # Effort is the speed/depth control on this model (thinking is always on).
            output_config={
                "effort": self.effort,
                "format": {"type": "json_schema", "schema": OUTPUT_SCHEMA},
            },
            # If a safety classifier declines (e.g. violent game dialogue), the API retries on
            # Anthropic's recommended fallback model instead of returning nothing.
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        )

        usage = response.usage
        result = Translation(
            source=text,
            translation="",
            model=response.model,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
        )
        if response.stop_reason == "refusal":
            category = response.stop_details.category if response.stop_details else None
            log.warning("Refused (%s): %r request=%s", category, text, response._request_id)
            return result
        if response.stop_reason == "max_tokens":
            log.warning("Hit max_tokens for %r request=%s", text, response._request_id)
            return result

        body = next((b.text for b in response.content if b.type == "text"), None)
        if body is None:
            log.warning("No text block for %r request=%s", text, response._request_id)
            return result
        data = json.loads(body)
        result.source = data["source"] or text
        result.translation = data["translation"]
        return result
