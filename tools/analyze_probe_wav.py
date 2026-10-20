#!/usr/bin/env python3
"""Summarise WAV files written by the app's audio capture test (AudioProbeService).

For each file prints overall RMS level and how strongly the 1 kHz test tone stands out
from neighbouring frequencies, so "the path heard the TV's own output" can be told
apart from background noise. Standard library only.

Usage: python3 tools/analyze_probe_wav.py <dir-or-wav>...
"""

import math
import os
import struct
import sys
import wave


def goertzel_power(samples, rate, freq):
    k = 2 * math.cos(2 * math.pi * freq / rate)
    s1 = s2 = 0.0
    for x in samples:
        s1, s2 = x + k * s1 - s2, s1
    return (s1 * s1 + s2 * s2 - k * s1 * s2) / len(samples) ** 2


def analyse(path):
    with wave.open(path) as w:
        rate = w.getframerate()
        raw = w.readframes(w.getnframes())
    samples = struct.unpack(f"<{len(raw) // 2}h", raw)
    if not samples:
        return f"{os.path.basename(path):40s} empty"
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))
    db = 20 * math.log10(rms / 32768) if rms else float("-inf")
    # Use 1 s from the middle to keep it fast.
    mid = samples[len(samples) // 2 - rate // 2: len(samples) // 2 + rate // 2]
    tone = goertzel_power(mid, rate, 1000)
    noise = sum(goertzel_power(mid, rate, f) for f in (700, 800, 1200, 1300)) / 4
    snr = 10 * math.log10(tone / noise) if noise > 0 and tone > 0 else float("nan")
    voice = sum(goertzel_power(mid, rate, f) for f in (300, 500, 2000, 3000)) / 4
    voice_db = 10 * math.log10(voice) - 20 * math.log10(32768) + 3 if voice > 0 else float("-inf")
    return (f"{os.path.basename(path):40s} rms {db:6.1f} dBFS   1kHz vs neighbours {snr:+6.1f} dB"
            f"   speech-band {voice_db:6.1f} dB")


def main(args):
    files = []
    for a in args:
        if os.path.isdir(a):
            files += sorted(os.path.join(a, f) for f in os.listdir(a) if f.endswith(".wav"))
        else:
            files.append(a)
    for f in files:
        print(analyse(f))


if __name__ == "__main__":
    main(sys.argv[1:] or ["."])
