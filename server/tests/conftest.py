"""Shared test setup."""

from __future__ import annotations

import pytest

# What puts an agent on a gateway. Agents started by the tests inherit the environment of whoever runs them, and an
# agent Krewd runs on a gateway profile has these set, so tests run from its shell would see a gateway that is not there.
GATEWAY_ENV = ("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "MODEL_PROVIDER", "CODEX_CONFIG")


@pytest.fixture(autouse=True)
def _no_gateway_from_the_caller(monkeypatch):
    for name in GATEWAY_ENV:
        monkeypatch.delenv(name, raising=False)
