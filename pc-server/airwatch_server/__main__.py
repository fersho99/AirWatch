"""Punto de entrada: python -m airwatch_server [opciones]"""
from __future__ import annotations

import argparse
import asyncio
import logging
import signal
import socket
import sys

from .input_driver import DryRunDriver
from .server import PAIR_WINDOW_S, AirWatchServer
from .store import DeviceStore, config_dir


def build_presenter(driver, use_overlay: bool, dry_run: bool):
    """Overlay de laser/marcador, lector de PowerPoint y deteccion de la app en primer plano (cada parte es opcional)."""
    from .presenter import Presenter
    from .slides import PowerPointSource, SlideTracker

    overlay = None
    if dry_run and hasattr(driver, "_emit"):
        from .overlay import LogOverlay

        overlay = LogOverlay(driver._emit)
    elif use_overlay:
        try:
            from .overlay_tk import TkOverlay

            overlay = TkOverlay()
        except Exception as exc:
            logging.warning("Laser/marcador desactivados (%s). Usa --no-overlay para ocultar este aviso.", exc)
    ppt = PowerPointSource() if sys.platform == "win32" and not dry_run else None
    foreground = None
    if not dry_run:
        from .fgapp import foreground as fg

        foreground = fg
    return Presenter(driver, overlay, SlideTracker(ppt), foreground)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="airwatch_server", description="Servidor de PC para AirWatch")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8765, help="puerto TCP del WebSocket")
    p.add_argument("--udp-port", type=int, default=8766, help="puerto UDP de movimiento")
    p.add_argument("--name", default=f"AirWatch-{socket.gethostname().split('.')[0]}")
    p.add_argument("--pair", action="store_true", help=f"abrir ventana de emparejamiento ({PAIR_WINDOW_S} s)")
    p.add_argument("--no-mdns", action="store_true", help="no anunciar por mDNS")
    p.add_argument("--list-devices", action="store_true")
    p.add_argument("--forget", metavar="ID", help="olvidar un reloj emparejado")
    p.add_argument("--dry-run", action="store_true", help="no mover el mouse real (pruebas)")
    p.add_argument("--dry-run-log", metavar="ARCHIVO", help="registrar eventos en JSONL (con --dry-run)")
    p.add_argument("--no-overlay", action="store_true", help="sin superposicion de laser/marcador")
    p.add_argument("--unsafe-auto-confirm", action="store_true",
                   help="SOLO PRUEBAS: omite la confirmacion manual del codigo en la PC")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    store = DeviceStore()

    if args.list_devices:
        print(f"Config: {config_dir()}")
        for d in store.devices.values():
            print(f"  {d.dev_id}  {d.name}")
        return 0
    if args.forget:
        print("Olvidado." if store.remove(args.forget) else "No existe ese ID.")
        return 0

    if args.dry_run:
        driver = DryRunDriver(args.dry_run_log)
    else:
        from .input_driver import PynputDriver

        driver = PynputDriver()

    presenter = build_presenter(driver, use_overlay=not (args.no_overlay or args.dry_run), dry_run=args.dry_run)
    server = AirWatchServer(driver, store, args.host, args.port, args.udp_port, args.name,
                            mdns=not args.no_mdns, auto_confirm=args.unsafe_auto_confirm, presenter=presenter)
    if args.pair:
        server.open_pairing_window()
        logging.info("Ventana de emparejamiento abierta %d s. En el reloj: Emparejar.", PAIR_WINDOW_S)
    if hasattr(signal, "SIGBREAK"):  # Windows: Ctrl+Break / CTRL_BREAK_EVENT cierra limpio, como Ctrl+C
        signal.signal(signal.SIGBREAK, signal.default_int_handler)
    try:
        asyncio.run(server.run())
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
