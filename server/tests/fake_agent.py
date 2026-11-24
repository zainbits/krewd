"""A tiny ACP agent for tests. Prompt words steer it: perm, slow, hold, pause, crash. Supports `_session/steering`
(a steer that says "nosteer" is answered as if the turn had just ended)."""

import asyncio
import re
import json
import os
import sys
import uuid

pending: dict[int, asyncio.Future] = {}
cancelled: set[str] = set()
next_id = 1000
# Every set_config_option call, so tests can see what reached the agent: (session, id, value).
config_calls: list[tuple[str, str, str]] = []
config: dict[str, dict[str, str]] = {}
deleted: set[str] = set()
# Messages steered into a running prompt, per session.
running: dict[str, list[str]] = {}
# Slash commands this fake advertises after session/new (FAKE_COMMANDS="compact,review").
COMMANDS = [c for c in os.environ.get("FAKE_COMMANDS", "").split(",") if c]


async def advertise(sid: str) -> None:
    await asyncio.sleep(0.05)  # after the session/new answer, as real agents do
    update(sid, {"sessionUpdate": "available_commands_update",
                 "availableCommands": [{"name": c, "description": c} for c in COMMANDS]})


# On a gateway profile (Krewd gives it the gateway's key) the agent has that gateway's models, as real agents do.
MODELS = ["gw-small", "gw-big"] if os.environ.get("ANTHROPIC_AUTH_TOKEN") or os.environ.get("MODEL_PROVIDER") \
    else ["small", "big"]


def config_options(sid: str) -> list[dict]:
    values = config.setdefault(sid, {"model": MODELS[0], "effort": "default"})
    return [
        {"id": "model", "name": "Model", "category": "model", "type": "select", "currentValue": values["model"],
         "options": [{"value": m, "name": m.title()} for m in MODELS]},
        {"id": "effort", "name": "Effort", "category": "thought_level", "type": "select",
         "currentValue": values["effort"],
         "options": [{"group": "levels", "name": "Levels", "options": [
             {"value": "default", "name": "Default"}, {"value": "high", "name": "High"}]}]},
    ]


def send(message: dict) -> None:
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def update(session_id: str, body: dict) -> None:
    send({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": session_id, "update": body}})


async def ask(method: str, params: dict) -> dict:
    global next_id
    next_id += 1
    future = asyncio.get_running_loop().create_future()
    pending[next_id] = future
    send({"jsonrpc": "2.0", "id": next_id, "method": method, "params": params})
    return await future


async def prompt(request_id: int, params: dict) -> None:
    sid = params["sessionId"]
    text = " ".join(block.get("text", "") for block in params["prompt"])
    # Krewd tells a new agent session its chat id first; tests look at what follows.
    text = re.sub(r"\s*\(This is Krewd chat [0-9a-f-]+\.\)$", "", text)
    running[sid] = []
    try:
        await run_prompt(request_id, sid, text, params)
    finally:
        running.pop(sid, None)


async def run_prompt(request_id: int, sid: str, text: str, params: dict) -> None:
    if "crash" in text:
        sys.exit(1)
    if text.startswith("env "):  # tests read what environment an engine was started with
        value = os.environ.get(text.split()[1], "<unset>")
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "e1", "content": {"type": "text", "text": value}})
        send({"jsonrpc": "2.0", "id": request_id, "result": {"stopReason": "end_turn"}})
        return
    if text == "/compact":
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "c1",
                     "content": {"type": "text", "text": "Compacted."}})
        send({"jsonrpc": "2.0", "id": request_id, "result": {"stopReason": "end_turn"}})
        return
    if text.startswith("Krewd is compacting this chat"):
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "h1",
                     "content": {"type": "text", "text": "## Handover\nGoal: timesheets. Code MK-42."}})
        send({"jsonrpc": "2.0", "id": request_id, "result": {"stopReason": "end_turn"}})
        return
    update(sid, {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "thinking"}})
    update(sid, {"sessionUpdate": "session_info_update", "title": "Fake title"})
    if "perm" in text:
        update(sid, {"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "Terminal", "kind": "execute", "status": "pending"})
        update(sid, {"sessionUpdate": "tool_call_update", "toolCallId": "t1", "title": "echo hi", "rawInput": {"command": "echo hi"}})
        reply = await ask(
            "session/request_permission",
            {
                "sessionId": sid,
                "toolCall": {"toolCallId": "t1", "title": "echo hi", "kind": "execute"},
                "options": [
                    {"optionId": "allow", "name": "Allow", "kind": "allow_once"},
                    {"optionId": "always", "name": "Always allow", "kind": "allow_always"},
                    {"optionId": "reject", "name": "Reject", "kind": "reject_once"},
                ],
            },
        )
        outcome = reply.get("outcome", {})
        allowed = outcome.get("outcome") == "selected" and outcome.get("optionId") in ("allow", "always")
        update(
            sid,
            {
                "sessionUpdate": "tool_call_update",
                "toolCallId": "t1",
                "status": "completed" if allowed else "failed",
                "content": [{"type": "content", "content": {"type": "text", "text": "hi" if allowed else "denied"}}],
            },
        )
    if "screenshot" in text:  # a Read of an image file: base64 in raw output (Claude) and in content (ACP)
        png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        update(sid, {"sessionUpdate": "tool_call", "toolCallId": "r1", "title": "Read /tmp/shot.png", "kind": "read",
                     "status": "pending", "rawInput": {"file_path": "/tmp/shot.png"}})
        update(sid, {"sessionUpdate": "tool_call_update", "toolCallId": "r1", "status": "completed",
                     "rawOutput": [{"type": "image", "source": {"type": "base64", "data": png, "media_type": "image/png"}}]})
        update(sid, {"sessionUpdate": "tool_call_update", "toolCallId": "r1",
                     "content": [{"type": "content", "content": {"type": "image", "data": png, "mimeType": "image/png"}}]})
    if "slow" in text:
        for _ in range(200):
            if sid in cancelled:
                cancelled.discard(sid)
                send({"jsonrpc": "2.0", "id": request_id, "result": {"stopReason": "cancelled"}})
                return
            await asyncio.sleep(0.05)
    if "hold" in text:  # a turn that runs until it is stopped (at most 10 minutes), for live checks
        for _ in range(12000):
            if sid in cancelled:
                cancelled.discard(sid)
                send({"jsonrpc": "2.0", "id": request_id, "result": {"stopReason": "cancelled"}})
                return
            await asyncio.sleep(0.05)
    if "pause" in text:  # long enough for a test to act during the turn
        await asyncio.sleep(0.8)
    for chunk in ("Hello ", "world"):
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m1", "content": {"type": "text", "text": chunk}})
        await asyncio.sleep(0.01)
    model = config.get(sid, {}).get("model", MODELS[0])
    update(sid, {"sessionUpdate": "usage_update", "used": 10, "size": 100, "_meta": {"_fake/model": f"fake-{model}-1"}})
    links = [b.get("name") for b in params["prompt"] if b.get("type") == "resource_link"]
    if links:
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m3",
                     "content": {"type": "text", "text": " links=" + ",".join(links)}})
    if running.get(sid):
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m4",
                     "content": {"type": "text", "text": " steered=" + "|".join(running[sid])}})
    if text.startswith("hand "):  # reply with the paths that follow, the way an agent names a file it made
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m1",
                     "content": {"type": "text", "text": " Saved it at `" + text.split(" ", 1)[1] + "`."}})
    if "Handover from the earlier part" in text and "MK-42" in text:
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m5",
                     "content": {"type": "text", "text": " carried=MK-42"}})
    if "The owner left notes on parts of your earlier replies" in text:  # margin notes reached the prompt
        quotes = [line[2:] for line in text.splitlines() if line.startswith("> ")]
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m8",
                     "content": {"type": "text", "text": " noted=" + "|".join(quotes)}})
    if "Krewd voice input is on in this chat" in text:  # the voice rules came with this prompt
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m9",
                     "content": {"type": "text", "text": " voice-rules"}})
    if "moves between agents" in text:
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m7",
                     "content": {"type": "text", "text": " briefed"}})
    if "Scheduled run of the Krewd routine" in text or "Run started by the owner" in text:
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m6",
                     "content": {"type": "text", "text": " routine-ran"}})
    if "calls" in text:
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": "m2",
                     "content": {"type": "text", "text": " calls=" + ",".join(f"{c[1]}:{c[2]}" for c in config_calls if c[0] == sid)}})
    send({"jsonrpc": "2.0", "id": request_id, "result": {"stopReason": "end_turn"}})


