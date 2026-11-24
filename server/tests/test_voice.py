"""Voice input: pieces of audio -> speakers and lines -> router -> chat messages, with fake models and APIs.

The fake audio is a constant level per piece: the level picks the speaker (the fake speaker model maps it to a
fixed voice vector) and the words (the fake transcription API looks it up). The fake router answers per line text.
"""

from __future__ import annotations

import asyncio
import io
import json
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
# line text -> (choice, confidence, probabilities) the fake router answers
ROUTES: dict[str, tuple[str, float, dict[str, float]]] = {}
ROUTED: list[dict] = []  # router requests, for checks


def piece(level: int, seconds: float = 1.5) -> bytes:
    return (np.full(int(seconds * va.RATE), level * 1000, dtype="<i2")).tobytes()


class FakeModels:
    """Voices are axis vectors: level 1..9 -> speaker (level % 10)."""

    def missing(self) -> list[str]:
        return []

    def embed(self, audio: np.ndarray):
        level = round(float(audio[0]) * 32768 / 1000)
        v = np.zeros(256, dtype=np.float32)
        v[level % 10] = 1.0
        return v

    def turn_done(self, audio: np.ndarray) -> float:
        return 0.9


def _fake_api(request: httpx.Request) -> httpx.Response:
    if request.url.host == "stt.test":
        body = request.read()
        start = body.index(b"RIFF")
        with wave.open(io.BytesIO(body[start:]), "rb") as w:
            pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
        level = round(int(pcm[0]) / 1000)
        return httpx.Response(200, json={"text": WORDS.get(level, "")})
    if request.url.host == "router.test":
        data = json.loads(request.read())
        ROUTED.append(data)
        line = data["state"].rsplit('Latest finished line, from ', 1)[1]
        text = line.split(': "', 1)[1].rstrip('"')
        choice, conf, probs = ROUTES.get(text, ("new", 0.95, {"new": 0.95, "context": 0.05}))
        return httpx.Response(200, json={"answers": {"action": {"choice": choice, "confidence": conf, "probabilities": probs}}})
    return httpx.Response(404)


@pytest.fixture
async def voice(hub, tmp_path):  # noqa: F811
    WORDS.clear()
    ROUTES.clear()
    ROUTED.clear()
    keys = tmp_path / "keys.env"
    keys.write_text("OPENROUTER_API_KEY=test-key\n")
    v = hub.voice
    v.settings = VoiceSettings(stt_url="http://stt.test/v1", stt_model="fake-stt", router_url="http://router.test/decide",
                               router_model="fake-router", key_file=keys, models_dir=tmp_path / "models",
                               voiceprint_file=tmp_path / "voiceprint.json")
    v.models = FakeModels()
    v.http = httpx.AsyncClient(transport=httpx.MockTransport(_fake_api))
    me = np.zeros(256, dtype=np.float32)
    me[0] = 1.0  # level 10, 20...: the owner
    v.me = me
    return v


async def chat(hub):  # noqa: F811
    session = await hub.create_session("hello")  # the owner's typed context
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    return sid


async def users(hub, sid):  # noqa: F811
    return [e["payload"] for e in await hub.events(0, sid) if e["kind"] == "user" and (e["payload"].get("meta") or {}).get("voice")]


async def until(check, timeout=10.0):
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if result := await check():
            return result
        await asyncio.sleep(0.05)
    raise AssertionError("timed out")


async def test_lines_become_labelled_messages_and_the_rules_come_once(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid, my_voice=True)
    WORDS.update({1: "Can you open my website", 11: "and check the login page?", 3: "What does the error mean?"})
    voice.models.turn_done = lambda audio: 0.2 if len(audio) < 30000 else 0.9  # the first piece is not finished
    first = await voice.piece(sid, s["voice_id"], 0, piece(1), False)
    assert first["label"] == "P1" and first["text"] == "Can you open my website" and not first["done"]
    second = await voice.piece(sid, s["voice_id"], 1, piece(11), False)  # the same voice (level % 10)
    assert second["done"] and second["line"] == "Can you open my website and check the login page?"
    again = await voice.piece(sid, s["voice_id"], 1, piece(11), False)  # a resend gets the same answer
    assert again == second
    msgs = await until(lambda: users(hub, sid))
    assert msgs[0]["text"] == "Can you open my website and check the login page?"
    assert msgs[0]["meta"]["voice"] == {"line": second["line_id"], "speaker": "P1", "kind": "new", "answer": "A1",
                                        "confidence": 0.95}
    await wait_for(hub, sid, "turn_end", lambda p: True)
    await until(lambda: _turns(hub, sid, 2))
    events = await hub.events(0, sid)
    text = assistant_text(events)
    assert "voice-rules" in text and '[Voice] P1 said: "Can you open my website and check the login page?"' in text
    # The router heard the owner's typed context and nothing else yet.
    assert "Owner's note before the conversation: hello" in ROUTED[0]["state"]
    assert ROUTED[0]["provider"] == {"zdr": True, "allow_fallbacks": False}
    voice.models.turn_done = lambda audio: 0.9
    await voice.piece(sid, s["voice_id"], 2, piece(3), False)  # a new voice: P2
    await until(lambda: _turns(hub, sid, 3))
    text = assistant_text(await hub.events(0, sid))
    assert text.count("voice-rules") == 1  # the rules came with the first voice message only
    assert '[Voice] P2 said: "What does the error mean?"' in text
    assert "A1 (answered): Can you open my website and check the login page? (asked by P1)" in ROUTED[1]["state"]


