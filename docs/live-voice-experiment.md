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

(filled in as each step is tested)
