"""Servidor AirWatch: WebSocket (control, emparejamiento) + UDP (movimiento), todo cifrado."""
from __future__ import annotations

import asyncio
import hmac
import json
import logging
import os
import struct
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from . import crypto
from .crypto import ReplayWindow, b64d, b64e
from .input_driver import BUTTON_STATES, BUTTONS, Driver, parse_keys
from .netutil import MdnsAdvertiser, local_ipv4_addresses
from .presenter import Presenter
from .store import DeviceStore

log = logging.getLogger("airwatch")

# Tipos de paquete UDP (primer byte del plaintext)
UDP_MOVE = 1   # int16 dx, int16 dy (big-endian)
UDP_PING = 2   # int64 sello de tiempo del reloj (se devuelve tal cual)
UDP_PONG = 3

PAIR_WINDOW_S = 120


class Session:
    def __init__(self, dev_id: str, name: str, key: bytes, sid: int, ws) -> None:
        self.dev_id, self.name, self.sid, self.ws = dev_id, name, sid, ws
        self.aead = AESGCM(key)
        self.tx_sid = sid ^ crypto.SERVER_SID_MASK
        self.tx_ctr = 0
        self.replay = ReplayWindow()
        self.udp_addr = None
        self.last_seen = time.monotonic()
        self.held: set[str] = set()   # botones del mouse que el reloj dejo presionados

    def seal(self, plaintext: bytes) -> bytes:
        self.tx_ctr += 1
        return crypto.seal(self.aead, self.tx_sid, self.tx_ctr, plaintext)

    def open(self, frame: bytes) -> bytes | None:
        sid, ctr, plain = crypto.open_frame(self.aead, frame)
        if plain is None or sid != self.sid:
            return None
        if not self.replay.check_and_update(ctr):
            return None
        self.last_seen = time.monotonic()
        return plain

    async def send_json(self, obj: dict) -> None:
        await self.ws.send(self.seal(json.dumps(obj, separators=(",", ":")).encode()))


class UdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, app: "AirWatchServer") -> None:
        self.app = app
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport) -> None:
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:
        if len(data) < crypto.HEADER_LEN + crypto.TAG_LEN + 1:
            return
        sid = int.from_bytes(data[:4], "big")
        sess = self.app.sessions.get(sid)
        if sess is None:
            return
        plain = sess.open(data)
        if plain is None:
            return
        sess.udp_addr = addr
        kind = plain[0]
        if kind == UDP_MOVE and len(plain) >= 5:
            dx, dy = struct.unpack(">hh", plain[1:5])
            pointer = self.app.presenter.pointer
            if pointer.active:
                pointer.move(dx, dy)         # laser / marcador: el mouse real no se mueve
            else:
                self.app.driver.move(dx, dy)
        elif kind == UDP_PING and len(plain) >= 9 and self.transport:
            self.transport.sendto(sess.seal(bytes([UDP_PONG]) + plain[1:9]), addr)


