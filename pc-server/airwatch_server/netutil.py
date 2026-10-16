"""Utilidades de red: IPs locales y anuncio mDNS."""
from __future__ import annotations

import logging
import socket

log = logging.getLogger("airwatch.net")

SERVICE_TYPE = "_airwatch._tcp.local."

# Adaptadores virtuales cuya IP no es alcanzable desde el reloj (se omiten del anuncio mDNS).
_VIRTUAL_ADAPTERS = ("virtualbox", "vmware", "vethernet", "hyper-v", "wsl", "host-only", "vmnet", "docker")


def local_ipv4_addresses() -> list[str]:
    """IPv4 privadas de las interfaces activas (sin loopback ni link-local)."""
    out: list[str] = []
    try:
        import ifaddr

        for adapter in ifaddr.get_adapters():
            label = f"{adapter.nice_name} {adapter.name}".lower()
            if any(k in label for k in _VIRTUAL_ADAPTERS):
                continue
            for ip in adapter.ips:
                if ip.is_IPv4 and isinstance(ip.ip, str):
                    a = ip.ip
                    if not a.startswith(("127.", "169.254.")) and a not in out:
                        out.append(a)
    except Exception:
        pass
    if not out:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("10.255.255.255", 1))  # no envia trafico
            out.append(s.getsockname()[0])
            s.close()
        except OSError:
            pass
    return out


class MdnsAdvertiser:
    def __init__(self, name: str, ws_port: int, udp_port: int, addresses: list[str]) -> None:
        self.name, self.ws_port, self.udp_port, self.addresses = name, ws_port, udp_port, addresses
        self._azc = None
        self._info = None

    async def start(self) -> bool:
        try:
            from zeroconf import ServiceInfo
            from zeroconf.asyncio import AsyncZeroconf
        except ImportError:
            log.warning("zeroconf no instalado: sin descubrimiento mDNS")
            return False
        try:
            host = socket.gethostname().split(".")[0] or "airwatch-pc"
            self._info = ServiceInfo(
                SERVICE_TYPE,
                f"{self.name}.{SERVICE_TYPE}",
                addresses=[socket.inet_aton(a) for a in self.addresses],
                port=self.ws_port,
                properties={"udp": str(self.udp_port), "v": "1"},
                server=f"{host}.local.",
            )
            self._azc = AsyncZeroconf()
            await self._azc.async_register_service(self._info)
            log.info("mDNS anunciado como '%s' (%s)", self.name, SERVICE_TYPE)
            return True
        except Exception as exc:  # pragma: no cover
            log.warning("No se pudo anunciar mDNS: %s", exc)
            return False

    async def stop(self) -> None:
        if self._azc and self._info:
            try:
                await self._azc.async_unregister_service(self._info)
                await self._azc.async_close()
            except Exception:
                pass