async def _turns(hub, sid, n):  # noqa: F811
    return [e["kind"] for e in await hub.events(0, sid)].count("turn_end") >= n


async def test_a_new_voice_session_goes_on_with_the_answer_ids(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid, my_voice=True)
    WORDS.update({1: "What is a tumbling window?", 11: "And a sliding one?"})
    await voice.piece(sid, s["voice_id"], 0, piece(1), False)
    await until(lambda: users(hub, sid))
    await voice.stop(sid, s["voice_id"])
    again = await voice.start_session(sid, my_voice=True)
    ROUTES["And a sliding one?"] = ("add_A1", 0.9, {"add_A1": 0.9, "new": 0.1})
    await voice.piece(sid, again["voice_id"], 0, piece(11), False)
    msgs = await until(lambda: _two(hub, sid))
    assert msgs[1]["meta"]["voice"]["kind"] == "add" and msgs[1]["meta"]["voice"]["answer"] == "A1"
    assert "A1 (" in ROUTED[-1]["state"]  # the earlier answer is still open after the restart
    assert voice.sessions[sid].next_answer == 2


async def test_my_voice_off_drops_the_owners_lines_but_instructions_go(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid, my_voice=False)
    WORDS.update({10: "Let me think about that", 20: "Look up their order history"})
    await voice.piece(sid, s["voice_id"], 0, piece(10), False)
    dropped = await wait_for(hub, sid, "voice_line")
    assert dropped["speaker"] == "Me" and dropped["state"] == "not_sent"
    await voice.piece(sid, s["voice_id"], 1, piece(20), True)  # held "instruct": the thought stays open until let go
    assert not await users(hub, sid)
    await voice.pause(sid, s["voice_id"])
    msgs = await until(lambda: users(hub, sid))
    assert msgs[0]["meta"]["voice"]["kind"] == "instruct" and msgs[0]["text"] == "Look up their order history"
    assert not any("Look up" in r["state"] for r in ROUTED)  # instructions skip the router
    await until(lambda: _turns(hub, sid, 2))
    assert '[Voice] Instruction from the owner: "Look up their order history"' in assistant_text(await hub.events(0, sid))


async def test_context_is_held_and_goes_with_the_next_message(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid, my_voice=True)
    WORDS.update({1: "Thanks, that is helpful", 11: "How much is the premium plan?"})
    ROUTES["Thanks, that is helpful"] = ("context", 0.9, {"context": 0.9, "new": 0.1})
    await voice.piece(sid, s["voice_id"], 0, piece(1), False)
    held = await wait_for(hub, sid, "voice_line")
    assert held["state"] == "held" and held["speaker"] == "P1"
    await voice.piece(sid, s["voice_id"], 1, piece(11), False)
    msgs = await until(lambda: users(hub, sid))
    assert msgs[0]["meta"]["voice"]["meanwhile"] == [{"speaker": "P1", "text": "Thanks, that is helpful"}]
    await until(lambda: _turns(hub, sid, 2))
    text = assistant_text(await hub.events(0, sid))
    assert 'Said meanwhile (no reply was needed): / - P1: "Thanks, that is helpful"' in text


async def test_an_addition_steers_into_its_running_answer(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid, my_voice=True)
    WORDS.update({1: "Please look into this slow checkout", 11: "It only happens on mobile"})
    ROUTES["It only happens on mobile"] = ("add_A1", 0.92, {"add_A1": 0.92, "new": 0.08})
    await voice.piece(sid, s["voice_id"], 0, piece(1), False)
    await wait_for(hub, sid, "text", lambda p: p["role"] == "thought", timeout=10)  # the "slow" turn runs
    await asyncio.sleep(0.2)
    await voice.piece(sid, s["voice_id"], 1, piece(11), False)
    msgs = await until(lambda: _two(hub, sid))
    add = msgs[1]
    assert add["delivery"] == "steer" and add["meta"]["voice"]["kind"] == "add" and add["meta"]["voice"]["answer"] == "A1"
    await until(lambda: _turns(hub, sid, 2), timeout=20)
    text = assistant_text(await hub.events(0, sid))
    assert "steered=[Voice] P1 adds to your answer A1 (Please look into this slow checkout)" in text


