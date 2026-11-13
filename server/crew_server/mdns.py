"""Announce Crew's HTTPS address on the local network (mDNS / DNS-SD, `_crew._tcp`).

The app looks for it when none of its saved addresses answers, for example after a router change gave the
server a new address. Finding it proves nothing: the app still refuses any certificate but the pinned one.
"""

from __future__ import annotations

import logging
import socket

log = logging.getLogger("crew")

SERVICE = "_crew._tcp.local."


class Announcer:
    def __init__(self, addresses: list[str], port: int, version: str, pin: str) -> None:
        self.addresses = [a for a in addresses if ":" not in a]  # IPv4 only; the app connects over IPv4
        self.port = port
        self.version = version
        self.pin = pin
        self._zc = None
        self._info = None

    async def start(self) -> None:
        if not self.addresses:
            return
        try:
            from zeroconf import IPVersion, ServiceInfo
            from zeroconf.asyncio import AsyncZeroconf

            host = socket.gethostname().split(".")[0] or "crew"
            self._info = ServiceInfo(
                SERVICE,
                f"Crew on {host}.{SERVICE}",
                addresses=[socket.inet_aton(a) for a in self.addresses],
                port=self.port,
                # "id" lets the app tell its own server from another Crew on the same network before connecting.
                properties={"v": self.version, "id": self.pin.removeprefix("sha256/")[:12]},
                # Not "<host>.local.": the desktop's own mDNS daemon already answers for that name.
                server=f"crew-{host}.local.",
            )
            self._zc = AsyncZeroconf(interfaces=self.addresses, ip_version=IPVersion.V4Only)
            await self._zc.async_register_service(self._info, allow_name_change=True)
            log.info("announcing %s on %s", SERVICE, ", ".join(self.addresses))
        except Exception as exc:  # noqa: BLE001 - discovery is a convenience; never stop the server for it
            log.warning("mDNS announcement failed: %s", exc)
            await self.close()

    async def close(self) -> None:
        if self._zc is None:
            return
        try:
            if self._info is not None:
                await self._zc.async_unregister_service(self._info)
            await self._zc.async_close()
        except Exception:  # noqa: BLE001
            pass
        self._zc = None
