"""Voice input: audio about 250 ms at a time -> segments (one speaker turn each) labelled Me or Others, that grow, then
are final; messages sent from voice mode carry the voice rules once.

The fake audio is a run of constant levels, and the fake live API turns each level into one speaker turn (levels 10,
20... are the owner's, whose reference the API is sent; 31 is a voice it cannot place); a turn is final once another
one starts, or on flush / end.
"""

from __future__ import annotations

import asyncio
import uuid
import wave

import httpx
import numpy as np
import pytest

from krewd_server import voice as voice_mod
from krewd_server import voice_audio as va
from krewd_server.api import create_app
from krewd_server.config import VoiceSettings

from test_flow import DATABASE_URL, TOKEN, assistant_text, hub, wait_for  # noqa: F401  (hub is a fixture)

pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="KREWD_TEST_DATABASE_URL is not set")

# level (x 1000, in 16-bit units) -> what the fake transcription hears
WORDS: dict[int, str] = {}


def piece(*parts: int | tuple[int, float]) -> bytes:
    """Stretches of speech, each a level or (level, seconds); 1.5 s by default."""
    out = []
    for part in parts:
        level, seconds = part if isinstance(part, tuple) else (part, 1.5)
        out.append(np.full(int(seconds * va.RATE), level * 1000, dtype="<i2"))
    return np.concatenate(out).tobytes()


class FakeLive:
    def __init__(self) -> None:
        self.sessions: dict[str, dict] = {}
        self.opened: list[dict] = []
        self.end_after: int | None = None  # posts until the API ends a session by itself (quiet, GPU wanted)

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/v1/audio/live" and request.method == "POST":
            body = request.read()
            sid = uuid.uuid4().hex
            self.sessions[sid] = {"runs": [], "final": 0, "posts": 0, "status": "live"}
            self.opened.append({"reference": b"speaker_reference" in body, "model": b"fake-live" in body})
            return httpx.Response(201, json={"id": sid, "status": "queued", "segments": []})
        parts = path.split("/")
        sid = parts[4] if len(parts) > 4 else ""
        s = self.sessions.get(sid)
        if request.method == "DELETE":
            if s:
                s["status"] = "ended"
            return httpx.Response(200, json={})
        if s is None:
            return httpx.Response(404, json={"detail": "no such live session"})
        if request.method == "GET":
            return httpx.Response(200, json=self.state(s, int(request.url.params.get("after", 0))))
        if s["status"] == "ended":
            return httpx.Response(404, json={"detail": "ended"})
        pcm = np.frombuffer(request.read(), dtype="<i2")
        for level in [round(int(x) / 1000) for x in pcm[:: 1600]]:
            if not s["runs"] or s["runs"][-1] != level:
                s["runs"].append(level)
        s["final"] = max(s["final"], len(s["runs"]) - 1)
        q = request.url.params
        if q.get("flush") == "true" or q.get("end") == "true":
            s["final"] = len(s["runs"])
        if q.get("end") == "true":
            s["status"] = "ended"
        s["posts"] += 1
        if self.end_after and s["posts"] >= self.end_after:
            s["status"] = "ended"  # the next post gets 404, its finals stay readable (GET)
        return httpx.Response(200, json=self.state(s, int(q.get("after", 0))))

    @staticmethod
    def state(s: dict, after: int) -> dict:
        segs = []
        for i, level in enumerate(s["runs"]):
            if i < after:
                continue
            speaker = None if level == 31 else "reference" if level % 10 == 0 else "other"
            final = i < s["final"]
            text = WORDS.get(level, "")
            segs.append({"id": i, "start": i, "end": i + 1 if final else None, "speaker": speaker,
                         "text": text if final else " ".join(text.split()[:2]), "final": final})
        return {"status": s["status"], "heard_seconds": 1.0, "segments": segs}


@pytest.fixture
async def voice(hub, tmp_path):  # noqa: F811
    WORDS.clear()
    v = hub.voice
    v.settings = VoiceSettings(stt_url="http://stt.test/v1", live_model="fake-live",
                               reference_file=tmp_path / "voice-reference.wav",
                               old_voiceprint_file=tmp_path / "voiceprint.json")
    v.me = va.wav_bytes(np.full(va.RATE, 0.3, dtype=np.float32))
    return v


@pytest.fixture
def live(voice):
    fake = FakeLive()
    voice.http = httpx.AsyncClient(transport=httpx.MockTransport(fake))
    return fake


