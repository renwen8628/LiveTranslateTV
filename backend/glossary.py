"""Per-game glossaries, so names and terms are translated the same way every time.

Each game gets a JSON file in backend/glossaries/ mapping English terms to the Chinese
used for them. The translator reports the proper nouns it translated, new ones are
added (the first translation wins), and later lines that mention a term get that entry
in their prompt. Edit the file by hand to correct a term; the server reloads it when
the game changes or the server restarts.
"""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path

log = logging.getLogger("glossary")

GLOSSARY_DIR = Path(__file__).with_name("glossaries")
MAX_TERMS_PER_PROMPT = 40
MAX_TERM_LENGTH = 60

# PS5 titles often carry marks like "Call of Duty®: Black Ops 6".
_TRADEMARKS = re.compile("[®™©℠]")


def clean_game_name(name: str | None) -> str | None:
    if not name:
        return None
    cleaned = " ".join(_TRADEMARKS.sub("", name).split())
    return cleaned or None


def _slug(game: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", game.lower()).strip("-")
    return slug or "game"


class Glossary:
    def __init__(self, game: str, directory: Path = GLOSSARY_DIR):
        self.game = game
        self.path = directory / f"{_slug(game)}.json"
        self.terms: dict[str, str] = {}
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                self.terms = {str(k): str(v) for k, v in (data.get("terms") or {}).items() if k and v}
            except (OSError, ValueError, AttributeError) as e:
                log.error("Could not read %s (%s); starting an empty glossary", self.path, e)
        log.info("Glossary for %r: %d terms (%s)", game, len(self.terms), self.path)

    def relevant(self, texts: list[str]) -> dict[str, str]:
        """Entries whose English term appears in any of [texts], longest terms first."""
        haystack = "\n".join(texts).lower()
        found = {}
        for en in sorted(self.terms, key=len, reverse=True):
            if re.search(rf"(?<!\w){re.escape(en.lower())}(?!\w)", haystack):
                found[en] = self.terms[en]
                if len(found) >= MAX_TERMS_PER_PROMPT:
                    break
        return found

    def learn(self, terms: dict[str, str]) -> dict[str, str]:
        """Adds unseen terms and saves. Returns what was added."""
        known = {en.lower() for en in self.terms}
        added = {}
        for en, zh in terms.items():
            en, zh = en.strip(), zh.strip()
            if not en or not zh or len(en) > MAX_TERM_LENGTH or len(zh) > MAX_TERM_LENGTH:
                continue
            if en.lower() in known:
                continue
            known.add(en.lower())
            self.terms[en] = zh
            added[en] = zh
        if added:
            self._save()
        return added

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        data = {"game": self.game, "terms": dict(sorted(self.terms.items(), key=lambda kv: kv[0].lower()))}
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(self.path)


class GlossaryStore:
    """One shared Glossary per game for all connected TVs."""

    def __init__(self, directory: Path = GLOSSARY_DIR):
        self.directory = directory
        self._by_game: dict[str, Glossary] = {}

    def get(self, game: str | None) -> Glossary | None:
        if not game:
            return None
        key = _slug(game)
        if key not in self._by_game:
            self._by_game[key] = Glossary(game, self.directory)
        return self._by_game[key]
