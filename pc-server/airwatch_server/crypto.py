"""Primitivas criptograficas del protocolo AirWatch (v1).

Resumen del diseno
------------------
Emparejamiento (una sola vez, con comparacion numerica estilo Bluetooth):
  1. Reloj y PC intercambian claves publicas ECDH P-256 (efimeras) por WebSocket.
  2. Ambos derivan: secreto de emparejamiento (32 B) y un codigo SAS de 6 digitos.
  3. El usuario compara el SAS en la pantalla del reloj y en la consola de la PC.
     Si un atacante se interpuso, los SAS no coinciden.
  4. Ambos guardan el secreto de emparejamiento.

Cada conexion posterior:
  - El reloj envia un nonce de cliente (cn) y su id de dispositivo.
  - La PC responde con un nonce de servidor (sn) y una prueba HMAC (autentica a la PC).
  - Ambos derivan clave de sesion AES-256-GCM y un id de sesion (sid) de 32 bits.
  - Todo el trafico posterior (WebSocket y UDP) va en tramas AEAD:
        trama = sid(4) || ctr(8) || AES-GCM(ciphertext || tag)
    con nonce = sid||ctr y AAD = sid||ctr.  Reloj->PC usa `sid`; PC->reloj usa `sid ^ 0xFFFFFFFF`
    para que nunca se repita un nonce entre direcciones.
  - Proteccion anti-replay con ventana deslizante sobre `ctr`.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import struct

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

SERVER_SID_MASK = 0xFFFFFFFF
HEADER_LEN = 12
TAG_LEN = 16


def b64e(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def b64d(text: str) -> bytes:
    return base64.b64decode(text.encode("ascii"))


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


# --------------------------------------------------------------------------- ECDH

def new_keypair() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def public_bytes(priv: ec.EllipticCurvePrivateKey) -> bytes:
    """Punto sin comprimir (65 bytes: 0x04 || X || Y)."""
    return priv.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)


def ecdh(priv: ec.EllipticCurvePrivateKey, peer_pub: bytes) -> bytes:
    peer = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer_pub)
    return priv.exchange(ec.ECDH(), peer)


def pairing_material(shared: bytes, watch_pub: bytes, server_pub: bytes) -> tuple[bytes, str]:
    """Devuelve (secreto_de_emparejamiento, SAS de 6 digitos)."""
    salt = hashlib.sha256(watch_pub + server_pub).digest()
    secret = hkdf(shared, salt, b"airwatch pairing secret")
    sas = int.from_bytes(hkdf(shared, salt, b"airwatch sas", 4), "big") % 1_000_000
    return secret, f"{sas:06d}"


def device_id(watch_pub: bytes) -> str:
    return hashlib.sha256(watch_pub).digest()[:8].hex()


# --------------------------------------------------------------------------- Sesion

def session_keys(secret: bytes, cn: bytes, sn: bytes) -> tuple[bytes, int]:
    salt = cn + sn
    key = hkdf(secret, salt, b"airwatch session key")
    sid = int.from_bytes(hkdf(secret, salt, b"airwatch session id", 4), "big")
    return key, sid


def server_proof(secret: bytes, cn: bytes, sn: bytes) -> bytes:
    return hmac_sha256(secret, b"srv" + cn + sn)


def seal(aead: AESGCM, sid: int, ctr: int, plaintext: bytes) -> bytes:
    header = struct.pack(">IQ", sid, ctr)
    return header + aead.encrypt(header, plaintext, header)


def open_frame(aead: AESGCM, frame: bytes) -> tuple[int, int, bytes | None]:
    """Devuelve (sid, ctr, plaintext|None). None si la autenticacion falla."""
    if len(frame) < HEADER_LEN + TAG_LEN:
        return 0, 0, None
    header = frame[:HEADER_LEN]
    sid, ctr = struct.unpack(">IQ", header)
    try:
        return sid, ctr, aead.decrypt(header, frame[HEADER_LEN:], header)
    except Exception:
        return sid, ctr, None


class ReplayWindow:
    """Ventana anti-replay deslizante (como IPsec). Admite reordenamiento limitado."""

    def __init__(self, size: int = 128) -> None:
        self.size = size
        self.mask = (1 << size) - 1
        self.top = -1
        self.bits = 0

    def check_and_update(self, n: int) -> bool:
        if n > self.top:
            if self.top < 0:
                self.bits = 1
            else:
                shift = n - self.top
                self.bits = ((self.bits << shift) | 1) & self.mask
            self.top = n
            return True
        offset = self.top - n
        if offset >= self.size or (self.bits >> offset) & 1:
            return False
        self.bits |= 1 << offset
        return True