async def chat(hub, first: str = "hello"):  # noqa: F811
    session = await hub.create_session(first)  # the owner's typed context
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    return sid


async def until(check, timeout=10.0):
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if result := await check():
            return result
        await asyncio.sleep(0.05)
    raise AssertionError("timed out")


async def _turns(hub, sid, n):  # noqa: F811
    return [e["kind"] for e in await hub.events(0, sid)].count("turn_end") >= n


def _texts(result):
    return [(s["id"], s["label"], s["text"], s["final"]) for s in result["segments"]]


async def test_live_words_grow_then_turn_final_by_speaker(hub, voice, live):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid)
    assert s["enrolled"] is True and live.opened == [{"reference": True, "model": True}]  # opened at once: the model loads
    vid = s["voice_id"]
    WORDS.update({3: "Tell me about your last project.", 10: "I built a voice assistant.", 31: "Right."})
    r = await voice.live(sid, vid, 0, piece(3))
    assert _texts(r) == [(0, "Others", "Tell me", False)]  # live words, the turn goes on
    r = await voice.live(sid, vid, 1, piece(10))
    assert _texts(r) == [(0, "Others", "Tell me about your last project.", True), (1, "Me", "I built", False)]
    assert await voice.live(sid, vid, 1, piece(3)) == r  # a resend gets the first answer
    r = await voice.live(sid, vid, 2, piece(31), after=1)  # the phone has segment 0
    assert _texts(r) == [(1, "Me", "I built a voice assistant.", True), (2, None, "Right.", False)]
    r = await voice.live(sid, vid, 3, b"", after=2, flush=True)  # Send: everything final
    assert _texts(r) == [(2, "Me", "Right.", True)]  # a voice it could not place: the last speaker's


async def test_a_new_api_session_carries_on_the_numbering(hub, voice, live):  # noqa: F811
    sid = await chat(hub)
    vid = (await voice.start_session(sid))["voice_id"]
    WORDS.update({3: "First question.", 4: "Second question."})
    live.end_after = 1  # the API ends its session after one post (it gave the GPU to another request)
    r = await voice.live(sid, vid, 0, piece(3))
    assert _texts(r) == [(0, "Others", "First question.", False)]
    r = await voice.live(sid, vid, 1, piece(4))  # 404: the old session's words are read, then a new one opens
    assert len(live.opened) == 2
    ids = [x[0] for x in _texts(r)]
    assert ids == sorted(set(ids)) and ids[-1] >= 1
    assert _texts(r)[-1][2].startswith("Second")


async def test_without_my_voice_everyone_is_others(hub, voice, live):  # noqa: F811
    voice.me = None
    sid = await chat(hub)
    vid = (await voice.start_session(sid))["voice_id"]
    WORDS[10] = "Me talking."
    r = await voice.live(sid, vid, 0, piece(10), flush=True)
    assert _texts(r) == [(0, "Others", "Me talking.", True)]
    assert live.opened == [{"reference": False, "model": True}]


async def test_api_takes_live_audio_and_voice_messages(hub, voice, live):  # noqa: F811
    sid = await chat(hub)
    app = create_app(hub, TOKEN, lifespan_hub=False)
    auth = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        status = (await client.get("/api/voice", headers=auth)).json()
        assert status["enrolled"] is True and status["available"] is True and status["transcription"] == "fake-live"
        vid = (await client.post(f"/api/sessions/{sid}/voice", json={}, headers=auth)).json()["voice_id"]
        WORDS[5] = "Is the server down?"
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/live?seq=0&end=true", content=piece(5), headers=auth)
        assert r.status_code == 200 and _texts(r.json()) == [(0, "Others", "Is the server down?", True)]
        r = await client.post(f"/api/sessions/{sid}/voice/{'0' * 32}/live?seq=1", content=b"", headers=auth)
        assert r.status_code == 409  # a voice session the server does not have: the phone starts a new one
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/live?seq=2", content=b"\0" * (11 * 16000 * 2),
                              headers=auth)
        assert r.status_code == 413
        r = await client.post(f"/api/sessions/{sid}/messages", json={"text": "Others: Is the server down?", "voice": True},
                              headers=auth)
        assert r.status_code == 202 and r.json()["message"]["meta"] == {"voice": True}
        r = await client.delete(f"/api/sessions/{sid}/voice/{vid}", headers=auth)
        assert r.json() == {"active": False}
    assert sid not in voice.sessions
    await until(lambda: _turns(hub, sid, 2))
    assert "voice-rules" in assistant_text(await hub.events(0, sid))


