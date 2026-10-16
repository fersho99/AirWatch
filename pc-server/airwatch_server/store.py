"""Almacen de dispositivos emparejados (JSON en el directorio de configuracion)."""
from __future__ import annotations

import json
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from .crypto import b64d, b64e


def config_dir() -> Path:
    override = os.environ.get("AIRWATCH_HOME")
    if override:
        base = Path(override)
    elif sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home())) / "AirWatch"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "airwatch"
    base.mkdir(parents=True, exist_ok=True)
    return base


@dataclass
class Device:
    dev_id: str
    name: str
    secret: bytes
    added: float


class DeviceStore:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path or (config_dir() / "paired.json")
        self.devices: dict[str, Device] = {}
        self.load()

    def load(self) -> None:
        if not self.path.exists():
            return
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        for dev_id, d in raw.get("devices", {}).items():
            self.devices[dev_id] = Device(dev_id, d["name"], b64d(d["secret"]), d.get("added", 0.0))

    def save(self) -> None:
        data = {
            "devices": {
                d.dev_id: {"name": d.name, "secret": b64e(d.secret), "added": d.added}
                for d in self.devices.values()
            }
        }
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, self.path)

    def get(self, dev_id: str) -> Device | None:
        return self.devices.get(dev_id)

    def add(self, dev_id: str, name: str, secret: bytes) -> None:
        self.devices[dev_id] = Device(dev_id, name, secret, time.time())
        self.save()

    def remove(self, dev_id: str) -> bool:
        if self.devices.pop(dev_id, None) is None:
            return False
        self.save()
        return True
