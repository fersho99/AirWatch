"""Reloj simulado: implementa el lado cliente del protocolo para probar el servidor sin hardware.

Es tambien la referencia ejecutable de lo que debe hacer WifiLink.kt en el reloj.
"""
from __future__ import annotations

import asyncio
import json
import os
import struct
import time
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from airwatch_server import crypto
from airwatch_server.crypto import b64d, b64e
from airwatch_server.server import UDP_MOVE, UDP_PING, UDP_PONG


class _UdpClient(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.transport = None
        self.frames: asyncio.Queue[bytes] = asyncio.Queue()

    def connection_made(self, transport) -> None:
        self.transport = transport

    def datagram_received(self, data, addr) -> None:
        self.frames.put_nowait(data)


class FakeWatch:
    def __init__(self, host: str, port: int, state_file: Path, name: str = "Fake Galaxy Watch") -> None:
        self.host, self.port, self.state_file, self.name = host, port, Path(state_file), name
        self.sid = 0
        self.aead: AESGCM | None = None
        self.ctr = 0
        self.ws = None
        self.udp: _UdpClient | None = None
        self.last_frame = b""
        self.rx_replay = crypto.ReplayWindow()

    # ----------------------------------------------------------------- emparejamiento
    async def pair(self, confirm=lambda sas: True) -> tuple[str, bool, str]:
        """Devuelve (sas, ok, motivo)."""
        from websockets.asyncio.client import connect

        priv = crypto.new_keypair()
        pub = crypto.public_bytes(priv)
        async with connect(f"ws://{self.host}:{self.port}") as ws:
            await ws.send(json.dumps({"t": "pair_req", "pub": b64e(pub), "name": self.name}))
            r = json.loads(await ws.recv())
            if r["t"] != "pair_resp":
                return "", False, r.get("why", "?")
            server_pub = b64d(r["pub"])
            shared = crypto.ecdh(priv, server_pub)
            secret, sas = crypto.pairing_material(shared, pub, server_pub)
            if not confirm(sas):
                return sas, False, "user_rejected"
            await ws.send(json.dumps({"t": "pair_confirm", "mac": b64e(crypto.hmac_sha256(secret, b"confirm-watch"))}))
            r = json.loads(await asyncio.wait_for(ws.recv(), 30))
            if r["t"] != "pair_ok":
                return sas, False, r.get("why", "?")
            if b64d(r["mac"]) != crypto.hmac_sha256(secret, b"confirm-server"):
                return sas, False, "bad_server_mac"
            self.state_file.write_text(json.dumps({"dev": crypto.device_id(pub), "secret": b64e(secret)}))
            return sas, True, "ok"

    # ----------------------------------------------------------------- sesion
    async def connect(self, dev_override: str | None = None) -> dict:
        from websockets.asyncio.client import connect

        st = json.loads(self.state_file.read_text())
        secret = b64d(st["secret"])
        self.ws = await connect(f"ws://{self.host}:{self.port}")
        cn = os.urandom(16)
        await self.ws.send(json.dumps({"t": "hello", "dev": dev_override or st["dev"], "cn": b64e(cn)}))
        r = json.loads(await self.ws.recv())
        if r["t"] != "hello_ok":
            raise ConnectionError(r.get("why", "hello_err"))
        sn = b64d(r["sn"])
        if b64d(r["proof"]) != crypto.server_proof(secret, cn, sn):
            raise ConnectionError("la PC no demostro conocer el secreto")
        key, self.sid = crypto.session_keys(secret, cn, sn)
        self.aead = AESGCM(key)
        info = self._open_server(await self.ws.recv())
        loop = asyncio.get_running_loop()
        self.udp = _UdpClient()
        await loop.create_datagram_endpoint(lambda: self.udp, remote_addr=(self.host, int(r["udp"])))
        return json.loads(info)

    def _seal(self, plaintext: bytes) -> bytes:
        self.ctr += 1
        return crypto.seal(self.aead, self.sid, self.ctr, plaintext)

    def _open_server(self, frame: bytes) -> bytes:
        sid, ctr, plain = crypto.open_frame(self.aead, frame)
        assert plain is not None and sid == self.sid ^ crypto.SERVER_SID_MASK, "trama del servidor invalida"
        assert self.rx_replay.check_and_update(ctr), "replay del servidor"
        return plain

    # ----------------------------------------------------------------- envio
    async def send_ctrl(self, obj: dict) -> None:
        await self.ws.send(self._seal(json.dumps(obj).encode()))

    def send_move(self, dx: int, dy: int) -> bytes:
        frame = self._seal(bytes([UDP_MOVE]) + struct.pack(">hh", dx, dy))
        self.last_frame = frame
        self.udp.transport.sendto(frame)
        return frame

    def send_raw_udp(self, frame: bytes) -> None:
        self.udp.transport.sendto(frame)

    async def ping_udp(self, timeout: float = 1.0) -> float:
        t0 = time.perf_counter_ns()
        self.udp.transport.sendto(self._seal(bytes([UDP_PING]) + struct.pack(">q", t0)))
        frame = await asyncio.wait_for(self.udp.frames.get(), timeout)
        plain = self._open_server(frame)
        assert plain[0] == UDP_PONG and struct.unpack(">q", plain[1:9])[0] == t0
        return (time.perf_counter_ns() - t0) / 1e6

    async def close(self) -> None:
        if self.udp and self.udp.transport:
            self.udp.transport.close()
        if self.ws:
            await self.ws.close()
