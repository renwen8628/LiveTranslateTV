#!/usr/bin/env python3
"""LiveTranslateTV translation backend.

The TV connects over WebSocket and sends each subtitle line it reads from the game:
    {"type": "source", "sequence": 12, "text": "Dunn: You gotta listen to heavy metal."}
The backend translates it (with the last few lines as context) and replies:
    {"type": "subtitle", "sequence": 12, "originalText": "...", "translatedText": "...",
     "sourceLanguage": "en", "targetLanguage": "zh-CN", "isFinal": true, "latencyMs": 900}

Lines are translated concurrently; the TV ignores replies for lines older than the one it
is showing, so a slow translation never overwrites a newer subtitle.

Usage:
    backend/.venv/bin/python backend/translation_server.py [--port 8766] [--game "Call of Duty"]
    (needs DEEPSEEK_API_KEY in backend/.env; --fake translates offline)
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import socket
import time
from collections import deque
from pathlib import Path

import ws
from translator import ClaudeTranslator, ContextLine, DeepSeekTranslator, FakeTranslator, Translator

log = logging.getLogger("server")
CONTEXT_LINES = 6


def load_dotenv(path: Path) -> None:
    """Tiny .env reader (KEY=VALUE lines) so the API key can live in a git-ignored file."""
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


class Connection:
    def __init__(self, writer: asyncio.StreamWriter, translator: Translator, game: str | None):
        self.writer = writer
        self.translator = translator
        self.game = game
        self.target_language = "zh-CN"
        self.context: deque[ContextLine] = deque(maxlen=CONTEXT_LINES)
        self.send_lock = asyncio.Lock()
        self.tasks: set[asyncio.Task] = set()

    async def send(self, message: dict) -> None:
        data = json.dumps(message, ensure_ascii=False).encode()
        async with self.send_lock:
            self.writer.write(ws.encode_frame(data))
            await self.writer.drain()

    async def handle_text(self, text: str) -> None:
        try:
            msg = json.loads(text)
        except json.JSONDecodeError:
            return
        kind = msg.get("type")
        if kind == "hello":
            self.target_language = msg.get("targetLanguage") or self.target_language
            self.game = msg.get("game") or self.game
            log.info("TV hello: %s", msg)
        elif kind == "source" and isinstance(msg.get("text"), str) and isinstance(msg.get("sequence"), int):
            task = asyncio.create_task(self.translate(msg["sequence"], msg["text"]))
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)

    async def translate(self, sequence: int, text: str) -> None:
        started = time.monotonic()
        try:
            result = await self.translator.translate(text, list(self.context), self.game)
        except Exception as e:  # network/API errors: log and skip this line
            log.error("#%d translation failed: %s: %s", sequence, type(e).__name__, e)
            return
        latency_ms = int((time.monotonic() - started) * 1000)
        if not result.translation:
            log.info("#%d skipped (not dialogue / declined) %dms: %s", sequence, latency_ms, text)
            return
        self.context.append(ContextLine(result.source, result.translation))
        log.info("#%d %dms [%s in=%d out=%d]\n    EN: %s\n    ZH: %s", sequence, latency_ms,
                 result.model, result.input_tokens, result.output_tokens, result.source, result.translation)
        await self.send({
            "type": "subtitle",
            "sequence": sequence,
            "sourceLanguage": "en",
            "targetLanguage": self.target_language,
            "originalText": result.source,
            "translatedText": result.translation,
            "isFinal": True,
            "latencyMs": latency_ms,
        })


async def serve_client(reader, writer, translator: Translator, game: str | None) -> None:
    peer = writer.get_extra_info("peername")
    try:
        if not await ws.handshake(reader, writer):
            return
    except (asyncio.IncompleteReadError, ConnectionError):
        return
    log.info("TV connected: %s", peer)
    conn = Connection(writer, translator, game)
    try:
        while True:
            opcode, data = await ws.read_frame(reader)
            if opcode == ws.OP_CLOSE:
                writer.write(ws.encode_frame(data[:2], ws.OP_CLOSE))
                await writer.drain()
                break
            if opcode == ws.OP_PING:
                async with conn.send_lock:
                    writer.write(ws.encode_frame(data, ws.OP_PONG))
                    await writer.drain()
            elif opcode == ws.OP_TEXT:
                await conn.handle_text(data.decode(errors="replace"))
    except (asyncio.IncompleteReadError, ConnectionError):
        pass
    finally:
        for task in list(conn.tasks):
            task.cancel()
        writer.close()
        log.info("TV disconnected: %s", peer)


def lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


async def main() -> None:
    parser = argparse.ArgumentParser(description="LiveTranslateTV translation backend")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--game", help="game title, gives the translator context")
    parser.add_argument("--fake", action="store_true", help="offline fake translator (no API calls)")
    parser.add_argument("--provider", choices=["deepseek", "claude"], default="deepseek")
    parser.add_argument("--model", help="provider model (default: deepseek-flash / claude-opus-5-5)")
    parser.add_argument("--effort", default="low", choices=["low", "medium", "high"],
                        help="Claude thinking effort (only used with --provider claude)")
    args = parser.parse_args()

    load_dotenv(Path(__file__).with_name(".env"))
    if args.fake:
        translator: Translator = FakeTranslator()
    elif args.provider == "deepseek":
        if not os.environ.get("DEEPSEEK_API_KEY", "").strip():
            raise SystemExit("DEEPSEEK_API_KEY is not set. Put DEEPSEEK_API_KEY=... in backend/.env, or run with --fake.")
        translator = DeepSeekTranslator(model=args.model or "deepseek-flash")
    else:
        if not os.environ.get("ANTHROPIC_API_KEY"):
            raise SystemExit("ANTHROPIC_API_KEY is not set. Put ANTHROPIC_API_KEY=... in backend/.env, or run with --fake.")
        translator = ClaudeTranslator(model=args.model or "claude-opus-5-5", effort=args.effort)

    try:
        server = await asyncio.start_server(lambda r, w: serve_client(r, w, translator, args.game), "0.0.0.0", args.port)
        log.info("Translation backend (%s) on ws://%s:%d", translator.name, lan_ip(), args.port)
        async with server:
            await server.serve_forever()
    finally:
        if isinstance(translator, DeepSeekTranslator):
            await translator.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
