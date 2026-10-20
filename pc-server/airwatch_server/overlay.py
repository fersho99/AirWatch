"""Puntero laser y marcador: controlador de posicion + superposicion transparente (opcional)."""
from __future__ import annotations

from typing import Protocol


class Overlay(Protocol):
    screen: tuple[int, int] | None

    def dot(self, x: int, y: int) -> None: ...
    def hide_dot(self) -> None: ...
    def segment(self, x0: int, y0: int, x1: int, y1: int) -> None: ...
    def clear_ink(self) -> None: ...
    def close(self) -> None: ...


class NullOverlay:
    screen = None

    def dot(self, x, y): pass
    def hide_dot(self): pass
    def segment(self, x0, y0, x1, y1): pass
    def clear_ink(self): pass
    def close(self): pass


class RecordingOverlay:
    """Para pruebas: guarda lo que se dibujaria."""

    def __init__(self, screen: tuple[int, int] | None = (1920, 1080)) -> None:
        self.screen = screen
        self.events: list[tuple] = []

    def dot(self, x, y): self.events.append(("dot", x, y))
    def hide_dot(self): self.events.append(("hide",))
    def segment(self, x0, y0, x1, y1): self.events.append(("seg", x0, y0, x1, y1))
    def clear_ink(self): self.events.append(("clear",))
    def close(self): self.events.append(("close",))


class LogOverlay:
    """Modo --dry-run: en vez de dibujar, anota lo que se dibujaria en el mismo registro del driver de prueba."""

    def __init__(self, emit, screen: tuple[int, int] = (1920, 1080)) -> None:
        self._emit, self.screen = emit, screen

    def dot(self, x, y): self._emit(ev="ovl", op="dot", x=x, y=y)
    def hide_dot(self): self._emit(ev="ovl", op="hide")
    def segment(self, x0, y0, x1, y1): self._emit(ev="ovl", op="seg", a=[x0, y0], b=[x1, y1])
    def clear_ink(self): self._emit(ev="ovl", op="clear")
    def close(self): pass


MODES = ("off", "laser", "pen")


class PointerController:
    """Estado del puntero: mientras esta activo, el movimiento del reloj NO mueve el mouse real sino este punto.

    laser = punto rojo que sigue la mano.  pen = ademas deja una linea (marcador) hasta que se borra.
    La posicion se conserva entre activaciones (empieza en el centro de la pantalla).
    """

    def __init__(self, overlay: Overlay, screen: tuple[int, int] = (1920, 1080)) -> None:
        self.overlay = overlay
        w, h = overlay.screen or screen
        self.w, self.h = max(1, w), max(1, h)
        self.x, self.y = self.w // 2, self.h // 2
        self.mode = "off"

    @property
    def active(self) -> bool:
        return self.mode != "off"

    def set_mode(self, mode: str) -> bool:
        if mode not in MODES:
            return False
        if mode == self.mode:
            return True
        self.mode = mode
        if mode == "off":
            self.overlay.hide_dot()
        else:
            self.overlay.dot(self.x, self.y)
        return True

    def move(self, dx: int, dy: int) -> None:
        if not self.active:
            return
        nx = min(self.w - 1, max(0, self.x + int(dx)))
        ny = min(self.h - 1, max(0, self.y + int(dy)))
        if self.mode == "pen" and (nx, ny) != (self.x, self.y):
            self.overlay.segment(self.x, self.y, nx, ny)
        self.x, self.y = nx, ny
        self.overlay.dot(nx, ny)

    def clear(self) -> None:
        self.overlay.clear_ink()

    def close(self) -> None:
        self.mode = "off"
        self.overlay.close()
