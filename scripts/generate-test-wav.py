#!/usr/bin/env python3
"""Create a non-personal synthetic tone for ingestion/quality smoke tests."""
import math, struct, wave
rate, seconds = 16000, 3
with wave.open("/tmp/voice-smoke.wav", "wb") as wav:
    wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(rate)
    for i in range(rate * seconds):
        value = int(0.25 * 32767 * math.sin(2 * math.pi * 220 * i / rate))
        wav.writeframesraw(struct.pack("<h", value))
