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
