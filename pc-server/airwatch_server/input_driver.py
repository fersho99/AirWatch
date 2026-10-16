"""Ejecucion de eventos de mouse/teclado en la PC (pynput) y un driver de prueba."""
from __future__ import annotations

import json
import sys
import time
from typing import Protocol

MAX_STEP = 2000  # px maximos por paquete (sanidad)


class Driver(Protocol):
    def move(self, dx: int, dy: int) -> None: ...
    def button(self, name: str, state: str) -> None: ...
    def scroll(self, h: int, v: int) -> None: ...
    def recenter(self) -> None: ...
    def close(self) -> None: ...


def _clamp(v: int) -> int:
    return max(-MAX_STEP, min(MAX_STEP, int(v)))


def _screen_size() -> tuple[int, int]:
    if sys.platform == "win32":
        import ctypes

        u = ctypes.windll.user32
        return u.GetSystemMetrics(0), u.GetSystemMetrics(1)
    try:
        import tkinter

        root = tkinter.Tk()
        root.withdraw()
        size = root.winfo_screenwidth(), root.winfo_screenheight()
        root.destroy()
        return size
    except Exception:
        return 1920, 1080


class PynputDriver:
    def __init__(self) -> None:
        if sys.platform == "win32":
            import ctypes

            try:  # coordenadas reales en pantallas con escalado
                ctypes.windll.shcore.SetProcessDpiAwareness(2)
            except Exception:
                try:
                    ctypes.windll.user32.SetProcessDPIAware()
                except Exception:
                    pass
        from pynput.mouse import Button, Controller

        self._mouse = Controller()
        self._buttons = {
            "left": Button.left,
            "right": Button.right,
            "middle": Button.middle,
            "back": getattr(Button, "x1", Button.left),
            "forward": getattr(Button, "x2", Button.left),
        }
        self.screen = _screen_size()

    def move(self, dx: int, dy: int) -> None:
        self._mouse.move(_clamp(dx), _clamp(dy))

    def button(self, name: str, state: str) -> None:
        b = self._buttons.get(name)
        if b is None:
            return
        if state == "down":
            self._mouse.press(b)
        elif state == "up":
            self._mouse.release(b)
        elif state == "click":
            self._mouse.click(b)
        elif state == "double":
            self._mouse.click(b, 2)

    def scroll(self, h: int, v: int) -> None:
        self._mouse.scroll(_clamp(h), _clamp(v))

    def recenter(self) -> None:
        w, h = self.screen
        self._mouse.position = (w // 2, h // 2)

    def close(self) -> None:
        pass


class DryRunDriver:
    """No toca el mouse real. Acumula totales y, opcionalmente, registra eventos en JSONL."""

    def __init__(self, log_path: str | None = None) -> None:
        self.total_dx = 0
        self.total_dy = 0
        self.moves = 0
        self._log = open(log_path, "w", encoding="utf-8", buffering=1) if log_path else None

    def _emit(self, **ev) -> None:
        if self._log:
            ev["ts"] = time.time()
            self._log.write(json.dumps(ev) + "\n")

    def move(self, dx: int, dy: int) -> None:
        dx, dy = _clamp(dx), _clamp(dy)
        self.total_dx += dx
        self.total_dy += dy
        self.moves += 1
        self._emit(ev="move", dx=dx, dy=dy)

    def button(self, name: str, state: str) -> None:
        self._emit(ev="button", b=name, s=state)

    def scroll(self, h: int, v: int) -> None:
        self._emit(ev="scroll", h=h, v=v)

    def recenter(self) -> None:
        self._emit(ev="recenter")

    def close(self) -> None:
        self._emit(ev="summary", moves=self.moves, dx=self.total_dx, dy=self.total_dy)
        if self._log:
            self._log.close()
