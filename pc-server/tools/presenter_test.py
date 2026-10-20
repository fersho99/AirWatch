"""Pruebas unitarias del modo presentacion del PC (sin red): perfiles, deteccion, puntero, diapositivas.

Uso (desde pc-server/):  python tools/presenter_test.py
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from airwatch_server.input_driver import ALLOWED_KEYS, DryRunDriver  # noqa: E402
from airwatch_server.overlay import PointerController, RecordingOverlay  # noqa: E402
from airwatch_server.presenter import Presenter  # noqa: E402
from airwatch_server.profiles import ACTIONS, PROFILES, detect  # noqa: E402
from airwatch_server.slides import PowerPointSource, SlideState, SlideTracker, summarize_notes  # noqa: E402

failures = 0


def check(cond: bool, msg: str) -> None:
    global failures
    print(("  OK   " if cond else "  FALLO ") + msg)
    if not cond:
        failures += 1


class RecDriver(DryRunDriver):
    def __init__(self) -> None:
        super().__init__(None)
        self.keys: list[list[str]] = []
        self.screen = (1280, 720)

    def key(self, keys):
        self.keys.append(list(keys))


def test_profiles() -> None:
    print("[1] Perfiles y secuencias de teclas")
    p = PROFILES["powerpoint"]
    check(p.sequence("next") == [("right",)] and p.sequence("prev") == [("left",)], "PowerPoint: siguiente=derecha, anterior=izquierda")
    check(p.sequence("start") == [("f5",)] and p.sequence("start_current") == [("shift", "f5")], "PowerPoint: F5 y Shift+F5")
    check(p.sequence("goto", 12) == [("1",), ("2",), ("enter",)], "PowerPoint: ir a 12 = 1, 2, Enter")
    check(PROFILES["gslides"].sequence("start") == [("ctrl", "f5")], "Google Slides: Ctrl+F5")
    check(PROFILES["acrobat"].sequence("goto", 7) == [("ctrl", "shift", "n"), ("7",), ("enter",)], "Acrobat: Ctrl+Shift+N, 7, Enter")
    check(PROFILES["acrobat"].sequence("black") is None, "Acrobat no tiene pantalla negra -> None")
    check(PROFILES["pdf_browser"].sequence("goto", 3) is None, "PDF en navegador no admite ir a diapositiva")
    check(PROFILES["sumatra"].sequence("black") == [(".",)], "SumatraPDF: pantalla negra con '.'")
    check(p.sequence("goto", 0) is None and p.sequence("goto", 10000) is None and p.sequence("goto") is None, "numero invalido -> None")
    check(p.sequence("explotar") is None, "accion desconocida -> None")
    bad = [(n, c) for n, pr in PROFILES.items() for a in ACTIONS
           for c in (pr.sequence(a, 5) or []) if len(c) > 4]
    check(not bad, "ninguna combinacion tiene mas de 4 teclas")
    # las teclas de los perfiles son estandar: letras, digitos, modificadores, F-teclas, navegacion o . ,
    odd = {k for pr in PROFILES.values() for a in ACTIONS for c in (pr.sequence(a, 5) or []) for k in c} - ALLOWED_KEYS
    check(odd <= {".", ","}, f"teclas fuera de la lista blanca del reloj: {sorted(odd)} (solo puntuacion de pantalla negra)")


def test_detect() -> None:
    print("[2] Deteccion de la aplicacion en primer plano")
    cases = [
        (("powerpnt.exe", "PowerPoint Slide Show - demo.pptx"), "powerpoint"),
        (("soffice.bin", "demo - LibreOffice Impress"), "impress"),
        (("chrome.exe", "Mi charla - Presentaciones de Google - Google Chrome"), "gslides"),
        (("msedge.exe", "Deck - Google Slides"), "gslides"),
        (("chrome.exe", "informe.pdf - Google Chrome"), "pdf_browser"),
        (("chrome.exe", "Noticias"), "generic"),
        (("acrord32.exe", "informe.pdf - Adobe Acrobat Reader"), "acrobat"),
        (("sumatrapdf.exe", "informe.pdf"), "sumatra"),
        (("notepad.exe", "Sin titulo"), "generic"),
        ((None, None), "generic"),
    ]
    for (exe, title), want in cases:
        got = detect(exe, title)
        check(got == want, f"{exe} | {title} -> {got}")


def test_pointer() -> None:
    print("[3] Puntero laser / marcador")
    ov = RecordingOverlay((1000, 500))
    pc = PointerController(ov)
    check((pc.x, pc.y) == (500, 250) and not pc.active, "empieza en el centro y apagado")
    pc.move(50, 50)
    check(ov.events == [], "apagado: mover no dibuja nada")
    check(pc.set_mode("laser") and ov.events[-1] == ("dot", 500, 250), "laser encendido muestra el punto en el centro")
    pc.move(30, -20)
    check((pc.x, pc.y) == (530, 230) and ov.events[-1] == ("dot", 530, 230), "el movimiento mueve el punto")
    check(not any(e[0] == "seg" for e in ov.events), "el laser no dibuja linea")
    pc.set_mode("pen")
    pc.move(10, 0)
    check(("seg", 530, 230, 540, 230) in ov.events, "el marcador dibuja un segmento")
    pc.move(5000, 5000)
    check((pc.x, pc.y) == (999, 499), "el punto se queda dentro de la pantalla")
    pc.set_mode("off")
    check(ov.events[-1] == ("hide",) and not pc.active, "apagar oculta el punto")
    n = len(ov.events)
    pc.set_mode("laser")
    check(ov.events[n] == ("dot", 999, 499), "se reanuda donde se quedo")
    pc.clear()
    check(ov.events[-1] == ("clear",), "borrar tinta")
    check(not pc.set_mode("lazer"), "modo desconocido rechazado")


class FakeShape:
    def __init__(self, ptype, text, placeholder=2):
        self.Type, self.HasTextFrame = ptype, True
        self.PlaceholderFormat = type("P", (), {"Type": placeholder})()
        self.TextFrame = type("F", (), {"TextRange": type("R", (), {"Text": text})()})()


class FakeSlide:
    def __init__(self, index, notes):
        self.SlideIndex = index
        self.NotesPage = type("N", (), {"Shapes": [FakeShape(14, "titulo", placeholder=1), FakeShape(14, notes)]})()


class FakePres:
    def __init__(self, notes):
        self.notes = notes
        self.Slides = type("S", (), {"Count": len(notes), "__call__": lambda s, i: FakeSlide(i, notes[i - 1])})()


class FakeCollection:
    def __init__(self, items): self.items = items
    @property
    def Count(self): return len(self.items)
    def __call__(self, i): return self.items[i - 1]


class FakePPT:
    def __init__(self, notes, pos=None):
        self.pres = FakePres(notes)
        self.pos = pos
        self.SlideShowWindows = FakeCollection(
            [type("W", (), {"View": type("V", (), {"CurrentShowPosition": pos, "Slide": FakeSlide(pos, notes[pos - 1])})(),
                            "Presentation": self.pres})()] if pos else [])
        self.Windows = FakeCollection([object()])
        self.Presentations = FakeCollection([self.pres])
        self.ActivePresentation = self.pres
        self.ActiveWindow = type("AW", (), {"View": type("V", (), {"Slide": FakeSlide(1, notes[0])})()})()


def test_slides() -> None:
    print("[4] Estado de diapositivas")
    check(summarize_notes("  hola\n\n mundo  ") == "hola mundo", "las notas se normalizan")
    long = ("palabra " * 100).strip()
    s = summarize_notes(long)
    check(len(s) <= 262 and s.endswith("…") and not s[:-1].endswith("palab"), f"nota larga cortada en palabra completa ({len(s)} car.)")

    notes = ["Saluda y presenta el tema", "Explica el problema.\nDa un ejemplo.", "Cierre"]
    src = PowerPointSource(app_factory=lambda: FakePPT(notes, pos=2))
    st = src.poll()
    check(st == SlideState(2, 3, "Explica el problema. Da un ejemplo.", True), f"COM falso en presentacion: {st}")
    st = PowerPointSource(app_factory=lambda: FakePPT(notes, pos=None)).poll()
    check(st is not None and st.cur == 1 and st.total == 3 and st.exact, "COM falso en edicion: diapositiva 1 de 3")

    def boom():
        raise RuntimeError("PowerPoint cerrado")
    check(PowerPointSource(app_factory=boom).poll() is None, "PowerPoint cerrado -> None (sin excepcion)")

    tr = SlideTracker(None)
    tr.set_total(10)
    for a, n in (("start", None), ("next", None), ("next", None), ("prev", None)):
        tr.on_action(a, n)
    check(tr.read("generic") == SlideState(2, 10, "", False), "estimacion por conteo: start, +1, +1, -1 -> 2 de 10")
    tr.on_action("goto", 99)
    check(tr.read("generic").cur == 10, "ir a 99 con total 10 se limita a 10")
    tr.on_action("prev", None); tr.on_action("prev", None)
    tr2 = SlideTracker(None)
    tr2.on_action("prev")
    check(tr2.read("generic").cur == 1, "anterior en la primera no baja de 1")
    tr3 = SlideTracker(PowerPointSource(app_factory=lambda: FakePPT(notes, pos=3)))
    st = tr3.read("powerpoint")
    check(st.exact and st.cur == 3, "con perfil PowerPoint usa la lectura exacta")
    check(not tr3.read("gslides").exact, "con otro perfil usa la estimacion")


async def test_presenter() -> None:
    print("[5] Presentador: acciones, perfiles y estado")
    d = RecDriver()
    pr = Presenter(d, RecordingOverlay(), SlideTracker(None), foreground=lambda: ("chrome.exe", "Deck - Google Slides"))
    pr.refresh_detection()
    check(pr.profile.name == "gslides", "autodeteccion: Google Slides")
    ok, why = await pr.run_action("start")
    check(ok and d.keys[-1] == ["ctrl", "f5"], f"iniciar en Google Slides -> Ctrl+F5 ({d.keys[-1:]})")
    ok, _ = await pr.run_action("goto", 14)
    check(ok and d.keys[-3:] == [["1"], ["4"], ["enter"]], "ir a 14 envia 1, 4, Enter")
    check(pr.set_profile("acrobat") and pr.profile.name == "acrobat", "perfil manual: Acrobat")
    pr.detected = "powerpoint"
    check(pr.profile.name == "acrobat", "el perfil manual manda sobre la deteccion")
    n = len(d.keys)
    ok, why = await pr.run_action("black")
    check(not ok and "Acrobat" in why and len(d.keys) == n, f"accion no soportada -> error legible sin enviar teclas ({why})")
    ok, why = await pr.run_action("zzz")
    check(not ok, "accion desconocida rechazada")
    check(pr.set_profile("auto") and pr.profile.name == "powerpoint", "volver a automatico usa lo detectado")
    check(not pr.set_profile("inexistente"), "perfil inexistente rechazado")
    await pr.run_action("next"); await pr.run_action("next")
    msg = pr.slide_msg()
    check(msg["cur"] == 16 and msg["exact"] is False, f"estimacion: ir a 14 y dos 'siguiente' -> 16 ({msg})")
    pr.set_pointer("laser")
    check(pr.pointer.active, "laser activo")
    ov = pr.overlay
    ov.events.clear()
    await pr.run_action("next")
    check(("clear",) in ov.events, "cambiar de diapositiva borra la tinta")
    ov.events.clear()
    await pr.run_action("black")
    check(("clear",) not in ov.events, "pantalla negra no borra la tinta")
    pr.end_session()
    check(not pr.pointer.active, "al cortarse la conexion el laser se apaga")

    # bucle de sondeo: envia perfil + diapositiva una vez y despues solo cambios
    sent: list[dict] = []

    async def bc(o): sent.append(o)

    pr2 = Presenter(RecDriver(), RecordingOverlay(), SlideTracker(None), foreground=lambda: ("powerpnt.exe", "x"))
    task = asyncio.create_task(pr2.run(bc, lambda: True, period=0.05))
    await asyncio.sleep(0.4)
    first = [m["t"] for m in sent]
    check(first[:2] == ["profile", "slide"] and first.count("profile") == 1 and first.count("slide") == 1,
          f"primer envio: perfil y diapositiva una sola vez ({first})")
    await pr2.run_action("next")
    await asyncio.sleep(0.3)
    check([m["t"] for m in sent][-1] == "slide" and sent[-1]["cur"] == 2, "un cambio de diapositiva se envia")
    cnt = len(sent)
    await asyncio.sleep(0.3)
    check(len(sent) == cnt, "sin cambios no se envia nada")
    task.cancel()


def main() -> int:
    test_profiles()
    test_detect()
    test_pointer()
    test_slides()
    asyncio.run(test_presenter())
    print("\nTODO OK" if failures == 0 else f"\n{failures} FALLOS")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