async def test_voice_messages_carry_the_rules_once_per_agent_session(hub, voice, live):  # noqa: F811
    sid = await chat(hub)
    first = await hub.send(sid, "Others: Where is my refund?\nMe: Let me check.", meta={"voice": True})
    await until(lambda: _turns(hub, sid, 2))
    user = await wait_for(hub, sid, "user", lambda p: p["message_id"] == first["id"])
    assert user["meta"] == {"voice": True}
    await hub.send(sid, "Others: It was in March.", meta={"voice": True})
    await hub.send(sid, "typed, no voice")
    await until(lambda: _turns(hub, sid, 4))
    text = assistant_text(await hub.events(0, sid))
    assert text.count("voice-rules") == 1  # the first voice message only


async def test_a_failed_steer_gives_the_rules_back_only_if_it_carried_them(hub, voice, live):  # noqa: F811
    sid = await chat(hub)
    rt = hub.runtimes[sid]
    await hub.send(sid, "pause one")  # a turn long enough to steer into
    await until(lambda: _running(hub, sid))
    await hub.send(sid, "Others: nosteer first", meta={"voice": True}, delivery="steer")  # carried the rules, failed
    assert await hub.pool.fetchval("select voice_rules_for from sessions where id = $1", sid) is None
    await until(lambda: _turns(hub, sid, 3))  # it ran as the next prompt, with the rules
    assert assistant_text(await hub.events(0, sid)).count("voice-rules") == 1
    given = await hub.pool.fetchval("select voice_rules_for from sessions where id = $1", sid)
    assert given == rt.agent_session
    await hub.send(sid, "pause two")
    await until(lambda: _running(hub, sid))
    await hub.send(sid, "Others: nosteer second", meta={"voice": True}, delivery="steer")  # no rules on it, failed
    assert await hub.pool.fetchval("select voice_rules_for from sessions where id = $1", sid) == given
    await until(lambda: _turns(hub, sid, 5))
    assert assistant_text(await hub.events(0, sid)).count("voice-rules") == 1  # not given twice


async def _running(hub, sid):  # noqa: F811
    rt = hub.runtimes.get(sid)
    return rt is not None and rt.running


async def test_setup_keeps_a_short_reference_with_pauses_shortened(hub, voice, live):  # noqa: F811
    voice.settings.old_voiceprint_file.write_text("{}")
    # Reading aloud: 0.5 s bursts of sound with 1 s pauses between them.
    rng = np.random.default_rng(1)
    bursts = [np.concatenate([rng.normal(0, 3000, 8000), np.zeros(16000)]) for _ in range(40)]
    reading = np.concatenate(bursts).astype("<i2").tobytes()
    out = await voice.enroll(reading)
    assert out["enrolled"] and 31 <= out["speech_seconds"] <= 33  # 0.5 s of sound + 0.3 s of each pause, x 40
    path = voice.settings.reference_file
    assert path.stat().st_mode & 0o777 == 0o600 and not voice.settings.old_voiceprint_file.exists()
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == va.RATE and w.getnframes() == voice_mod.REFERENCE_SECONDS * va.RATE
        clip = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    frames = np.abs(clip[: len(clip) // 480 * 480].reshape(-1, 480)).mean(axis=1)
    assert (frames < 50).mean() < 0.45  # 1 s pauses cut to 0.3 s: mostly speech
    assert voice.me == path.read_bytes()
    with pytest.raises(voice_mod.VoiceError):
        await voice.enroll(bytes(5 * va.RATE * 2))
    voice.forget_voiceprint()
    assert voice.me is None and not path.exists()


def test_a_real_reading_with_soft_sounds_is_enough():
    # 30 s of reading where only about 13 s is loud (the rest soft sounds and gaps between words): setup used to fail.
    rng = np.random.default_rng(2)
    word = np.concatenate([rng.normal(0, 0.1, 2600), rng.normal(0, 0.003, 2000), np.zeros(1400)])  # 0.375 s
    reading = np.tile(word, 80).astype(np.float32)
    clip, seconds = voice_mod.reference_clip(reading)
    assert clip is not None and seconds >= 25 and len(clip) == voice_mod.REFERENCE_SECONDS * va.RATE
