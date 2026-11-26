"""Voice input, Live mode: audio about 250 ms at a time -> segments (one speaker turn each) that grow, then are final.

The fake live API turns each level of the fake audio into one speaker turn (levels 10, 20... are the owner's, 31 is a
voice it cannot place); a turn is final once another one starts, or on flush / end.
"""

from __future__ import annotations

import uuid

import httpx
import numpy as np
import pytest

from krewd_server.api import create_app

from test_flow import DATABASE_URL, TOKEN, hub  # noqa: F401  (hub is a fixture)
from test_voice import WORDS, chat, piece, voice  # noqa: F401  (voice is a fixture)

pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="KREWD_TEST_DATABASE_URL is not set")


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
def live(voice):  # noqa: F811
    fake = FakeLive()
    voice.settings = type(voice.settings)(**{**voice.settings.__dict__, "live_model": "fake-live"})
    voice.http = httpx.AsyncClient(transport=httpx.MockTransport(fake))
    return fake


def _texts(result):
    return [(s["id"], s["label"], s["text"], s["final"]) for s in result["segments"]]


async def test_live_words_grow_then_turn_final_by_speaker(hub, voice, live):  # noqa: F811
    sid = await chat(hub)
    s = await voice.start_session(sid, live=True)
    assert s["live"] is True and live.opened == [{"reference": True, "model": True}]  # opened at once: the model loads
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
    vid = (await voice.start_session(sid, live=True))["voice_id"]
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
    vid = (await voice.start_session(sid, live=True))["voice_id"]
    WORDS[10] = "Me talking."
    r = await voice.live(sid, vid, 0, piece(10), flush=True)
    assert _texts(r) == [(0, "Others", "Me talking.", True)]
    assert live.opened == [{"reference": False, "model": True}]


async def test_api_live_route_and_mode_checks(hub, voice, live):  # noqa: F811
    sid = await chat(hub)
    app = create_app(hub, TOKEN, lifespan_hub=False)
    auth = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        assert (await client.get("/api/voice", headers=auth)).json()["live"] is True
        pieces_vid = (await client.post(f"/api/sessions/{sid}/voice", json={}, headers=auth)).json()["voice_id"]
        r = await client.post(f"/api/sessions/{sid}/voice/{pieces_vid}/live?seq=0", content=b"", headers=auth)
        assert r.status_code == 409  # not a Live session
        r = await client.post(f"/api/sessions/{sid}/voice", json={"live": True}, headers=auth)
        vid = r.json()["voice_id"]
        assert r.json()["live"] is True and vid != pieces_vid  # switching modes starts a new voice session
        WORDS[5] = "Hello there."
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/live?seq=0&end=true", content=piece(5), headers=auth)
        assert r.status_code == 200 and _texts(r.json()) == [(0, "Others", "Hello there.", True)]
        r = await client.post(f"/api/sessions/{sid}/voice/{vid}/live?seq=1", content=b"\0" * (11 * 16000 * 2),
                              headers=auth)
        assert r.status_code == 413
        await client.delete(f"/api/sessions/{sid}/voice/{vid}", headers=auth)
    assert sid not in voice.sessions
