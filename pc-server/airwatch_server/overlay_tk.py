"""Superposicion transparente para el laser y el marcador (Tkinter + estilos Win32 para que sea click-through).

Corre su propio hilo con su propio bucle de Tk; el resto del servidor solo encola ordenes.
Limitaciones: solo el monitor principal; una ventana en pantalla completa "exclusiva" puede quedar por encima.
"""
from __future__ import annotations

import queue
import sys
import threading

DOT_R = 11
DOT_COLOR = "#ff2020"
INK_COLOR = "#ffd400"
INK_WIDTH = 6
KEY_COLOR = "#010101"      # color que se vuelve transparente


class TkOverlay:
    def __init__(self) -> None:
        self.screen: tuple[int, int] | None = None
        self._q: queue.SimpleQueue = queue.SimpleQueue()
        self._ready = threading.Event()
        self._error: Exception | None = None
        self._thread = threading.Thread(target=self._run, name="airwatch-overlay", daemon=True)
        self._thread.start()
        if not self._ready.wait(5) or self._error:
            raise RuntimeError(f"no se pudo crear el overlay: {self._error or 'tiempo agotado'}")

    # ------------------------------------------------------------------ API (hilo del servidor)
    def dot(self, x: int, y: int) -> None: self._q.put(("dot", x, y))
    def hide_dot(self) -> None: self._q.put(("hide",))
    def segment(self, x0: int, y0: int, x1: int, y1: int) -> None: self._q.put(("seg", x0, y0, x1, y1))
    def clear_ink(self) -> None: self._q.put(("clear",))
    def close(self) -> None: self._q.put(("close",))

    # ------------------------------------------------------------------ hilo de Tk
    def _run(self) -> None:
        try:
            import tkinter as tk

            root = tk.Tk()
            root.overrideredirect(True)
            root.attributes("-topmost", True)
            w, h = root.winfo_screenwidth(), root.winfo_screenheight()
            root.geometry(f"{w}x{h}+0+0")
            root.configure(bg=KEY_COLOR)
            canvas = tk.Canvas(root, width=w, height=h, bg=KEY_COLOR, highlightthickness=0)
            canvas.pack()
            if sys.platform == "win32":
                root.attributes("-transparentcolor", KEY_COLOR)
                root.update_idletasks()
                self._make_click_through(root)
            self.screen = (w, h)
        except Exception as exc:
            self._error = exc
            self._ready.set()
            return
        self._ready.set()

        dot_id = canvas.create_oval(-50, -50, -50, -50, fill=DOT_COLOR, outline="white", width=2, state="hidden")
        tick = {"n": 0}

        def pump() -> None:
            try:
                while True:
                    cmd = self._q.get_nowait()
                    if cmd[0] == "dot":
                        _, x, y = cmd
                        canvas.coords(dot_id, x - DOT_R, y - DOT_R, x + DOT_R, y + DOT_R)
                        canvas.itemconfigure(dot_id, state="normal")
                        canvas.tag_raise(dot_id)
                    elif cmd[0] == "hide":
                        canvas.itemconfigure(dot_id, state="hidden")
                    elif cmd[0] == "seg":
                        _, x0, y0, x1, y1 = cmd
                        canvas.create_line(x0, y0, x1, y1, fill=INK_COLOR, width=INK_WIDTH,
                                           capstyle="round", joinstyle="round")
                        canvas.tag_raise(dot_id)
                    elif cmd[0] == "clear":
                        for item in canvas.find_all():
                            if item != dot_id:
                                canvas.delete(item)
                    elif cmd[0] == "close":
                        root.destroy()
                        return
            except queue.Empty:
                pass
            tick["n"] += 1
            if tick["n"] % 120 == 0:        # ~1 s: una ventana de presentacion puede haberse puesto encima
                root.attributes("-topmost", True)
                root.lift()
            root.after(8, pump)

        root.after(8, pump)
        root.mainloop()

    @staticmethod
    def _make_click_through(root) -> None:
        import ctypes

        GWL_EXSTYLE = -20
        WS_EX_LAYERED, WS_EX_TRANSPARENT = 0x80000, 0x20
        WS_EX_TOOLWINDOW, WS_EX_NOACTIVATE = 0x80, 0x08000000
        user32 = ctypes.windll.user32
        hwnd = user32.GetParent(root.winfo_id()) or root.winfo_id()
        style = user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
        user32.SetWindowLongW(hwnd, GWL_EXSTYLE,
                              style | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE)
