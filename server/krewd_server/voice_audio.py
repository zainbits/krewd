"""Audio for voice input: 16 kHz mono float32 in [-1, 1], from the phone's 16-bit PCM, and WAV for the transcription
API."""

from __future__ import annotations

import io
import wave

import numpy as np

RATE = 16000


def pcm16_to_float(data: bytes) -> np.ndarray:
    return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0


def wav_bytes(audio: np.ndarray) -> bytes:
    """16-bit mono WAV of `audio`."""
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)
    return buf.getvalue()
