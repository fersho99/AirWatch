"""Prueba de extremo a extremo del modo presentacion: servidor real (--dry-run) + reloj simulado.

Uso (desde pc-server/):  python tools/e2e_presentation.py
"""
from __future__ import annotations

import asyncio
import json
import os
import signal
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tools.e2e_test import check, wait_port  # noqa: E402
from tools.fake_watch import FakeWatch  # noqa: E402

TCP, UDP = 18865, 18866


async def drain(watch: FakeWatch, until, timeout: float = 3.0) -> list[dict]:
    """Recibe mensajes del servidor hasta que `until(msg)` sea cierto; devuelve todos los recibidos."""
    got: list[dict] = []
    end = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < end:
        try:
            m = await watch.recv_ctrl(timeout=max(0.05, end - asyncio.get_running_loop().time()))
        except asyncio.TimeoutError:
            break
        got.append(m)
        if until(m):
            break
    return got


async def scenario(tmp: Path) -> None:
    watch = FakeWatch("127.0.0.1", TCP, tmp / "watch.json")
    _, ok, why = await watch.pair()
    check(ok, f"emparejado ({why})")
    info = await watch.connect()
    check(info["t"] == "info", "sesion cifrada abierta")

    print("[1] Al conectar, la PC envia perfil y estado de diapositivas")
    msgs = await drain(watch, lambda m: m["t"] == "slide")
    kinds = [m["t"] for m in msgs]
    check(kinds[:2] == ["profile", "slide"], f"mensajes iniciales: {kinds}")
    check(msgs[0]["name"] == "generic" and msgs[0]["auto"] is True, f"perfil inicial automatico/generico: {msgs[0]}")

    print("[2] Acciones de presentacion segun el perfil")
    await watch.send_ctrl({"t": "pres", "a": "start"})                      # generico: F5
    await watch.send_ctrl({"t": "profile", "name": "gslides"})
    await watch.send_ctrl({"t": "pres", "a": "start"})                      # Google Slides: Ctrl+F5
    await watch.send_ctrl({"t": "pres", "a": "next"})
    await watch.send_ctrl({"t": "pres", "a": "next"})
    await watch.send_ctrl({"t": "pres", "a": "prev"})
    await watch.send_ctrl({"t": "pres", "a": "goto", "n": 12})
    await watch.send_ctrl({"t": "profile", "name": "acrobat"})
    await watch.send_ctrl({"t": "pres", "a": "black"})                      # no existe en Acrobat -> error
    msgs = await drain(watch, lambda m: m["t"] == "pres_ack")
    ack = [m for m in msgs if m["t"] == "pres_ack"]
    check(len(ack) == 1 and ack[0]["a"] == "black" and not ack[0]["ok"] and "Acrobat" in ack[0]["why"],
          f"accion no soportada -> pres_ack con motivo: {ack}")
    await watch.send_ctrl({"t": "pres", "a": "goto", "n": "abc"})            # malformado
    await watch.send_ctrl({"t": "pres", "a": "goto", "n": 99999})            # fuera de rango
    await watch.send_ctrl({"t": "pres", "a": "rm -rf"})                      # accion inventada
    await watch.send_ctrl({"t": "profile", "name": "../etc/passwd"})         # perfil inexistente
    await watch.send_ctrl({"t": "profile", "name": "auto"})

    print("[3] Laser y marcador: el mouse real no se mueve")
    # UDP (movimiento) y WebSocket (ptr) no tienen orden entre si: el primer paquete tras el mensaje podria
    # adelantarse. En uso real son pocos pixeles; aqui se espera para que la prueba sea determinista.
    await watch.send_ctrl({"t": "ptr", "m": "laser"})
    await asyncio.sleep(0.1)
    for _ in range(20):
        watch.send_move(10, 5)
        await asyncio.sleep(0.01)
    await watch.send_ctrl({"t": "ptr", "m": "pen"})
    await asyncio.sleep(0.1)
    for _ in range(10):
        watch.send_move(4, 0)
        await asyncio.sleep(0.01)
    await watch.send_ctrl({"t": "ptr", "m": "clear"})
    await watch.send_ctrl({"t": "ptr", "m": "bogus"})                        # ignorado
    await watch.send_ctrl({"t": "ptr", "m": "off"})
    await asyncio.sleep(0.1)
    for _ in range(5):
        watch.send_move(1, 1)                                                # vuelve a mover el mouse real
        await asyncio.sleep(0.01)
    await asyncio.sleep(0.3)

    print("[4] Si se corta la conexion con el laser encendido, se apaga")
    await watch.send_ctrl({"t": "ptr", "m": "laser"})
    await asyncio.sleep(0.2)
    await watch.close()
    await asyncio.sleep(0.4)


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
        keys = [e["k"] for e in events if e["ev"] == "key"]
        expected = [["f5"], ["ctrl", "f5"], ["right"], ["right"], ["left"], ["1"], ["2"], ["enter"]]
        check(keys == expected, f"teclas enviadas: {keys}")
        moves = [e for e in events if e["ev"] == "move"]
        check(len(moves) == 5 and all(m["dx"] == 1 and m["dy"] == 1 for m in moves),
              f"solo los 5 movimientos posteriores al laser tocaron el mouse real ({[(m['dx'], m['dy']) for m in moves]})")
        all_ovl = [e for e in events if e["ev"] == "ovl"]
        check(all_ovl[0]["op"] == "clear", "cambiar de diapositiva borra la tinta (siguiente / anterior / ir a)")
        ovl = all_ovl[5:]        # el paso [2] genera 5 borrados automaticos (start x2, next x2, prev, goto)
        ops = [e["op"] for e in ovl]
        check(ops[0] in ("clear", "dot"), "eventos del overlay tras las acciones")
        first_dot = next(e for e in ovl if e["op"] == "dot")
        check((first_dot["x"], first_dot["y"]) == (960, 540), "el laser aparece en el centro de la pantalla")
        dots = [e for e in ovl if e["op"] == "dot"]
        check((dots[20]["x"], dots[20]["y"]) == (960 + 200, 540 + 100), f"20 movimientos de (10,5) -> punto en (1160, 640): {(dots[20]['x'], dots[20]['y'])}")
        segs = [e for e in ovl if e["op"] == "seg"]
        check(len(segs) == 10 and segs[0]["a"] == [1160, 640] and segs[-1]["b"] == [1200, 640], "el marcador dibujo 10 segmentos hasta x=1200")
        last_seg = max(i for i, e in enumerate(ovl) if e["op"] == "seg")
        check(any(e["op"] == "clear" for e in ovl[last_seg:]), "el mensaje 'clear' borra la tinta")
        check(ops.count("hide") >= 2, "se oculta el punto al apagar y al desconectar")
        check(ops[-1] == "hide", "al desconectar con el laser encendido, el ultimo evento oculta el punto")
        print("\nTODO OK")
        return 0


if __name__ == "__main__":
    sys.exit(main())
