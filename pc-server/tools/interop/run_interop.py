import os, subprocess, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
from airwatch_server import crypto
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

here = os.path.dirname(os.path.abspath(__file__))
env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
p = subprocess.Popen(["java", os.path.join(here, "CryptoInterop.java")], stdin=subprocess.PIPE,
                     stdout=subprocess.PIPE, text=True, env=env)
def send(s): p.stdin.write(s + "\n"); p.stdin.flush()
def recv(): return p.stdout.readline().strip()
def ok(c, m):
    print(("OK    " if c else "FALLO ") + m)
    if not c: sys.exit(1)

# 1) HKDF
ikm, salt, info = os.urandom(32), b"airwatch-v1", b"airwatch session id"
send(f"{ikm.hex()} {salt.hex()} {info.hex()}")
ok(recv() == crypto.hkdf(ikm, salt, info, 32).hex(), "HKDF 32 B coincide con Python")
ok(recv() == crypto.hkdf(ikm, salt, info, 4).hex(), "HKDF 4 B coincide con Python")

# 2) Tramas AES-GCM (nonce = cabecera, AAD = cabecera)
key = os.urandom(32)
frame = crypto.seal(AESGCM(key), 0xAABBCCDD, 12345, "hola desde python".encode())
send(f"{key.hex()} {frame.hex()}")
ok(recv() == "hola desde python", "Java descifra la trama de Python")
jframe = bytes.fromhex(recv())
sid, ctr, plain = crypto.open_frame(AESGCM(key), jframe)
ok(plain == b"hola desde java" and sid == 0x01020304 and ctr == 77, "Python descifra la trama de Java")

# 3) ECDH + material de emparejamiento + id de dispositivo
priv = crypto.new_keypair(); server_pub = crypto.public_bytes(priv)
java_pub = bytes.fromhex(recv())
send(server_pub.hex())
j_secret, j_sas, j_dev = recv(), recv(), recv()
shared = crypto.ecdh(priv, java_pub)
secret, sas = crypto.pairing_material(shared, java_pub, server_pub)
ok(j_secret == secret.hex(), "secreto de emparejamiento identico")
ok(j_sas == sas, f"SAS identico ({sas})")
ok(j_dev == crypto.device_id(java_pub), "id de dispositivo identico")
p.wait(timeout=10)
print("INTEROP OK")
