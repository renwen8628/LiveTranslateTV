# Translation backend

DeepSeek is the default engine for English OCR subtitles → Simplified Chinese.
It preserves recent dialogue context, corrects OCR errors, and filters non-dialogue.
The TV's existing WebSocket connection and app work unchanged.

```sh
python3 -m venv backend/.venv
backend/.venv/bin/pip install -r backend/requirements.txt
```

Add `DEEPSEEK_API_KEY=your-key` to `backend/.env` (git-ignored), preserving any
existing entries. See `.env.example`. Keys can also be set in the environment.
The key stays on the backend; subtitle text and recent context are sent to DeepSeek.

```sh
backend/.venv/bin/python backend/translation_server.py --game "Call of Duty"
```

Connect the TV to the printed `ws://<computer-LAN-IP>:8766` address.

## Game-aware translation

While PS5 translation is on, the TV asks the PS5 every 20 s which game is running and
sends its name to the backend. The model is told to follow that game's official Chinese
localization. `--game` is only a fallback for before the TV reports a game.

Each game also gets a glossary in `backend/glossaries/<game>.json`. The model reports the
names and terms it translated (characters, places, factions, weapons…), new ones are
saved, and every later line that mentions a term is told to use the saved Chinese, so
names stay the same across sessions. To fix a term, edit the file and restart the
backend (or switch games).
Stop any existing backend on that port before starting the new one.

The default model is `deepseek-flash`, with thinking disabled for subtitle latency.
Use `--model <model-id>` to override it. Requests time out after 15 seconds of
network inactivity; failed, empty, or truncated responses are skipped.
API details: [DeepSeek API](https://api-docs.deepseek.com/) and
[JSON output](https://api-docs.deepseek.com/guides/json_mode/).

Claude remains available with `--provider claude` and `ANTHROPIC_API_KEY`.
Use `--fake` for offline TV connectivity testing without an API key.

Run backend tests:

```sh
backend/.venv/bin/python -m unittest discover -s backend -p 'test_*.py'
```
