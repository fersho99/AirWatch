"""Estado de la presentacion (diapositiva actual, total y notas del orador).

* PowerPoint (Windows): lectura exacta por COM (necesita `pywin32`, opcional).
* Resto de aplicaciones (Google Slides, PDF, Impress...): no exponen la diapositiva actual de forma fiable
  desde fuera, asi que se ESTIMA contando las acciones siguiente/anterior/ir a. La estimacion se marca
  `exact=False` y el reloj la muestra con «~».
"""
from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from typing import Callable

log = logging.getLogger("airwatch.slides")

NOTES_MAX = 260


@dataclass(frozen=True)
class SlideState:
    cur: int | None = None
    total: int | None = None
    notes: str = ""
    exact: bool = False


def summarize_notes(text: str | None, limit: int = NOTES_MAX) -> str:
    """Resumen corto de la nota: espacios normalizados y cortado en el limite (en una palabra completa)."""
    if not text:
        return ""
    t = " ".join(text.replace("\r", "\n").split())
    if len(t) <= limit:
        return t
    cut = t[:limit].rsplit(" ", 1)[0]
    return (cut or t[:limit]) + "…"


class PowerPointSource:
    """Lee la diapositiva actual de PowerPoint por COM. `app_factory` permite inyectar un falso en pruebas."""

    def __init__(self, app_factory: Callable[[], object] | None = None) -> None:
        self._factory = app_factory or self._default_factory
        self._local = threading.local()
        self._warned = False

    @staticmethod
    def _default_factory():
        import win32com.client  # type: ignore[import-not-found]

        return win32com.client.GetActiveObject("PowerPoint.Application")

    def _init_com(self) -> None:
        if getattr(self._local, "ok", False):
            return
        try:
            import pythoncom  # type: ignore[import-not-found]

            pythoncom.CoInitialize()
        except ImportError:
            pass
        self._local.ok = True

    def poll(self) -> SlideState | None:
        """Estado actual, o None si PowerPoint no esta abierto / no hay COM."""
        try:
            self._init_com()
            app = self._factory()
        except Exception as exc:  # PowerPoint cerrado, pywin32 ausente...
            if not self._warned:
                log.info("Sin lectura de PowerPoint (%s). Se usara la estimacion por conteo.", exc)
                self._warned = True
            return None
        try:
            if app.SlideShowWindows.Count > 0:
                view = app.SlideShowWindows(1).View
                pres = app.SlideShowWindows(1).Presentation
                cur = int(view.CurrentShowPosition)
                slide = pres.Slides(int(view.Slide.SlideIndex))
                return SlideState(cur, int(pres.Slides.Count), self._notes(slide), True)
            if app.Windows.Count > 0 and app.Presentations.Count > 0:
                win = app.ActiveWindow
                cur = int(win.View.Slide.SlideIndex)
                pres = app.ActivePresentation
                return SlideState(cur, int(pres.Slides.Count), self._notes(pres.Slides(cur)), True)
        except Exception as exc:
            log.debug("lectura de PowerPoint fallida: %s", exc)
        return None

    @staticmethod
    def _notes(slide) -> str:
        try:
            for shape in slide.NotesPage.Shapes:
                if shape.Type == 14 and shape.PlaceholderFormat.Type == 2 and shape.HasTextFrame:   # cuerpo de notas
                    return summarize_notes(shape.TextFrame.TextRange.Text)
        except Exception:
            pass
        return ""


class SlideTracker:
    """Combina la lectura exacta (si existe) con la estimacion por conteo."""

    def __init__(self, ppt: PowerPointSource | None = None) -> None:
        self.ppt = ppt
        self.est_cur: int | None = None
        self.est_total: int | None = None

    def on_action(self, action: str, n: int | None = None) -> None:
        """Actualiza la estimacion cuando el reloj manda una accion de navegacion."""
        if action == "start":
            self.est_cur = 1
        elif action == "start_current":
            self.est_cur = self.est_cur or 1
        elif action == "next":
            self.est_cur = (self.est_cur or 1) + 1
            if self.est_total:
                self.est_cur = min(self.est_cur, self.est_total)
        elif action == "prev":
            self.est_cur = max(1, (self.est_cur or 2) - 1)
        elif action == "goto" and n:
            self.est_cur = n if not self.est_total else min(n, self.est_total)
        elif action == "end":
            pass

    def set_total(self, total: int | None) -> None:
        """Total conocido por el usuario (no hay forma de leerlo fuera de PowerPoint)."""
        self.est_total = total if total and total > 0 else None

    def read(self, profile_name: str) -> SlideState:
        if profile_name == "powerpoint" and self.ppt is not None:
            exact = self.ppt.poll()
            if exact is not None:
                self.est_cur, self.est_total = exact.cur, exact.total   # la estimacion parte de la lectura real
                return exact
        return SlideState(self.est_cur, self.est_total, "", False)
