"""Modo presentacion del lado del PC: perfiles por aplicacion, puntero laser/marcador y estado de diapositivas."""
from __future__ import annotations

import asyncio
import logging
from typing import Awaitable, Callable

from . import fgapp
from .input_driver import Driver
from .overlay import NullOverlay, Overlay, PointerController
from .profiles import ACTIONS, AUTO, PROFILES, Profile, detect
from .slides import SlideState, SlideTracker

log = logging.getLogger("airwatch.presenter")

Broadcast = Callable[[dict], Awaitable[None]]
CLEARS_INK = {"next", "prev", "goto", "start", "start_current", "end"}
KEY_GAP_S = 0.03     # pausa entre teclas de una secuencia (p. ej. los digitos de "ir a la diapositiva")


class Presenter:
    def __init__(
        self,
        driver: Driver,
        overlay: Overlay | None = None,
        tracker: SlideTracker | None = None,
        foreground: Callable[[], tuple[str, str] | None] | None = fgapp.foreground,
    ) -> None:
        self.driver = driver
        self.overlay = overlay or NullOverlay()
        self.pointer = PointerController(self.overlay, getattr(driver, "screen", None) or (1920, 1080))
        self.tracker = tracker or SlideTracker()
        self._foreground = foreground
        self.override: str | None = None          # None = automatico
        self.detected = "generic"
        self._last_sent: tuple | None = None

    # ------------------------------------------------------------------ perfil
    @property
    def profile(self) -> Profile:
        return PROFILES[self.override or self.detected]

    def set_profile(self, name: str) -> bool:
        if name == AUTO:
            self.override = None
            return True
        if name in PROFILES:
            self.override = name
            return True
        return False

    def profile_msg(self) -> dict:
        p = self.profile
        return {"t": "profile", "name": p.name, "label": p.label, "auto": self.override is None, "note": p.note}

    def refresh_detection(self) -> None:
        if self._foreground is None:
            return
        fg = self._foreground()
        if fg is not None:
            self.detected = detect(*fg)

    # ------------------------------------------------------------------ acciones
    async def run_action(self, action: str, n: int | None = None) -> tuple[bool, str]:
        """Ejecuta una accion de presentacion. Devuelve (ok, motivo si fallo)."""
        if action not in ACTIONS:
            return False, "accion desconocida"
        seq = self.profile.sequence(action, n)
        if seq is None:
            if action == "goto" and (n is None or not 1 <= n <= 9999):
                return False, "numero de diapositiva invalido"
            return False, f"no disponible en {self.profile.label}"
        for i, combo in enumerate(seq):
            self.driver.key(list(combo))
            if i < len(seq) - 1:
                await asyncio.sleep(KEY_GAP_S)
        self.tracker.on_action(action, n)
        if action in CLEARS_INK:
            self.pointer.clear()           # la tinta pertenece a la diapositiva en la que se dibujo
        return True, ""

    def set_pointer(self, mode: str) -> bool:
        if mode == "clear":
            self.pointer.clear()
            return True
        return self.pointer.set_mode(mode)

    def end_session(self) -> None:
        """Se corto la conexion: nunca dejar el laser encendido."""
        self.pointer.set_mode("off")

    # ------------------------------------------------------------------ estado hacia el reloj
    def slide_msg(self, st: SlideState | None = None) -> dict:
        st = st or self.tracker.read(self.profile.name)
        return {"t": "slide", "cur": st.cur, "total": st.total, "notes": st.notes, "exact": st.exact}

    async def run(self, broadcast: Broadcast, has_sessions: Callable[[], bool], period: float = 0.5) -> None:
        """Bucle de sondeo: detecta la app en primer plano y lee la diapositiva; avisa solo si algo cambio."""
        loop = asyncio.get_running_loop()
        ticks = 0
        while True:
            await asyncio.sleep(period)
            if not has_sessions():
                self._last_sent = None
                continue
            try:
                ticks += 1
                if ticks % max(1, int(1 / period)) == 0:
                    self.refresh_detection()
                prof = self.profile_msg()
                st = await loop.run_in_executor(None, self.tracker.read, self.profile.name)
                slide = self.slide_msg(st)
                sig = (prof["name"], prof["auto"], slide["cur"], slide["total"], slide["notes"], slide["exact"])
                if sig != self._last_sent:
                    if self._last_sent is None or self._last_sent[:2] != sig[:2]:
                        await broadcast(prof)
                    await broadcast(slide)
                    self._last_sent = sig
            except Exception as exc:       # nunca matar el bucle
                log.debug("sondeo de presentacion fallido: %s", exc)

    def force_resend(self) -> None:
        self._last_sent = None