async def main() -> None:
    reader = asyncio.StreamReader()
    await asyncio.get_running_loop().connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    tasks = set()
    while line := await reader.readline():
        message = json.loads(line)
        method = message.get("method")
        if method is None:
            future = pending.pop(message["id"], None)
            if future:
                future.set_result(message.get("result") or {})
            continue
        params = message.get("params") or {}
        rid = message.get("id")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": 1,
                "agentCapabilities": {"loadSession": True, "sessionCapabilities": {"resume": {}, "delete": {}}},
                "agentInfo": {"name": "fake-agent", "title": "Fake Agent", "version": "9.9.9"},
                "_meta": {"steering": {"supported": True}},
            }})
        elif method == "session/new":
            new_sid = str(uuid.uuid4())
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "sessionId": new_sid,
                "configOptions": config_options(new_sid),
                "modes": {"currentModeId": "default", "availableModes": [
                    {"id": "default", "name": "Manual"}, {"id": "auto", "name": "Auto"}]},
            }})
            if COMMANDS:
                task = asyncio.ensure_future(advertise(new_sid))
                tasks.add(task)
                task.add_done_callback(tasks.discard)
        elif method == "session/set_config_option":
            sid, key, value = params["sessionId"], params["configId"], params["value"]
            config_calls.append((sid, key, value))
            config_options(sid)
            config[sid][key] = value
            send({"jsonrpc": "2.0", "id": rid, "result": {"configOptions": config_options(sid)}})
        elif method == "session/delete":
            deleted.add(params["sessionId"])
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif method in ("session/resume", "session/load"):
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif method == "session/set_mode":
            update(params["sessionId"], {"sessionUpdate": "current_mode_update", "currentModeId": params["modeId"]})
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif method == "session/prompt":
            task = asyncio.ensure_future(prompt(rid, params))
            tasks.add(task)
            task.add_done_callback(tasks.discard)
        elif method == "_session/steering":
            sid = params["sessionId"]
            said = " ".join(b.get("text", "") for b in params["prompt"])
            if sid in running and "nosteer" not in said:  # "nosteer": answer as if the turn had just ended
                running[sid].append(said)
                send({"jsonrpc": "2.0", "id": rid, "result": {"outcome": "injected"}})
            else:
                send({"jsonrpc": "2.0", "id": rid, "result": {"outcome": "promptRequired", "reason": "noRunningTurn"}})
        elif method == "session/cancel":
            cancelled.add(params["sessionId"])
        elif rid is not None:
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})


asyncio.run(main())
