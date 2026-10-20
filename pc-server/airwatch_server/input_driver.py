"""Ejecucion de eventos de mouse/teclado en la PC (pynput) y un driver de prueba."""
from __future__ import annotations

import json
import sys
import time
from typing import Protocol

MAX_STEP = 2000  # px maximos por paquete (sanidad)
MAX_COMBO = 4    # teclas maximas por combinacion

BUTTONS = {"left", "right", "middle", "back", "forward"}
BUTTON_STATES = {"down", "up", "click", "double"}

# Lista blanca de teclas aceptadas desde el reloj (nombres en minusculas).
_MODIFIERS = {"ctrl", "alt", "shift", "win"}
_NAMED = {"tab", "esc", "enter", "space", "left", "right", "up", "down",
          "page_up", "page_down", "home", "end"} | {f"f{i}" for i in range(1, 13)}
_CHARS = set("abcdefghijklmnopqrstuvwxyz0123456789")
ALLOWED_KEYS = _MODIFIERS | _NAMED | _CHARS


def parse_keys(raw) -> list[str] | None:
    """Valida una combinacion enviada por el reloj. Devuelve la lista normalizada o None."""
    if not isinstance(raw, list) or not 1 <= len(raw) <= MAX_COMBO:
        return None
    keys: list[str] = []
    for k in raw:
        if not isinstance(k, str):
            return None
        k = k.strip().lower()
        if k not in ALLOWED_KEYS or k in keys:
            return None
        keys.append(k)
    return keys


class Driver(Protocol):
    def move(self, dx: int, dy: int) -> None: ...
    def button(self, name: str, state: str) -> None: ...
    def scroll(self, h: int, v: int) -> None: ...
    def key(self, keys: list[str]) -> None: ...
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
        from pynput.keyboard import Controller as KbController
        from pynput.keyboard import Key
        from pynput.mouse import Button, Controller

        self._mouse = Controller()
        self._kb = KbController()
        # Atras/adelante: x1/x2 en Windows, button8/button9 en X11. Si no existen, se ignoran
        # (nunca se convierten en clic izquierdo).
        self._buttons = {
            "left": Button.left,
            "right": Button.right,
            "middle": Button.middle,
            "back": getattr(Button, "x1", None) or getattr(Button, "button8", None),
            "forward": getattr(Button, "x2", None) or getattr(Button, "button9", None),
        }
        self._keys = {
            "ctrl": Key.ctrl, "alt": Key.alt, "shift": Key.shift, "win": Key.cmd,
            "tab": Key.tab, "esc": Key.esc, "enter": Key.enter, "space": Key.space,
            "left": Key.left, "right": Key.right, "up": Key.up, "down": Key.down,
            "page_up": Key.page_up, "page_down": Key.page_down, "home": Key.home, "end": Key.end,
        }
        for i in range(1, 13):
            self._keys[f"f{i}"] = getattr(Key, f"f{i}")
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

    def key(self, keys: list[str]) -> None:
        objs = [self._keys.get(k, k) for k in keys]  # letras/digitos se pasan como caracter
        for o in objs:
            self._kb.press(o)
        for o in reversed(objs):
            self._kb.release(o)

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

    def key(self, keys: list[str]) -> None:
        self._emit(ev="key", k=keys)

    def recenter(self) -> None:
        self._emit(ev="recenter")

    def close(self) -> None:
        self._emit(ev="summary", moves=self.moves, dx=self.total_dx, dy=self.total_dy)
        if self._log:
            self._log.close()