class AirWatchServer:
    def __init__(
        self,
        driver: Driver,
        store: DeviceStore,
        host: str = "0.0.0.0",
        port: int = 8765,
        udp_port: int = 8766,
        name: str = "AirWatch-PC",
        mdns: bool = True,
        auto_confirm: bool = False,
        presenter: Presenter | None = None,
    ) -> None:
        self.driver, self.store = driver, store
        self.presenter = presenter or Presenter(driver, foreground=None)
        self.host, self.port, self.udp_port = host, port, udp_port
        self.name, self.use_mdns, self.auto_confirm = name, mdns, auto_confirm
        self.sessions: dict[int, Session] = {}
        self.pair_until = 0.0
        self._pair_lock = asyncio.Lock()
        self._mdns: MdnsAdvertiser | None = None

    # ------------------------------------------------------------------ ciclo de vida
    def open_pairing_window(self, seconds: int = PAIR_WINDOW_S) -> None:
        self.pair_until = time.monotonic() + seconds

    async def run(self) -> None:
        from websockets.asyncio.server import serve

        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_datagram_endpoint(
            lambda: UdpProtocol(self), local_addr=(self.host, self.udp_port)
        )
        addrs = local_ipv4_addresses()
        if self.use_mdns:
            self._mdns = MdnsAdvertiser(self.name, self.port, self.udp_port, addrs)
            await self._mdns.start()
        poll_task = asyncio.create_task(self.presenter.run(self._broadcast, lambda: bool(self.sessions)))
        try:
            async with serve(self._handler, self.host, self.port, max_size=64 * 1024):
                log.info("WebSocket en :%d, UDP en :%d", self.port, self.udp_port)
                for a in addrs:
                    log.info("  alcanzable en %s", a)
                if not self.store.devices and time.monotonic() > self.pair_until:
                    log.info("No hay relojes emparejados. Reinicia con --pair para emparejar.")
                await asyncio.Future()
        finally:
            poll_task.cancel()
            self.presenter.pointer.close()
            transport.close()
            if self._mdns:
                await self._mdns.stop()
            self.driver.close()

    # ------------------------------------------------------------------ WebSocket
    async def _handler(self, ws) -> None:
        sess: Session | None = None
        try:
            async for msg in ws:
                if sess is None:
                    if isinstance(msg, (bytes, bytearray)):
                        return
                    m = json.loads(msg)
                    kind = m.get("t")
                    if kind == "pair_req":
                        await self._do_pair(ws, m)
                        return
                    if kind == "hello":
                        sess = await self._do_hello(ws, m)
                        if sess is None:
                            return
                        continue
                    return
                if isinstance(msg, str):
                    continue
                plain = sess.open(bytes(msg))
                if plain is None:
                    continue
                try:
                    await self._handle_ctrl(sess, json.loads(plain))
                except (ValueError, TypeError, KeyError, AttributeError) as exc:
                    log.debug("mensaje de control ignorado: %s", exc)
        except Exception as exc:
            log.debug("conexion cerrada: %s", exc)
        finally:
            if sess is not None:
                if self.sessions.get(sess.sid) is sess:
                    del self.sessions[sess.sid]
                for name in sorted(sess.held):   # nunca dejar un boton "pegado" si se corta la conexion
                    self.driver.button(name, "up")
                sess.held.clear()
                if not self.sessions:
                    self.presenter.end_session()
                log.info("Desconectado: %s", sess.name)

    async def _do_hello(self, ws, m: dict) -> Session | None:
        dev = self.store.get(str(m.get("dev", "")))
        if dev is None:
            await ws.send(json.dumps({"t": "hello_err", "why": "unpaired"}))
            log.warning("Conexion de dispositivo no emparejado: %s", m.get("dev"))
            return None
        cn = b64d(m["cn"])
        if len(cn) != 16:
            return None
        sn = os.urandom(16)
        key, sid = crypto.session_keys(dev.secret, cn, sn)
        proof = crypto.server_proof(dev.secret, cn, sn)
        for old in [s for s in self.sessions.values() if s.dev_id == dev.dev_id]:
            self.sessions.pop(old.sid, None)
            await old.ws.close()
        sess = Session(dev.dev_id, dev.name, key, sid, ws)
        self.sessions[sid] = sess
        await ws.send(json.dumps({"t": "hello_ok", "sn": b64e(sn), "proof": b64e(proof), "udp": self.udp_port}))
        w, h = getattr(self.driver, "screen", (0, 0))
        await sess.send_json({"t": "info", "name": self.name, "screen": [w, h]})
        self.presenter.refresh_detection()
        await sess.send_json(self.presenter.profile_msg())
        await sess.send_json(self.presenter.slide_msg())
        log.info("Conectado: %s", dev.name)
        return sess

    async def _broadcast(self, obj: dict) -> None:
        for s in list(self.sessions.values()):
            try:
                await s.send_json(obj)
            except Exception as exc:
                log.debug("no se pudo enviar a %s: %s", s.name, exc)

    def _apply_adj(self, m: dict) -> None:
        """Compensacion de posicion que el reloj adjunta a un clic (se aplica ANTES del clic)."""
        adj = m.get("adj")
        if isinstance(adj, list) and len(adj) == 2:
            dx, dy = int(adj[0]), int(adj[1])
            if dx or dy:
                self.driver.move(dx, dy)

    async def _handle_ctrl(self, s: Session, m: dict) -> None:
        kind = m.get("t")
        if kind == "btn":
            name, state = str(m.get("b")), str(m.get("s"))
            if name not in BUTTONS or state not in BUTTON_STATES:
                return
            self._apply_adj(m)
            self.driver.button(name, state)
            if state == "down":
                s.held.add(name)
            else:
                s.held.discard(name)
        elif kind == "scroll":
            self.driver.scroll(int(m.get("h", 0)), int(m.get("v", 0)))
        elif kind == "key":
            keys = parse_keys(m.get("k"))
            if keys:
                self.driver.key(keys)
            else:
                log.debug("combinacion de teclas rechazada: %r", m.get("k"))
        elif kind == "recenter":
            self.driver.recenter()
        elif kind == "ping":
            await s.send_json({"t": "pong", "n": m.get("n")})
        elif kind == "pres":
            action = str(m.get("a"))
            n = m.get("n")
            ok, why = await self.presenter.run_action(action, int(n) if isinstance(n, (int, float)) else None)
            if not ok:
                await s.send_json({"t": "pres_ack", "a": action, "ok": False, "why": why})
            else:
                self.presenter.force_resend()
        elif kind == "ptr":
            self.presenter.set_pointer(str(m.get("m")))
        elif kind == "profile":
            if self.presenter.set_profile(str(m.get("name"))):
                self.presenter.force_resend()
        elif kind == "slides_total":
            n = m.get("n")
            self.presenter.tracker.set_total(int(n) if isinstance(n, (int, float)) else None)
            self.presenter.force_resend()

    # ------------------------------------------------------------------ emparejamiento
    async def _do_pair(self, ws, m: dict) -> None:
        async with self._pair_lock:
            if time.monotonic() > self.pair_until:
                await ws.send(json.dumps({"t": "pair_err", "why": "window_closed"}))
                log.warning("Solicitud de emparejamiento rechazada (ventana cerrada; usa --pair)")
                return
            watch_pub = b64d(m["pub"])
            name = str(m.get("name", "reloj"))[:40]
            priv = crypto.new_keypair()
            server_pub = crypto.public_bytes(priv)
            try:
                shared = crypto.ecdh(priv, watch_pub)
            except Exception:
                await ws.send(json.dumps({"t": "pair_err", "why": "bad_key"}))
                return
            secret, sas = crypto.pairing_material(shared, watch_pub, server_pub)
            await ws.send(json.dumps({"t": "pair_resp", "pub": b64e(server_pub)}))
            print(f"\n=== EMPAREJAMIENTO con '{name}' ===\n    Codigo de verificacion: {sas}\n"
                  f"    Debe ser IDENTICO al que muestra el reloj. Confirma primero en el reloj.\n", flush=True)
            try:
                c = json.loads(await asyncio.wait_for(ws.recv(), PAIR_WINDOW_S))
            except Exception:
                return
            expected = crypto.hmac_sha256(secret, b"confirm-watch")
            if c.get("t") != "pair_confirm" or not hmac.compare_digest(b64d(c.get("mac", "")), expected):
                await ws.send(json.dumps({"t": "pair_err", "why": "rejected_by_watch"}))
                log.warning("El reloj rechazo el codigo de verificacion")
                return
            ok = self.auto_confirm or await self._ask_user(sas)
            if not ok:
                await ws.send(json.dumps({"t": "pair_err", "why": "rejected_by_pc"}))
                return
            self.store.add(crypto.device_id(watch_pub), name, secret)
            self.pair_until = 0.0
            mac = crypto.hmac_sha256(secret, b"confirm-server")
            await ws.send(json.dumps({"t": "pair_ok", "mac": b64e(mac)}))
            log.info("Emparejado: %s (%s)", name, crypto.device_id(watch_pub))

    async def _ask_user(self, sas: str) -> bool:
        loop = asyncio.get_running_loop()
        answer = await loop.run_in_executor(None, input, f"¿El codigo {sas} coincide en el reloj? [s/N]: ")
        return answer.strip().lower() in ("s", "si", "sí", "y", "yes")
