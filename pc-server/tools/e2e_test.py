"""Prueba de extremo a extremo: servidor real (modo --dry-run) + reloj simulado.

Uso (desde pc-server/):  python tools/e2e_test.py
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import signal
import socket
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tools.fake_watch import FakeWatch  # noqa: E402

TCP, UDP = 18765, 18766


def wait_port(port: int, timeout: float = 10.0) -> None:
    end = time.time() + timeout
    while time.time() < end:
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return
        time.sleep(0.1)
    raise TimeoutError(f"puerto {port} no abrio")


def check(cond: bool, msg: str) -> None:
    print(("  OK   " if cond else "  FALLO ") + msg)
    if not cond:
        raise SystemExit(1)


async def scenario(tmp: Path) -> None:
    watch = FakeWatch("127.0.0.1", TCP, tmp / "watch.json")

    print("[1] Emparejamiento")
    sas, ok, why = await watch.pair()
    check(ok, f"emparejado (SAS reloj={sas}, motivo={why})")
    server_log = (tmp / "server.log").read_text(encoding="utf-8")
    m = re.search(r"Codigo de verificacion: (\d{6})", server_log)
    check(bool(m) and m.group(1) == sas, f"el SAS de la PC coincide con el del reloj ({m.group(1) if m else '?'})")
    sas2, ok2, why2 = await FakeWatch("127.0.0.1", TCP, tmp / "w2.json").pair()
    check(not ok2 and why2 == "window_closed", f"segundo emparejamiento rechazado (ventana de un solo uso): {why2}")

    print("[2] Conexion cifrada")
    info = await watch.connect()
    check(info["t"] == "info", f"trama info cifrada recibida: {info}")
    try:
        await FakeWatch("127.0.0.1", TCP, tmp / "watch.json").connect(dev_override="deadbeefdeadbeef")
        check(False, "dispositivo desconocido debia rechazarse")
    except ConnectionError as exc:
        check(str(exc) == "unpaired", "dispositivo no emparejado rechazado")

    print("[3] Movimiento UDP, botones, scroll")
    n = 300
    for _ in range(n):
        watch.send_move(3, -2)
        await asyncio.sleep(0.01)  # ~100 Hz
    await watch.send_ctrl({"t": "btn", "b": "left", "s": "down"})
    await watch.send_ctrl({"t": "btn", "b": "left", "s": "up"})
    await watch.send_ctrl({"t": "scroll", "h": 0, "v": -1})
    await watch.send_ctrl({"t": "recenter"})

    print("[3b] Etapa 2: ajuste atomico con el clic, doble clic, atras, teclas, mensajes invalidos")
    await watch.send_ctrl({"t": "btn", "b": "left", "s": "click", "adj": [-4, 3]})
    await watch.send_ctrl({"t": "btn", "b": "left", "s": "double"})
    await watch.send_ctrl({"t": "btn", "b": "back", "s": "click"})
    await watch.send_ctrl({"t": "key", "k": ["alt", "tab"]})
    await watch.send_ctrl({"t": "key", "k": ["format", "c"]})            # tecla fuera de la lista blanca
    await watch.send_ctrl({"t": "key", "k": ["a", "a"]})                 # repetida
    await watch.send_ctrl({"t": "key", "k": ["ctrl", "alt", "shift", "win", "x"]})  # demasiadas
    await watch.send_ctrl({"t": "btn", "b": "bogus", "s": "click"})      # boton inexistente
    await watch.send_ctrl({"t": "scroll", "h": "x", "v": None})          # malformado: no debe tumbar la sesion
    await watch.send_ctrl({"t": "btn", "b": "right", "s": "click", "adj": "no-es-lista"})
    await watch.send_ctrl({"t": "btn", "b": "left", "s": "down"})        # queda retenido: al desconectar se libera

    print("[4] Anti-replay y manipulacion")
    frame = watch.send_move(100, 100)           # legitimo (cuenta una vez)
    await asyncio.sleep(0.05)
    watch.send_raw_udp(frame)                   # replay exacto -> descartado
    tampered = bytearray(watch._seal(b"\x01" + b"\x00\x64\x00\x64"))
    tampered[-1] ^= 0x01                        # un bit alterado -> tag invalido
    watch.send_raw_udp(bytes(tampered))
    watch.send_raw_udp(os.urandom(48))          # basura
    await asyncio.sleep(0.2)

    print("[5] Latencia UDP (loopback, solo mide el protocolo + cifrado)")
    rtts = [await watch.ping_udp() for _ in range(50)]
    print(f"      RTT mediana={statistics.median(rtts):.2f} ms  p95={sorted(rtts)[47]:.2f} ms  max={max(rtts):.2f} ms")
    check(statistics.median(rtts) < 5, "mediana < 5 ms en loopback")

    await watch.close()
    await asyncio.sleep(0.2)


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        env = {**os.environ, "AIRWATCH_HOME": str(tmp / "cfg"), "PYTHONPATH": str(ROOT)}
        log_f = open(tmp / "server.log", "w", encoding="utf-8")
        srv = subprocess.Popen(
            [sys.executable, "-m", "airwatch_server", "--dry-run", "--dry-run-log", str(tmp / "events.jsonl"),
             "--pair", "--no-mdns", "--unsafe-auto-confirm", "--port", str(TCP), "--udp-port", str(UDP)],
            env=env, stdout=log_f, stderr=subprocess.STDOUT, cwd=ROOT,
            creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
        )
        try:
            wait_port(TCP)
            asyncio.run(scenario(tmp))
        finally:
            srv.send_signal(getattr(signal, "CTRL_BREAK_EVENT", signal.SIGINT))
            try:
                srv.wait(timeout=5)
            except subprocess.TimeoutExpired:
                srv.kill()
            log_f.close()

        events = [json.loads(l) for l in (tmp / "events.jsonl").read_text().splitlines()]
        summary = events[-1]
        print("[6] Resumen del servidor:", {k: summary[k] for k in ("moves", "dx", "dy")})
        check(summary["moves"] == 302, "302 movimientos (300 + 1 legitimo + 1 ajuste; replay/manipulados/basura descartados)")
        check(summary["dx"] == 300 * 3 + 100 - 4 and summary["dy"] == 300 * -2 + 100 + 3, "suma dx/dy exacta (incluye el ajuste)")
        kinds = [(e["ev"], e.get("b"), e.get("s")) for e in events if e["ev"] not in ("move", "summary")]
        expected = [
            ("button", "left", "down"), ("button", "left", "up"), ("scroll", None, None), ("recenter", None, None),
            ("button", "left", "click"), ("button", "left", "double"), ("button", "back", "click"),
            ("key", None, None),
            ("button", "right", "click"),     # el 'adj' invalido se ignora pero el clic valido se ejecuta
            ("button", "left", "down"),
            ("button", "left", "up"),         # liberado por el servidor al desconectar
        ]
        check(kinds == expected, f"eventos de control en orden: {kinds}")
        keys = [e["k"] for e in events if e["ev"] == "key"]
        check(keys == [["alt", "tab"]], f"solo la combinacion valida llego al teclado: {keys}")
        i = next(n for n, e in enumerate(events) if e["ev"] == "button" and e["s"] == "click" and e["b"] == "left")
        check(events[i - 1] == {**events[i - 1], "ev": "move", "dx": -4, "dy": 3},
              "el ajuste (-4,3) se aplica inmediatamente ANTES del clic")
        print("\nTODO OK")
        return 0


if __name__ == "__main__":
    sys.exit(main())
