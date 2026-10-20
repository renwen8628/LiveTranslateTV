"""Minimal WebSocket (RFC 6455) server-side helpers on asyncio streams.

Enough for the TV's text-only connection (text frames, ping/pong, close).
Standard library only.
"""

import asyncio
import base64
import hashlib
import struct

_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

OP_TEXT = 0x1
OP_CLOSE = 0x8
OP_PING = 0x9
OP_PONG = 0xA


async def handshake(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> bool:
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
    accept = base64.b64encode(hashlib.sha1((key + _GUID).encode()).digest()).decode()
    writer.write(
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n".encode()
    )
    await writer.drain()
    return True


def encode_frame(payload: bytes, opcode: int = OP_TEXT) -> bytes:
    header = bytes([0x80 | opcode])
    n = len(payload)
    if n < 126:
        header += bytes([n])
    elif n < 65536:
        header += bytes([126]) + struct.pack("!H", n)
    else:
        header += bytes([127]) + struct.pack("!Q", n)
    return header + payload


async def read_frame(reader: asyncio.StreamReader) -> tuple[int, bytes]:
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
