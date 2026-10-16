# AirWatch — Galaxy Watch 8 como air mouse (Etapa 1)

Etapa 1 = conexión Wi-Fi cifrada + movimiento del cursor + clics básicos y scroll.
Gestos de muñeca (Etapa 2), modo presentación (3) y puente móvil / Bluetooth HID (4) vienen después.

```
airwatch/
├─ pc-server/                 Servidor Windows/Linux/macOS (Python)
│  ├─ airwatch_server/        crypto.py · server.py · input_driver.py · netutil.py · store.py
│  ├─ tools/                  fake_watch.py (reloj simulado) · e2e_test.py · interop/ (Java↔Python)
│  ├─ windows/allow_firewall.ps1
│  └─ run_server.bat
└─ watch-app/                 App Wear OS (Kotlin + Compose) — abrir esta carpeta en Android Studio
   └─ app/src/main/java/com/airwatch/wear/
      Crypto · Prefs · MotionProcessor · WifiLink · Engine · AirMouseService · MainActivity · ui/Screens
```

## 1. Servidor en la PC

Requiere Python 3.10+.

```powershell
cd pc-server
py -3 -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

### ⚠ Firewall de Windows (hazlo antes de la primera conexión)

Al ejecutar el servidor por primera vez, Windows mostrará **"El Firewall de Windows Defender ha bloqueado algunas características de esta aplicación"** para `python.exe`. Marca **Redes privadas** y pulsa *Permitir acceso*. Si lo cerraste o tu Wi-Fi está como **Pública**, el reloj no podrá conectar ni descubrir la PC.

La forma más limpia es abrir solo lo necesario (TCP 8765, UDP 8766 y mDNS UDP 5353, solo perfil *Privado*). PowerShell **como Administrador**:

```powershell
cd pc-server\windows
Set-ExecutionPolicy -Scope Process Bypass
.\allow_firewall.ps1
```

Si tu red es "Pública": Configuración → Red e Internet → Wi-Fi → tu red → *Tipo de perfil de red* → **Privada**.
Para quitar las reglas: `Get-NetFirewallRule -DisplayName 'AirWatch*' | Remove-NetFirewallRule`.

### Ejecutar

```powershell
.venv\Scripts\python -m airwatch_server --pair      # primera vez: abre 120 s de emparejamiento
.venv\Scripts\python -m airwatch_server             # uso normal
```

Opciones útiles: `--list-devices`, `--forget <ID>`, `--no-mdns`, `--port`, `--udp-port`, `--dry-run` (no mueve el mouse real).
La PC y el reloj deben estar en la **misma red Wi-Fi** (sin "aislamiento de clientes"/AP isolation, típico en redes de invitados).

## 2. App del reloj

### Compilar
1. Android Studio → *Open* → carpeta `watch-app/`. Deja que sincronice Gradle (necesita internet la primera vez).
2. Build → *Make Project*. (Usa AGP 8.9.1 / Kotlin 2.1.20 / compileSdk 36; si Studio sugiere actualizar versiones, acepta.)

### Conectar el reloj por ADB sobre Wi-Fi
En el reloj (misma red Wi-Fi que la PC):
1. Ajustes → *Acerca del reloj* → *Información del software* → toca **Versión de software** varias veces (5–7) hasta ver "Modo desarrollador activado".
2. Ajustes → *Opciones de desarrollador* → activa **Depuración ADB** y **Depuración inalámbrica** (en algunos modelos aparece como *Debug over Wi-Fi*).
3. Entra a *Depuración inalámbrica*:
   - **Wear OS 4 o superior (tu Watch 8)**: toca *Vincular nuevo dispositivo*; verás un código y `IP:puerto-de-vinculación`.
     ```
     adb pair 192.168.1.60:37215        # pide el código de 6 dígitos del reloj
     adb connect 192.168.1.60:41123     # IP:puerto que aparece en la pantalla principal de Depuración inalámbrica (NO el de vinculación)
     ```
   - Wear OS 3 o anterior: `adb connect <IP-del-reloj>:5555`.
4. Comprueba con `adb devices` (debe aparecer como `device`).

Esto aplica igual al **Watch 8** y al **Watch 8 Classic**.

### Instalar
Desde Android Studio (▶ con el reloj seleccionado) o por consola:
```
cd watch-app
gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.airwatch.wear/.MainActivity
```
Al abrir, acepta los permisos de notificaciones y de dispositivos Wi-Fi cercanos.

## 3. Primer uso

1. En la PC: `python -m airwatch_server --pair`.
2. Reloj → **Emparejar**. Aparece un código de 6 dígitos (por ejemplo `831 801`); la consola de la PC muestra el mismo.
3. Si **coinciden**: ✓ en el reloj y escribe `s` + Enter en la PC. Si no coinciden, ✗ (alguien podría estar interceptando).
4. Reloj → **Conectar** → **Modo mouse**. Mueve la muñeca: el cursor se mueve. Botones **Izq/Der** (mantener = arrastrar), **▲▼** o bisel = scroll, **Centrar**, **Pausa**.

El emparejamiento se hace una vez; después basta con **Conectar** (la reconexión es automática si se corta el Wi-Fi).

### Si no encuentra la PC (mDNS bloqueado por el router)
La consola del servidor imprime sus IPs ("alcanzable en …"). Entonces:
```
adb shell am start -n com.airwatch.wear/.MainActivity --es host 192.168.1.50
adb shell am start -n com.airwatch.wear/.MainActivity --es host 192.168.1.50 --ez pair true   # para emparejar
```
La app recuerda la última IP que funcionó.

## 4. Ajustes iniciales y cómo calibrarlos

| Ajuste | Inicial | Rango | Si… |
|---|---|---|---|
| Sensibilidad | 1.0× (≈1200 px por radián) | 0.2–3.0 | El cursor es lento/rápido |
| Suavizado | 35 % | 0–90 % | Tiembla (sube) / se siente "pegajoso" (baja) |
| Zona muerta | 0.06 rad/s | 0–0.30 | Se desliza solo en reposo (sube) / no responde a movimientos finos (baja) |
| Aceleración | 0.4 | 0–2 | Quieres llegar rápido a las esquinas sin perder precisión fina |
| Frecuencia de envío | 80 Hz | 60–100 | Más Hz = más suave pero más batería |
| Invertir X / Y | No | — | El cursor se mueve al revés |

Orientación asumida: reloj en muñeca izquierda, cara hacia arriba. Subir/bajar la mano mueve el cursor en vertical; girarla a izquierda/derecha lo mueve en horizontal. **Si algún eje sale invertido, usa Invertir X/Y** (los signos dependen de cómo uses el reloj).

El giroscopio se auto-calibra (estima su sesgo cuando el reloj está quieto ~0.5 s), así que conviene empezar con la mano en reposo.

## 5. Protocolo y seguridad (resumen)

- **Emparejamiento:** ECDH P-256 efímero + código de verificación de 6 dígitos que comparas en ambas pantallas (como Bluetooth "comparación numérica"). Ambos guardan un secreto de 32 B (en el reloj, cifrado con el Android Keystore).
- **Cada conexión:** el reloj envía un nonce; la PC responde con otro y una prueba HMAC (demuestra que es *tu* PC). Se deriva una clave AES-256-GCM de sesión.
- **Canales:** WebSocket (clics, scroll, control) y UDP (movimiento, 5 bytes) — ambos con tramas AES-GCM, contador anti-replay con ventana deslizante.
- La ventana de emparejamiento es de un solo uso y dura 120 s.

## 6. Pruebas incluidas

```
cd pc-server
python tools/e2e_test.py                         # servidor real (--dry-run) + reloj simulado
python tools/interop/run_interop.py              # Crypto del reloj (JCA) ↔ Python (necesita java)
```
`e2e_test.py` verifica: emparejamiento, coincidencia del SAS, cifrado, rechazo de dispositivos no emparejados, replay, tramas alteradas, suma exacta de movimientos y latencia UDP en loopback.

## 7. Limitaciones conocidas de la Etapa 1

- **JDK:** Gradle 8.11 no soporta Java 25 (el JBR de las versiones recientes de Android Studio). Compila con JDK 17 o 21 (`JAVA_HOME`, o *Settings → Build Tools → Gradle → Gradle JDK*).
- Probado en un Galaxy Watch 8 con Windows 11. Los ejes del cursor pueden requerir *Invertir X/Y* según cómo lleves el reloj.
- **Latencia <30 ms:** el reloj mide el RTT por UDP y lo muestra (la latencia de ida ≈ la mitad). En loopback el protocolo + cifrado cuesta ≈0.1 ms; el resto depende de tu router. `WIFI_MODE_FULL_LOW_LATENCY` solo se aplica con la app en primer plano y la pantalla encendida (por eso el modo mouse mantiene la pantalla activa).
- El tráfico va por Wi-Fi gracias a `requestNetwork(TRANSPORT_WIFI)`, pero si el reloj no tiene Wi-Fi conectado a la red local, no hay conexión (la ruta vía celular llega en la Etapa 4).
- Apagado automático tras 10 min sin actividad (fijo por ahora).
- Un solo PC emparejado por reloj en esta etapa.
- Bisel/corona: el Watch 8 **Classic** tiene bisel físico (scroll con el bisel); el Watch 8 sin bisel usa los botones ▲▼.
