"""Voice input: pieces of audio -> text labelled Me or Others; messages sent from voice mode carry the voice rules once.

The fake audio is a constant level per piece: the level picks the voice (the fake speaker model maps it to a fixed
voice vector) and the words (the fake transcription API looks it up).
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
EMBEDDED: list[int] = []  # the length of each piece the speaker model saw


def piece(level: int, seconds: float = 1.5) -> bytes:
    return (np.full(int(seconds * va.RATE), level * 1000, dtype="<i2")).tobytes()


class FakeModels:
    """Voices are axis vectors: level 1..9 -> voice (level % 10); the owner is voice 0 (levels 10, 20...)."""

    def missing(self) -> list[str]:
        return []

    def embed(self, audio: np.ndarray):
        EMBEDDED.append(len(audio))
        level = round(float(audio[0]) * 32768 / 1000)
        v = np.zeros(256, dtype=np.float32)
        v[level % 10] = 1.0
        return v


def _fake_api(request: httpx.Request) -> httpx.Response:
    if request.url.host == "stt.test":
        body = request.read()
        STT.append(body)
        start = body.index(b"RIFF")
        with wave.open(io.BytesIO(body[start:]), "rb") as w:
            pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
        level = round(int(pcm[0]) / 1000)
        return httpx.Response(200, json={"text": WORDS.get(level, "")})
    return httpx.Response(404)


@pytest.fixture
async def voice(hub, tmp_path):  # noqa: F811
    WORDS.clear()
    STT.clear()
    EMBEDDED.clear()
    v = hub.voice
    v.settings = VoiceSettings(stt_url="http://stt.test/v1", stt_model="fake-stt", stt_fields=(("store", "false"),),
                               models_dir=tmp_path / "models", voiceprint_file=tmp_path / "voiceprint.json")
    v.models = FakeModels()
    v.http = httpx.AsyncClient(transport=httpx.MockTransport(_fake_api))
    me = np.zeros(256, dtype=np.float32)
    me[0] = 1.0
    v.me = me
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


async def test_pieces_come_back_as_me_or_others(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid)
    assert s["enrolled"] is True
    vid = s["voice_id"]
    WORDS.update({10: "Can you check their order?", 3: "My order never came", 4: "yes", 13: "It was last week"})
    me = await voice.piece(sid, vid, 0, piece(10))
    assert me == {"seq": 0, "speaker": "me", "label": "Me", "text": "Can you check their order?"}
    other = await voice.piece(sid, vid, 1, piece(3))
    assert (other["speaker"], other["label"], other["text"]) == ("others", "Others", "My order never came")
    short = await voice.piece(sid, vid, 2, piece(10, seconds=0.6))  # too short to tell: the last speaker's
    assert short["speaker"] == "others"
    long = await voice.piece(sid, vid, 3, piece(13, seconds=12))
    assert long["label"] == "Others" and EMBEDDED[-1] == int(voice_mod.SPEAKER_MAX_SECONDS * va.RATE)  # its start only
    WORDS[3] = "something else"
    assert await voice.piece(sid, vid, 1, piece(3)) == other  # a resend gets the first answer
    assert all(b'name="store"\r\n\r\nfalse' in body for body in STT)  # the Warehouse is asked to keep nothing
    # Nothing goes into the chat by itself: only the owner's Send does.
    assert not [e for e in await hub.events(0, sid) if e["kind"] == "user" and e["payload"].get("meta")]
    with pytest.raises(voice_mod.VoiceGone):
        await voice.piece(sid, "0" * 32, 4, piece(3))
    again = await voice.start_session(sid, vid)  # the phone goes on with its session
    assert again["voice_id"] == vid
    voice.me = None  # no voiceprint yet: everyone is Others
    assert (await voice.piece(sid, vid, 5, piece(10)))["speaker"] == "others"


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
        assert r.status_code == 200 and r.json() == {"seq": 0, "speaker": "others", "label": "Others",
                                                      "text": "Is the server down?"}
        r = await client.post(f"/api/sessions/{sid}/voice/{'0' * 32}/audio?seq=1", content=piece(1), headers=auth)
        assert r.status_code == 409  # a voice session the server does not have: the phone starts a new one
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/audio?seq=2", content=b"\0" * (31 * va.RATE * 2), headers=auth)
        assert r.status_code == 413
        r = await client.post(f"/api/sessions/{sid}/messages", json={"text": "Others: Is the server down?", "voice": True},
                              headers=auth)
        assert r.status_code == 202 and r.json()["message"]["meta"] == {"voice": True}
        r = await client.delete(f"/api/sessions/{sid}/voice/{vid}", headers=auth)
        assert r.json() == {"active": False}
    assert sid not in voice.sessions
    await until(lambda: _turns(hub, sid, 2))
    assert "voice-rules" in assistant_text(await hub.events(0, sid))


async def test_enrollment_needs_enough_speech(hub, voice):  # noqa: F811
    # Reading aloud: bursts of sound with short gaps between words.
    rng = np.random.default_rng(1)
    bursts = [np.concatenate([rng.normal(0, 3000, 8000), np.zeros(3200)]) for _ in range(40)]
    noise = np.concatenate(bursts).astype("<i2").tobytes()
    out = await voice.enroll(noise)
    assert out["enrolled"] and voice.settings.voiceprint_file.stat().st_mode & 0o777 == 0o600
    with pytest.raises(voice_mod.VoiceError):
        await voice.enroll(bytes(5 * va.RATE * 2))


def test_features_hold_their_shape_and_values():
    t = np.arange(va.RATE) / va.RATE
    tone = (0.3 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
    fb = va.kaldi_fbank(tone)
    assert fb.shape == (98, 80)
    assert int(np.argmax(fb[50])) == 14  # 440 Hz: mel 550, bins 34.7 mel apart from mel 31.7 (20 Hz)
