"""Aplicacion en primer plano (solo Windows): nombre del ejecutable y titulo de la ventana."""
from __future__ import annotations

import os
import sys


def foreground() -> tuple[str, str] | None:
    """(exe en minusculas, titulo) de la ventana con el foco, o None si no se puede saber."""
    if sys.platform != "win32":
        return None
    try:
        return _foreground_windows()
    except Exception:
        return None


def _foreground_windows() -> tuple[str, str] | None:
    import ctypes
    from ctypes import wintypes

    u32 = ctypes.WinDLL("user32", use_last_error=True)
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    u32.GetForegroundWindow.restype = wintypes.HWND
    u32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    u32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    k32.OpenProcess.restype = wintypes.HANDLE
    k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    k32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR,
                                               ctypes.POINTER(wintypes.DWORD)]
    k32.CloseHandle.argtypes = [wintypes.HANDLE]

    hwnd = u32.GetForegroundWindow()
    if not hwnd:
        return None
    buf = ctypes.create_unicode_buffer(512)
    u32.GetWindowTextW(hwnd, buf, 512)
    title = buf.value
    pid = wintypes.DWORD()
    u32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    exe = ""
    h = k32.OpenProcess(0x1000, False, pid.value)       # PROCESS_QUERY_LIMITED_INFORMATION
    if h:
        try:
            size = wintypes.DWORD(1024)
            path = ctypes.create_unicode_buffer(1024)
            if k32.QueryFullProcessImageNameW(h, 0, path, ctypes.byref(size)):
                exe = os.path.basename(path.value).lower()
        finally:
            k32.CloseHandle(h)
    return exe, title
