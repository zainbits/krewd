"""Voice input: pieces of audio -> lines labelled Me or Others; messages sent from voice mode carry the voice rules once.

The fake audio is a run of constant levels: each level is one stretch of speech, and the level picks the words (the fake
transcription API looks them up) and the voice (levels 10, 20... are the owner's, whose reference the API is sent).
"""

from __future__ import annotations

import asyncio
import io
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
STT: list[bytes] = []  # each transcription request's body


def piece(*parts: int | tuple[int, float]) -> bytes:
    """Stretches of speech, each a level or (level, seconds); 1.5 s by default."""
    out = []
    for part in parts:
        level, seconds = part if isinstance(part, tuple) else (part, 1.5)
        out.append(np.full(int(seconds * va.RATE), level * 1000, dtype="<i2"))
    return np.concatenate(out).tobytes()


def _levels(wav: bytes) -> list[int]:
    with wave.open(io.BytesIO(wav), "rb") as w:
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    levels = [round(int(x) / 1000) for x in pcm[:: va.RATE // 10]]
    return [lv for i, lv in enumerate(levels) if i == 0 or lv != levels[i - 1]]


def _fake_api(request: httpx.Request) -> httpx.Response:
    if request.url.host != "stt.test":
        return httpx.Response(404)
    body = request.read()
    STT.append(body)
    files = {}
    for part in body.split(b"Content-Disposition: form-data; name=")[1:]:
        name = part.split(b'"')[1].decode()
        if b"RIFF" in part:
            files[name] = part[part.index(b"RIFF"):].rsplit(b"\r\n--", 1)[0]
    runs = [(lv, WORDS.get(lv, "")) for lv in _levels(files["file"])]
    text = " ".join(t for _, t in runs if t)
    if "speaker_reference" not in files:
        return httpx.Response(200, json={"text": text})
    assert _levels(files["speaker_reference"])  # the owner's reference came along
    speakers = [{"speaker": None if lv == 31 else "reference" if lv % 10 == 0 else "other", "text": t}
                for lv, t in runs if t]
    return httpx.Response(200, json={"text": text, "speakers": speakers})


@pytest.fixture
async def voice(hub, tmp_path):  # noqa: F811
    WORDS.clear()
    STT.clear()
    v = hub.voice
    v.settings = VoiceSettings(stt_url="http://stt.test/v1", stt_model="fake-stt", stt_fields=(("store", "false"),),
                               reference_file=tmp_path / "voice-reference.wav",
                               old_voiceprint_file=tmp_path / "voiceprint.json")
    v.http = httpx.AsyncClient(transport=httpx.MockTransport(_fake_api))
    v.me = va.wav_bytes(np.full(va.RATE, 0.3, dtype=np.float32))
    return v


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


async def test_pieces_come_back_as_me_and_others_lines(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid)
    assert s["enrolled"] is True
    vid = s["voice_id"]
    WORDS.update({10: "Can you check their order?", 3: "My order never came.", 4: "It was last week.",
                  20: "Let me look.", 31: "Okay."})
    me = await voice.piece(sid, vid, 0, piece(10))
    assert me == {"seq": 0, "lines": [{"speaker": "me", "label": "Me", "text": "Can you check their order?"}],
                  "text": "Can you check their order?"}
    both = await voice.piece(sid, vid, 1, piece(3, 4, 20, 31))  # two voices in one piece
    assert both["lines"] == [
        {"speaker": "others", "label": "Others", "text": "My order never came. It was last week."},
        {"speaker": "me", "label": "Me", "text": "Let me look. Okay."},  # words the model could not place: the last voice
    ]
    WORDS[3] = "something else"
    assert await voice.piece(sid, vid, 1, piece(3)) == both  # a resend gets the first answer
    assert (await voice.piece(sid, vid, 2, piece(7)))["lines"] == []  # nothing said
    assert all(b'name="store"\r\n\r\nfalse' in body for body in STT)  # the Warehouse is asked to keep nothing
    # Nothing goes into the chat by itself: only the owner's Send does.
    assert not [e for e in await hub.events(0, sid) if e["kind"] == "user" and e["payload"].get("meta")]
    with pytest.raises(voice_mod.VoiceGone):
        await voice.piece(sid, "0" * 32, 4, piece(3))
    again = await voice.start_session(sid, vid)  # the phone goes on with its session
    assert again["voice_id"] == vid
    voice.me = None  # My voice not set up: everyone is Others, and no reference is sent
    out = await voice.piece(sid, vid, 5, piece(10))
    assert out["lines"] == [{"speaker": "others", "label": "Others", "text": "Can you check their order?"}]
    assert b"speaker_reference" not in STT[-1]


async def test_voice_messages_carry_the_rules_once_per_agent_session(hub, voice):  # noqa: F811
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


async def test_a_failed_steer_gives_the_rules_back_only_if_it_carried_them(hub, voice):  # noqa: F811
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


async def test_api_takes_pieces_and_voice_messages(hub, voice):  # noqa: F811
    sid = await chat(hub)
    app = create_app(hub, TOKEN, lifespan_hub=False)
    auth = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        status = (await client.get("/api/voice", headers=auth)).json()
        assert status["enrolled"] is True and status["available"] is True and "router" not in status
        r = await client.post(f"/api/sessions/{sid}/voice", json={}, headers=auth)
        vid = r.json()["voice_id"]
        WORDS[1] = "Is the server down?"
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/audio?seq=0", content=piece(1), headers=auth)
        assert r.status_code == 200 and r.json() == {
            "seq": 0, "lines": [{"speaker": "others", "label": "Others", "text": "Is the server down?"}],
            "text": "Is the server down?"}
        r = await client.post(f"/api/sessions/{sid}/voice/{'0' * 32}/audio?seq=1", content=piece(1), headers=auth)
        assert r.status_code == 409  # a voice session the server does not have: the phone starts a new one
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/audio?seq=2", content=b"\0" * (33 * va.RATE * 2), headers=auth)
        assert r.status_code == 413
        r = await client.post(f"/api/sessions/{sid}/messages", json={"text": "Others: Is the server down?", "voice": True},
                              headers=auth)
        assert r.status_code == 202 and r.json()["message"]["meta"] == {"voice": True}
        r = await client.delete(f"/api/sessions/{sid}/voice/{vid}", headers=auth)
        assert r.json() == {"active": False}
    assert sid not in voice.sessions
    await until(lambda: _turns(hub, sid, 2))
    assert "voice-rules" in assistant_text(await hub.events(0, sid))


async def test_setup_keeps_a_short_reference_with_pauses_shortened(hub, voice):  # noqa: F811
    voice.settings.old_voiceprint_file.write_text("{}")
    # Reading aloud: 0.5 s bursts of sound with 1 s pauses between them.
    rng = np.random.default_rng(1)
    bursts = [np.concatenate([rng.normal(0, 3000, 8000), np.zeros(16000)]) for _ in range(40)]
    reading = np.concatenate(bursts).astype("<i2").tobytes()
    out = await voice.enroll(reading)
    assert out["enrolled"] and 19 <= out["speech_seconds"] <= 21
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
