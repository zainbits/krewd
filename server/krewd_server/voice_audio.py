"""Audio features for voice input, in numpy only (no torch): Kaldi filterbanks for the speaker model and
Whisper log-mels for the end-of-thought model. Both match their reference implementations (torchaudio's
`compliance.kaldi.fbank` and transformers' `WhisperFeatureExtractor`) to float32 rounding; `tests/test_voice.py`
pins a few values.

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


# Whisper log-mels (Smart Turn's input): 8 s window, 25 ms periodic Hann frames every 10 ms, centred with
# reflect padding, 80 Slaney mel bins up to 8 kHz, log10, clamped 8 below the peak, scaled to about [-1, 1].


def _slaney_mel(f):
    f = np.asarray(f, dtype=np.float64)
    lin = 3.0 * f / 200.0
    return np.where(f >= 1000.0, 15.0 + np.log(np.maximum(f, 1e-10) / 1000.0) * (27.0 / np.log(6.4)), lin)


def _slaney_hz(m):
    m = np.asarray(m, dtype=np.float64)
    lin = 200.0 * m / 3.0
    return np.where(m >= 15.0, 1000.0 * np.exp(np.log(6.4) / 27.0 * (m - 15.0)), lin)


def _whisper_filters() -> np.ndarray:
    freqs = _slaney_hz(np.linspace(_slaney_mel(0.0), _slaney_mel(8000.0), 82))
    fft = np.linspace(0, RATE // 2, 201)
    diff = np.diff(freqs)
    slopes = freqs[None, :] - fft[:, None]
    down = -slopes[:, :-2] / diff[:-1]
    up = slopes[:, 2:] / diff[1:]
    banks = np.maximum(0.0, np.minimum(down, up))
    banks *= 2.0 / (freqs[2:82] - freqs[:80])
    return banks  # [201, 80], float64 like transformers


_WHISPER_FILTERS = _whisper_filters()
_HANN = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(400) / 400)  # periodic


def whisper_mel(audio: np.ndarray, seconds: int = 8) -> np.ndarray:
    """[80, 100 * seconds] features of the last `seconds` of `audio`, zero-padded at the end when shorter."""
    size = seconds * RATE
    x = audio[-size:].astype(np.float64)
    x = (x - x.mean()) / np.sqrt(x.var() + 1e-7) if len(x) else x
    x = np.pad(x, (0, size - len(x)))
    x = np.pad(x, (200, 200), mode="reflect")
    n = 1 + (len(x) - 400) // 160
    idx = np.arange(400)[None, :] + 160 * np.arange(n)[:, None]
    spec = np.abs(np.fft.rfft(x[idx] * _HANN, n=400, axis=1)) ** 2
    mel = np.log10(np.maximum(_WHISPER_FILTERS.T @ spec.T, 1e-10))[:, :-1]
    mel = np.maximum(mel, mel.max() - 8.0)
    return ((mel + 4.0) / 4.0).astype(np.float32)


def rms(audio: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(audio)))) if len(audio) else 0.0


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
