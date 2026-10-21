"""Subtitle translators. Providers are interchangeable behind `Translator`."""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
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
    # Proper nouns / game terms in this line and the Chinese used, for the glossary.
    terms: dict[str, str] = field(default_factory=dict)


class Translator(Protocol):
    name: str

    async def translate(
        self, text: str, context: list[ContextLine], game: str | None,
        glossary: dict[str, str] | None = None,
    ) -> Translation: ...


class FakeTranslator:
    """Offline stand-in for testing the TV <-> backend plumbing without an API key."""

    name = "fake"

    async def translate(
        self, text: str, context: list[ContextLine], game: str | None,
        glossary: dict[str, str] | None = None,
    ) -> Translation:
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

Glossary: when the prompt lists glossary entries, use exactly that Chinese for those terms \
so names stay consistent across the whole game. In "terms", list each proper noun or \
game-specific term in this line (character names and call signs used inside the dialogue, \
places, factions, units, operations, weapons, vehicles, items, abilities), with "zh" copied \
exactly from your translation, so if you kept a name in English, "zh" is that English. \
Leave out ordinary words (package, target, base, sir) and the speaker label before the \
colon. Use an empty list when there are none.

Reply with the corrected English in "source", the Chinese in "translation", and "terms"."""


def system_prompt(game: str | None) -> str:
    """The game goes in the system prompt so it stays a stable, cacheable prefix."""
    if not game:
        return SYSTEM_PROMPT
    return SYSTEM_PROMPT + f"""

The game is "{game}". Translate like its official Simplified Chinese localization: \
where you know the official Chinese names for its characters, places, factions, weapons, \
and other terms, use them; otherwise pick natural names in the same style. Match the \
game's setting and tone."""

OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "source": {"type": "string"},
        "translation": {"type": "string"},
        "terms": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"en": {"type": "string"}, "zh": {"type": "string"}},
                "required": ["en", "zh"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["source", "translation", "terms"],
    "additionalProperties": False,
}


def subtitle_prompt(text: str, context: list[ContextLine], glossary: dict[str, str] | None) -> str:
    parts = []
    if glossary:
        parts.append("Glossary for this game (English = Chinese):\n" +
                     "\n".join(f"- {en} = {zh}" for en, zh in glossary.items()))
    if context:
        parts.append("Recent lines, oldest first:")
        parts += [f"- EN: {c.source}\n  ZH: {c.translation}" for c in context]
    parts.append(f"Translate this subtitle line:\n{text}")
    return "\n\n".join(parts)


def parse_terms(raw: object) -> dict[str, str]:
    """Reads the optional "terms" list; anything malformed is ignored, not fatal."""
    terms = {}
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, dict) and isinstance(item.get("en"), str) and isinstance(item.get("zh"), str):
                en, zh = item["en"].strip(), item["zh"].strip()
                if en and zh:
                    terms[en] = zh
    return terms


class DeepSeekTranslator:
    """DeepSeek chat API. The key stays in the backend, never on the TV."""

    name = "deepseek"

    def __init__(self, model: str = "deepseek-flash", timeout_s: float = 15.0):
        import httpx

        api_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
        if not api_key:
            raise ValueError("DEEPSEEK_API_KEY is not set")
        self.model = model
        self._client = httpx.AsyncClient(
            base_url="https://api.deepseek.com",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=timeout_s,
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def translate(
        self, text: str, context: list[ContextLine], game: str | None,
        glossary: dict[str, str] | None = None,
    ) -> Translation:
        response = await self._client.post("/chat/completions", json={
            "model": self.model,
            "messages": [
                {"role": "system", "content": system_prompt(game) + '\nReturn only a JSON object, '
                 'for example: {"source": "Park: Price, go!", "translation": "Park：普莱斯，快走！", '
                 '"terms": [{"en": "Price", "zh": "普莱斯"}]}'},
                {"role": "user", "content": subtitle_prompt(text, context, glossary)},
            ],
            "response_format": {"type": "json_object"},
            "thinking": {"type": "disabled"},
            "max_tokens": 2000,
            "stream": False,
        })
        response.raise_for_status()
        payload = response.json()
        usage = payload.get("usage") or {}
        result = Translation(
            source=text, translation="", model=payload.get("model", self.model),
            input_tokens=usage.get("prompt_tokens", 0),
            output_tokens=usage.get("completion_tokens", 0),
        )
        choices = payload.get("choices") or []
        if not choices or choices[0].get("finish_reason") != "stop":
            log.warning("DeepSeek returned no complete answer")
            return result
        body = choices[0].get("message", {}).get("content")
        if not body:
            log.warning("DeepSeek returned empty content")
            return result
        data = json.loads(body)
        if not isinstance(data, dict) or not all(
            isinstance(data.get(key), str) for key in ("source", "translation")
        ):
            raise ValueError("DeepSeek response must contain string source and translation fields")
        result.source = data["source"].strip() or text
        result.translation = data["translation"].strip()
        result.terms = parse_terms(data.get("terms"))
        return result


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

    async def translate(
        self, text: str, context: list[ContextLine], game: str | None,
        glossary: dict[str, str] | None = None,
    ) -> Translation:
        response = await self._client.beta.messages.create(
            model=self.model,
            # Room for adaptive thinking plus a one-line JSON answer.
            max_tokens=2000,
            system=system_prompt(game),
            messages=[{"role": "user", "content": subtitle_prompt(text, context, glossary)}],
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
        result.terms = parse_terms(data.get("terms"))
        return result
