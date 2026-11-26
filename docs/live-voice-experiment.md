# Experiment: live voice input (branch `feat/live-voice`)

Started 2026-11-26. Zain picks the winner after real-life use; the other mode is then removed.

## What is being compared

| Mode | Phone | Server / Model Warehouse | Words appear | Me / Others |
|---|---|---|---|---|
| **Pieces** (today, `feat/voice-input`) | Cutter sends a piece after 2 s of quiet (30 s max) | Bark 3.0 Clean + Nemotron 3 Diarization (offline, 15 s reference first) | after each piece | with the words |
| **Live** (this experiment) | streams audio continuously | Nemotron ASR Streaming 0.6B (`nvidia/nemotron-speech-streaming-en-0.6b`) + Nemotron 3 Diarization in streaming mode (reference fed first, so the owner is speaker #1 in its speaker cache) | while speaking (0.56-1.12 s steps) | about 1 s after the words, set before Send |

No Bark in Live mode.

## Plan

Both modes stay available behind a switch in the voice settings (Pieces / Live) while Zain tests them.

1. Server bench (no app change): a recording fed as if live; measure word delay, label delay,
   GPU memory with both models, accuracy against Bark on the same audio.
2. Model Warehouse live session (branch `feat/streaming-asr` in the Warehouse repo).
3. Krewd server relays the phone's stream to the Warehouse and sends words and labels back.
4. App: the Pieces / Live switch; Live streams audio and shows grey words that get their label.

## Open questions

- The Warehouse runs one job at a time; a live session holds the GPU while Zain talks.
- Accuracy for Indian English (one public benchmark of accented English learners: about 25% WER
  for Nemotron ASR); test on Zain's own recordings.
- Mixed voices: Bark dropped the second speaker's words in one stretch; check whether the streaming
  model does the same.

## Results

### 1. Server bench (2026-11-26, Warehouse `experiments/live_bench.py`)

70 s AMI meeting audio, 12 alternating turns, one speaker as the owner, RTX 2060.

| | Result |
|---|---|
| Streaming ASR, one stream | WER 32.7%: drops most Others turns (keeps the main voice, like Bark) |
| Streaming ASR, new stream per speaker turn | WER 22.2% (Bark per turn 23.5%) |
| Word delay | about 1.2 s at 1.12 s chunks, about 0.65 s at 0.56 s |
| Diarization streaming (1.04 s) | Me/Others right on 92.0% of speech (offline 92.9%) |
| GPU memory, both models | about 1.5 GiB |

So Live mode restarts the ASR stream at each speaker change found by the diarizer. Not yet tested
on Zain's own phone audio.
