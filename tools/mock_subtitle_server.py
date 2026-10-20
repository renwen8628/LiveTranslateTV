#!/usr/bin/env python3
"""Mock subtitle backend for LiveTranslateTV (Milestone 3).

Streams scripted English -> Simplified Chinese subtitles over WebSocket, the same
JSON shape a real translation backend will send. Uses only the Python standard
library, so there is nothing to install.

Each connection gets its own stream starting at sequence 1. For every line it sends
growing partial captions (isFinal=false) and then the final caption. It also sends
some messages the TV should ignore, to exercise that logic:
  * a duplicate of the previous final (same sequence)
  * an outdated caption with an old sequence number

Usage:
    python3 tools/mock_subtitle_server.py [--port 8765] [--pause 1.5] [--once]
Then point the TV at ws://<this computer's LAN IP>:8765
"""

import argparse
import asyncio
import base64
import hashlib
import json
import socket
import struct

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

SCRIPT = [
    ("We need to move now.", "我们现在必须行动。"),
    ("Is anyone there?", "有人在吗？"),
    ("Hold the line, reinforcements are on the way.", "守住防线，援军正在赶来。"),
    ("I've never seen anything like this before.", "我以前从没见过这样的东西。"),
    ("Check the map, the exit should be to the north.", "看看地图，出口应该在北边。"),
    ("Stay close and keep your voice down.", "跟紧点，小声说话。"),
]


# --- Minimal WebSocket (RFC 6455) server side -------------------------------------

async def handshake(reader, writer):
    request = await reader.readuntil(b"\r\n\r\n")
    headers = {}
    for line in request.decode("latin-1").split("\r\n")[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    key = headers.get("sec-websocket-key")
    if not key or headers.get("upgrade", "").lower() != "websocket":
        writer.write(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
        await writer.drain()
        return False
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    writer.write(
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n".encode()
    )
    await writer.drain()
    return True


def encode_frame(payload: bytes, opcode: int = 0x1) -> bytes:
    header = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header += bytes([n])
    elif n < 65536:
        header += bytes([126]) + struct.pack("!H", n)
    else:
        header += bytes([127]) + struct.pack("!Q", n)
    return header + payload


async def read_frame(reader):
    b0, b1 = await reader.readexactly(2)
    opcode = b0 & 0x0F
    length = b1 & 0x7F
    if length == 126:
        (length,) = struct.unpack("!H", await reader.readexactly(2))
    elif length == 127:
        (length,) = struct.unpack("!Q", await reader.readexactly(8))
    mask = await reader.readexactly(4) if b1 & 0x80 else None
    data = bytearray(await reader.readexactly(length))
    if mask:
        for i in range(length):
            data[i] ^= mask[i % 4]
    return opcode, bytes(data)


# --- Subtitle stream ---------------------------------------------------------------

def caption(seq, original, translated, final, duration_ms=None):
    msg = {
        "type": "subtitle",
        "sequence": seq,
        "sourceLanguage": "en",
        "targetLanguage": "zh-CN",
        "originalText": original,
        "translatedText": translated,
        "isFinal": final,
    }
    if duration_ms:
        msg["durationMs"] = duration_ms
    return msg


async def stream(send, pause, once):
    seq = 0
    while True:
        for original, translated in SCRIPT:
            seq += 1
            words = original.split()
            # Partials: reveal the sentence a few words at a time.
            steps = min(3, len(words) - 1)
            for i in range(1, steps + 1):
                k = round(len(words) * i / (steps + 1))
                part_en = " ".join(words[:k])
                part_zh = translated[: max(1, round(len(translated) * k / len(words)))]
                await send(caption(seq, part_en + "…", part_zh + "…", False))
                await asyncio.sleep(0.35)

            duration = 1500 + 60 * len(original)
            await send(caption(seq, original, translated, True, duration))

            if seq % 3 == 0:
                await asyncio.sleep(0.2)
                # Should be ignored: same sequence already final.
                await send(caption(seq, original, "【重复，应被忽略】", True, duration), "duplicate")
            if seq % 4 == 0:
                await asyncio.sleep(0.2)
                # Should be ignored: older sequence.
                await send(caption(seq - 2, "stale", "【过期，应被忽略】", True, duration), "outdated")

            await asyncio.sleep(duration / 1000 + pause)
        if once:
            return


async def handle_client(reader, writer):
    peer = writer.get_extra_info("peername")
    try:
        if not await handshake(reader, writer):
            return
    except (asyncio.IncompleteReadError, ConnectionError):
        return
    print(f"[+] {peer} connected")

    async def send(msg, note=""):
        writer.write(encode_frame(json.dumps(msg, ensure_ascii=False).encode()))
        await writer.drain()
        label = f" ({note}, TV should ignore)" if note else ""
        kind = "final  " if msg.get("isFinal") else "partial"
        print(f"    -> #{msg['sequence']} {kind} {msg['translatedText']}{label}")

    async def receive():
        while True:
            opcode, data = await read_frame(reader)
            if opcode == 0x8:  # close
                writer.write(encode_frame(data[:2], 0x8))
                await writer.drain()
                return
            if opcode == 0x9:  # ping
                writer.write(encode_frame(data, 0xA))
                await writer.drain()
            elif opcode == 0x1:
                print(f"    <- {data.decode(errors='replace')}")

    receiver = asyncio.create_task(receive())
    streamer = asyncio.create_task(stream(send, ARGS.pause, ARGS.once))
    try:
        await asyncio.wait({receiver, streamer}, return_when=asyncio.FIRST_COMPLETED)
    except (ConnectionError, asyncio.IncompleteReadError):
        pass
    finally:
        for task in (receiver, streamer):
            task.cancel()
        writer.close()
        print(f"[-] {peer} disconnected")


def lan_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


async def main():
    server = await asyncio.start_server(handle_client, "0.0.0.0", ARGS.port)
    print(f"Mock subtitle server on ws://{lan_ip()}:{ARGS.port}  (Ctrl+C to stop)")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--pause", type=float, default=1.5, help="seconds between lines")
    parser.add_argument("--once", action="store_true", help="play the script once per client")
    ARGS = parser.parse_args()
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
