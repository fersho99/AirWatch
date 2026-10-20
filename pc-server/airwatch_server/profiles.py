"""Perfiles por aplicacion de presentacion: que teclas hace falta enviar para cada accion del reloj.

El reloj envia acciones (siguiente, anterior, iniciar...) y es el PC quien las traduce a teclas segun la
aplicacion que tiene el foco. Las tablas son fijas (no vienen del reloj), asi que no pasan por la lista
blanca de `parse_keys`; aun asi solo usan teclas estandar.

Fuentes de los atajos (verificados en la documentacion de cada aplicacion):
  PowerPoint, Google Slides, LibreOffice Impress, SumatraPDF, Acrobat. Keynote queda como experimental.
"""
from __future__ import annotations

from dataclasses import dataclass

Combo = tuple[str, ...]

ACTIONS = ("next", "prev", "start", "start_current", "end", "black", "white", "goto")


@dataclass(frozen=True)
class Profile:
    name: str
    label: str
    next: Combo | None = ("right",)
    prev: Combo | None = ("left",)
    start: Combo | None = ("f5",)
    start_current: Combo | None = ("shift", "f5")
    end: Combo | None = ("esc",)
    black: Combo | None = ("b",)
    white: Combo | None = ("w",)
    goto: bool = True                    # False = la aplicacion no admite "ir a la diapositiva N"
    goto_prefix: tuple[Combo, ...] = ()  # teclas antes de escribir el numero (p. ej. abrir un dialogo)
    note: str = ""

    def supports(self, action: str) -> bool:
        if action == "goto":
            return self.goto
        return getattr(self, action, None) is not None

    def sequence(self, action: str, n: int | None = None) -> list[Combo] | None:
        """Lista de combinaciones a pulsar en orden, o None si la aplicacion no soporta la accion."""
        if action not in ACTIONS or not self.supports(action):
            return None
        if action != "goto":
            return [getattr(self, action)]
        if n is None or not 1 <= n <= 9999:
            return None
        return [*self.goto_prefix, *[(d,) for d in str(n)], ("enter",)]


PROFILES: dict[str, Profile] = {p.name: p for p in (
    Profile("generic", "Genérico"),
    Profile("powerpoint", "PowerPoint"),
    Profile("gslides", "Google Slides", start=("ctrl", "f5"), start_current=("ctrl", "f5"),
            note="Google solo documenta Ctrl+F5 para presentar"),
    Profile("impress", "LibreOffice Impress"),
    Profile("acrobat", "Acrobat / Reader", next=("page_down",), prev=("page_up",),
            start=("ctrl", "l"), start_current=("ctrl", "l"), black=None, white=None,
            goto_prefix=(("ctrl", "shift", "n"),), note="Sin pantalla negra/blanca"),
    Profile("sumatra", "SumatraPDF", start=("f5",), start_current=("f5",), black=(".",), white=("w",),
            goto_prefix=(("ctrl", "g"),)),
    Profile("pdf_browser", "PDF en navegador", next=("page_down",), prev=("page_up",),
            start=("f11",), start_current=("f11",), black=None, white=None, goto=False,
            note="Pantalla completa del navegador; sin ir a diapositiva"),
    Profile("keynote", "Keynote (experimental)", start=("alt", "win", "p"), start_current=("alt", "win", "p"),
            black=None, white=None, goto=False, note="Sin verificar; en Mac, win = Comando"),
)}

AUTO = "auto"

_BROWSERS = ("chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe")
_SLIDES_TITLES = ("google slides", "presentaciones de google", "google presentaciones", "google diapositivas",
                  "diapositivas de google")


def detect(exe: str | None, title: str | None) -> str:
    """Perfil que corresponde a la aplicacion en primer plano (exe en minusculas, titulo de la ventana)."""
    exe = (exe or "").lower()
    title = (title or "").lower()
    if exe == "powerpnt.exe":
        return "powerpoint"
    if exe in ("soffice.bin", "soffice.exe", "simpress.exe"):
        return "impress"
    if exe.startswith("sumatrapdf"):
        return "sumatra"
    if exe in ("acrobat.exe", "acrord32.exe", "acrord64.exe", "acrobatreader.exe"):
        return "acrobat"
    if exe in ("keynote", "keynote.app"):
        return "keynote"
    if exe in _BROWSERS:
        if any(t in title for t in _SLIDES_TITLES):
            return "gslides"
        if ".pdf" in title:
            return "pdf_browser"
    return "generic"