async def _two(hub, sid):  # noqa: F811
    msgs = await users(hub, sid)
    return msgs if len(msgs) >= 2 else None


async def test_unsure_reply_or_not_asks_the_owner_briefly(hub, voice, monkeypatch):  # noqa: F811
    monkeypatch.setattr(voice_mod, "CHOICE_SECONDS", 0.5)
    sid = await chat(hub)
    s = await voice.start_session(sid, my_voice=True)
    WORDS.update({1: "Hmm I wonder about the pricing", 11: "Maybe we should check the logs"})
    ROUTES["Hmm I wonder about the pricing"] = ("new", 0.55, {"new": 0.55, "context": 0.45})
    ROUTES["Maybe we should check the logs"] = ("context", 0.52, {"context": 0.52, "new": 0.48})
    await voice.piece(sid, s["voice_id"], 0, piece(1), False)
    ask = await wait_for(hub, sid, "voice_choice")
    assert [o["id"] for o in ask["options"]] == ["new", "context"] and ask["default"] == "new"
    voice.choose(sid, s["voice_id"], ask["line_id"], "context")  # the owner says no reply is needed
    done = await wait_for(hub, sid, "voice_choice_done")
    assert done == {"line_id": ask["line_id"], "choice": "context", "by": "owner"}
    held = await wait_for(hub, sid, "voice_line")
    assert held["state"] == "held"
    await voice.piece(sid, s["voice_id"], 1, piece(11), False)  # nobody answers: the likelier choice wins
    late = await wait_for(hub, sid, "voice_choice_done", lambda p: p["line_id"] != ask["line_id"])
    assert late["by"] == "timeout" and late["choice"] == "context"
    assert not await users(hub, sid)
    await voice.change(sid, s["voice_id"], ask=False)  # "Ask when unsure" off: the likelier choice, no question
    WORDS[21] = "Hmm I wonder about the pricing"
    await voice.piece(sid, s["voice_id"], 2, piece(21), False)
    await until(lambda: users(hub, sid))
    assert [e["kind"] for e in await hub.events(0, sid)].count("voice_choice") == 2


async def test_echo_of_the_reply_is_dropped(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid, my_voice=True)
    voice.reply(sid, "You can say: the refund will reach your card in three to five working days.")
    WORDS.update({10: "The refund will reach your card in three to five days"})
    await voice.piece(sid, s["voice_id"], 0, piece(10), False)
    line = await wait_for(hub, sid, "voice_line")
    assert line["state"] == "echo" and line["speaker"] == "Me"
    assert voice_mod.is_echo("Okay, what about Tuesday?", ["You can say: the refund will reach your card"]) is False


async def test_speakers_are_renamed_and_merged(hub, voice):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid, my_voice=True)
    WORDS.update({1: "Hello there", 2: "Good morning", 12: "Me again"})
    await voice.piece(sid, s["voice_id"], 0, piece(1), False)
    await voice.piece(sid, s["voice_id"], 1, piece(2), False)
    state = await voice.rename(sid, s["voice_id"], "P1", "Sara", None)
    assert [(x["id"], x["label"]) for x in state["speakers"]] == [("P1", "Sara"), ("P2", "P2")]
    state = await voice.rename(sid, s["voice_id"], "P2", None, "P1")  # "P2 is Sara"
    assert [x["id"] for x in state["speakers"]] == ["P1"]
    third = await voice.piece(sid, s["voice_id"], 2, piece(12), False)  # her other voice now joins her
    assert third["label"] == "Sara"


async def test_api_takes_pieces_and_ends_unknown_sessions(hub, voice):  # noqa: F811
    sid = await chat(hub)
    app = create_app(hub, TOKEN, lifespan_hub=False)
    auth = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        assert (await client.get("/api/voice", headers=auth)).json()["enrolled"] is True
        r = await client.post(f"/api/sessions/{sid}/voice", json={"my_voice": True}, headers=auth)
        vid = r.json()["voice_id"]
        WORDS[1] = "Is the server down?"
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/audio?seq=0", content=piece(1), headers=auth)
        assert r.status_code == 200 and r.json()["label"] == "P1" and r.json()["done"]
        r = await client.post(f"/api/sessions/{sid}/voice/{'0' * 32}/audio?seq=1", content=piece(1), headers=auth)
        assert r.status_code == 409  # a voice session the server does not have: the phone starts a new one
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/audio?seq=2", content=b"\0" * (31 * va.RATE * 2), headers=auth)
        assert r.status_code == 413
        r = await client.delete(f"/api/sessions/{sid}/voice/{vid}", headers=auth)
        assert r.json() == {"active": False}
    await until(lambda: users(hub, sid))
    assert sid not in voice.sessions


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
    mel = va.whisper_mel(tone)
    assert mel.shape == (80, 800) and float(mel.max() - mel.min()) <= 2.0 + 1e-6  # clamped 8 log10 units below the peak, /4
