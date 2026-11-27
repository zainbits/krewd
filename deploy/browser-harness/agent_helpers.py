"""Krewd's hook for browser-harness: each chat has its own daemon and claims every tab it touches.

browser-harness loads `$BH_AGENT_WORKSPACE/agent_helpers.py` on every call, while `browser_harness.helpers`
is being imported. Krewd starts its agents with BH_AGENT_WORKSPACE set to a folder where this file is
linked (server/krewd_server/harness.py), so it runs only for Krewd's agents. It does nothing in a shell
that is not an agent's (no CLAUDE_CODE_SESSION_ID or CODEX_THREAD_ID).

1. Own daemon: the daemon is named after the agent session (BU_NAME=krewd-<id>), so the tab one chat
   attached to is never moved by another chat's call.
2. Claims: before a call touches a tab (attach, switch, a page command, close, activate), it asks Krewd to
   lock that tab for its chat (POST /api/browser/claim). While the chat's turn runs, the phone only watches
   the tab and other chats are refused it: the call fails with Krewd's message, and the agent opens its own
   tab with new_tab(). If Krewd cannot be reached, the call goes on without a lock.

Names starting with "_" are not copied into the agent's helpers; this file adds no helpers of its own.
"""
import json as _json
import os as _os
import re as _re
import sys as _sys
import urllib.error as _urlerror
import urllib.request as _urlrequest

_AGENT_SESSION = _os.environ.get("CLAUDE_CODE_SESSION_ID") or _os.environ.get("CODEX_THREAD_ID") or ""
# Commands that name no tab, or only a tab they make (claimed when the call attaches to it).
_NO_TAB = {"Target.getTargets", "Target.createTarget", "Target.getTargetInfo", "Target.setDiscoverTargets",
           "Target.setAutoAttach", "Target.getBrowserContexts", "Target.createBrowserContext"}

# switch_tab's first command: remove the horse marker from the tab it leaves (browser_harness.helpers).
_UNMARK = "if(document.title.startsWith('\U0001F434 '))"


def _install():
    helpers = _sys.modules.get("browser_harness.helpers")
    if helpers is None or not _AGENT_SESSION or getattr(helpers, "_krewd_hooked", False):
        return
    helpers._krewd_hooked = True
    _pick_browser()
    _own_daemon(helpers)
    _claim_tabs(helpers)


def _krewd_request(method, path, body=None, timeout=5):
    port = _os.environ.get("KREWD_PORT", "8800")
    token_file = _os.environ.get("KREWD_TOKEN_FILE") or _os.path.expanduser("~/.config/krewd/token")
    with open(token_file) as f:
        token = f.read().strip()
    req = _urlrequest.Request(
        f"http://127.0.0.1:{port}{path}", method=method,
        data=_json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
    )
    return _urlrequest.urlopen(req, timeout=timeout).read()


def _pick_browser():
    from urllib.parse import quote

    try:
        info = _json.loads(_krewd_request("GET", f"/api/browser/session?agent_session={quote(_AGENT_SESSION)}",
                                          timeout=60))
    except _urlerror.HTTPError as e:
        try:
            detail = _json.loads(e.read()).get("detail")
        except Exception:
            detail = None
        if e.code == 503:  # the chat's browser could not start: never use another one instead
            raise SystemExit(f"Krewd: {detail or 'this chat' + chr(39) + 's browser is not running'}")
        print(f"krewd: could not ask which browser this chat uses (HTTP {e.code}); using BU_CDP_URL as set",
              file=_sys.stderr)
        return
    except Exception as e:
        print(f"krewd: could not ask which browser this chat uses ({e}); using BU_CDP_URL as set", file=_sys.stderr)
        return
    url = info.get("cdp_url")
    if url:
        _os.environ["BU_CDP_URL"] = url  # the daemon this call starts inherits it
    if info.get("name") and info["name"] != "main":
        print(f"krewd: this chat uses the browser of {info['name']}", file=_sys.stderr)


def _own_daemon(helpers):
    if _os.environ.get("BU_NAME"):
        return  # the agent picked a daemon on purpose
    name = ("krewd-" + _re.sub(r"[^A-Za-z0-9_-]", "", _AGENT_SESSION))[:64]
    _os.environ["BU_NAME"] = name  # the daemon this call starts inherits it
    helpers.NAME = name
    try:
        helpers.SOCK = helpers.ipc.sock_addr(name)
    except Exception:
        pass
    admin = _sys.modules.get("browser_harness.admin")
    if admin is not None:
        admin.NAME = name


def _claim_tabs(helpers):
    send = helpers._send
    claimed = set()
    current = {}
    warned = []

    def claim(tab_id):
        if not tab_id or tab_id in claimed:
            return
        try:
            _krewd_request("POST", "/api/browser/claim", {"agent_session": _AGENT_SESSION, "tab_id": tab_id})
        except _urlerror.HTTPError as e:
            if e.code == 409:
                try:
                    detail = _json.loads(e.read()).get("detail")
                except Exception:
                    detail = None
                raise RuntimeError(f"Krewd: {detail or 'this tab is in use by another chat'}") from None
            if e.code != 404 and not warned:  # 404: not a Krewd chat's agent session
                warned.append(1)
                print(f"krewd: could not lock tab {tab_id[:8]} for this chat (HTTP {e.code})", file=_sys.stderr)
        except Exception as e:
            if not warned:
                warned.append(1)
                print(f"krewd: could not lock tab {tab_id[:8]} for this chat ({e})", file=_sys.stderr)
        claimed.add(tab_id)

    def current_target():
        if "id" not in current:
            try:
                current["id"] = send({"meta": "current_tab"}).get("targetId")
            except Exception:
                current["id"] = None
        return current["id"]

    def krewd_send(req, *args, **kwargs):
        meta = req.get("meta")
        if meta == "set_session":
            claim(req.get("target_id"))
            result = send(req, *args, **kwargs)
            current["id"] = req.get("target_id")
            return result
        if meta is None:
            method = req.get("method") or ""
            params = req.get("params") or {}
            if method.startswith(("Target.", "Browser.", "SystemInfo.")):
                if params.get("targetId") and method not in _NO_TAB:
                    claim(params["targetId"])  # attach, activate, close
            elif str(params.get("expression", "")).startswith(_UNMARK):
                pass  # switch_tab taking its marker off the tab it leaves: not a use of that tab
            elif not req.get("session_id"):
                claim(current_target())  # a page command on the attached tab
            # A command with its own session_id runs on a target attached above (already claimed).
        return send(req, *args, **kwargs)

    helpers._send = krewd_send


_install()
