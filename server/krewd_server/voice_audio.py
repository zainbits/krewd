"""Audio for voice input, in numpy only (no torch): Kaldi filterbanks for the speaker model, which match torchaudio's
`compliance.kaldi.fbank` to float32 rounding (`tests/test_voice.py` pins a few values), and WAV for the transcription
API.

Audio is 16 kHz mono float32 in [-1, 1].
"""

from __future__ import annotations

import numpy as np

RATE = 16000


def pcm16_to_float(data: bytes) -> np.ndarray:
    return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0


# Kaldi filterbanks (WeSpeaker's input): 25 ms frames every 10 ms, Hamming window, pre-emphasis 0.97,
# DC removed per frame, 512-point FFT power spectrum, 80 triangular mel bins from 20 Hz, natural log.


def _kaldi_mel_banks(bins: int, n_fft: int, rate: int, low: float = 20.0) -> np.ndarray:
    def mel(f):
        return 1127.0 * np.log(1.0 + np.asarray(f, dtype=np.float64) / 700.0)

    high = rate / 2
    lo, hi = mel(low), mel(high)
    delta = (hi - lo) / (bins + 1)
    b = np.arange(bins)[:, None]
    left, center, right = lo + b * delta, lo + (b + 1) * delta, lo + (b + 2) * delta
    m = mel(rate / n_fft * np.arange(n_fft // 2))[None, :]
    up = (m - left) / (center - left)
    down = (right - m) / (right - center)
    banks = np.maximum(0.0, np.minimum(up, down))
    return np.pad(banks, ((0, 0), (0, 1))).astype(np.float32)  # the Nyquist bin gets no weight, as in Kaldi


_KALDI_BANKS = _kaldi_mel_banks(80, 512, RATE)
_HAMMING = (0.54 - 0.46 * np.cos(2 * np.pi * np.arange(400) / 399)).astype(np.float32)


def kaldi_fbank(audio: np.ndarray) -> np.ndarray:
    """[frames, 80] log mel energies of `audio` (scaled to 16-bit range first, as WeSpeaker was trained)."""
    x = audio.astype(np.float32) * 32768.0
    if len(x) < 400:
        return np.zeros((0, 80), dtype=np.float32)
    n = 1 + (len(x) - 400) // 160
    idx = np.arange(400)[None, :] + 160 * np.arange(n)[:, None]
    frames = x[idx]
    frames = frames - frames.mean(axis=1, keepdims=True)
    shifted = np.concatenate([frames[:, :1], frames[:, :-1]], axis=1)
    frames = (frames - 0.97 * shifted) * _HAMMING
    spec = np.abs(np.fft.rfft(frames, n=512, axis=1)) ** 2
    energies = spec.astype(np.float32) @ _KALDI_BANKS.T
    return np.log(np.maximum(energies, np.finfo(np.float32).eps)).astype(np.float32)


def wav_bytes(audio: np.ndarray) -> bytes:
    """16-bit mono WAV of `audio`, for a transcription API."""
    import io
    import wave

    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)
    return buf.getvalue()
