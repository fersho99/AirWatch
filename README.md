# AirWatch — Galaxy Watch 8 como air mouse y control de presentaciones (Etapas 1–3)

Etapa 1 = conexión Wi-Fi cifrada + movimiento del cursor + clics básicos y scroll.
Etapa 4 = conexión por Bluetooth (el reloj como ratón/teclado, sin software en la PC) con Wi-Fi de respaldo.
Etapa 3 = modo presentación (diapositivas con «toque + deslizar», láser en pantalla, temporizador con vibración, notas, perfiles por aplicación).
Etapa 2 = asistente de configuración (muñeca y ejes medidos), gestos de muñeca (clic, clic derecho, arrastrar), scroll inclinando la mano y compensación del sacudón.
Gestos de muñeca (Etapa 2), modo presentación (3) y puente móvil / Bluetooth HID (4) vienen después.

```
airwatch/
├─ pc-server/                 Servidor Windows/Linux/macOS (Python)
│  ├─ airwatch_server/        crypto.py · server.py · input_driver.py · netutil.py · store.py
│  ├─ tools/                  fake_watch.py (reloj simulado) · e2e_test.py · interop/ (Java↔Python)
│  ├─ windows/allow_firewall.ps1 · autostart.ps1
│  └─ run_server.bat
└─ watch-app/                 App Wear OS (Kotlin + Compose) — abrir esta carpeta en Android Studio
   └─ app/src/main/java/com/airwatch/wear/
      Crypto · Prefs · MotionProcessor · WifiLink · BtHidLink · HidKeys · Gestures · Calibrator · Presentation · Engine · AirMouseService · MainActivity · ui/
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

### Arrancar con Windows (opcional)

Con el entorno ya creado (`run_server.bat` una vez) ejecuta en PowerShell, sin ser administrador:

```powershell
cd pc-server\windows
Set-ExecutionPolicy -Scope Process Bypass
.\autostart.ps1          # instala
.\autostart.ps1 -Remove  # quita
```

Crea la tarea programada «AirWatch servidor» (al iniciar sesión, sin ventana). Para ver mensajes de error ejecuta `run_server.bat` en una consola. El láser necesita Tkinter (viene con el instalador de Python de python.org; marca *tcl/tk* al instalar).

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
| Sensibilidad | 2.0× (≈1200 px por radián ×2) | 0.2–3.0 | El cursor es lento/rápido |
| Suavizado | 35 % | 0–90 % | Tiembla (sube) / se siente "pegajoso" (baja) |
| Zona muerta | 0.06 rad/s | 0–0.30 | Se desliza solo en reposo (sube) / no responde a movimientos finos (baja) |
| Aceleración | 0.4 | 0–2 | Quieres llegar rápido a las esquinas sin perder precisión fina |
| Frecuencia de envío | 80 Hz | 60–100 | Más Hz = más suave pero más batería |
| Invertir X / Y | No | — | El cursor se mueve al revés |

**Ejes:** desde la Etapa 2 ya no se suponen: el asistente (primer arranque, o *Calibrar muñeca*) mide en qué dirección gira el reloj cuando la mano va a la derecha / hacia abajo. El lado de la muñeca solo fija los valores previos a calibrar. Tras calibrar, *Invertir X/Y* queda en «No» (úsalo solo si aun así prefieres el cursor al revés).

El giroscopio se auto-calibra (estima su sesgo cuando el reloj está quieto ~0.5 s), así que conviene empezar con la mano en reposo.

## 4b. Etapa 2: asistente, gestos y scroll

**Asistente** (8 pasos, ~2 min; se abre solo en el primer arranque y desde *Calibrar muñeca* / *Ajustes → Reconfigurar*):
muñeca → mover la mano a la derecha (2 veces) → hacia abajo (2 veces) → girar el antebrazo hacia afuera (2 veces) → 5 s de movimiento libre sin gestos → 5 toques/pellizcos → 5 giros afuera → 5 giros adentro → resumen (*Guardar* / *Repetir todo*). Cada trazo debe repetirse en la misma dirección; si no, pide repetir. Los umbrales se ajustan a ti y se califican (bueno / regular / bajo). Los pasos de entrenamiento se pueden saltar (quedan los valores por defecto).

**Gestos** (con la pantalla encendida y conectado), pensados para no hacer movimientos bruscos:

| Gesto | Cómo se detecta | Acción por defecto |
|---|---|---|
| Toque / pellizco | Impulso del acelerómetro (> 1.5 m/s², pasa-altas 5 Hz) sin que el reloj esté girando rápido; se confirma 60 ms después | Clic izquierdo (inmediato, sin retraso) |
| Doble toque | Segundo toque 0.25–0.6 s después del primero (el primero ya hizo clic) | Deja el botón presionado (seleccionar / arrastrar). **Cualquier toque lo suelta** |
| Giro rápido hacia adentro (palma abajo) | Desde la posición normal (palma hacia adentro), giro del antebrazo (> 5 rad/s) hacia adentro; el regreso se ignora 450 ms | Clic derecho |
| Palma arriba sostenida (> 35° respecto a tu posición normal, durante 0.45 s, casi quieto) | Entra en modo scroll: el cursor se congela y subir/bajar la mano desplaza la página, más rápido cuanto más inclines. Vuelve a la posición normal para salir | Scroll |
| Giro rápido hacia afuera (palma arriba) | Igual que el anterior, sentido contrario | Nada (asignable, p. ej. doble clic) |

**Posición normal:** es la de tu mano en reposo apuntando (palma hacia adentro, como dar la mano). El reloj la toma solo la primera vez que lo dejas quieto 1 s; si quedó mal tomada, usa *Ajustes → Fijar posición normal* (o el gesto asignado a «Centrar cursor») con la mano en reposo. Todo giro y el scroll se miden desde ella.

El clic derecho y el scroll están en lados opuestos a propósito: poner la palma arriba despacio (scroll) no dispara el giro rápido. El scroll solo se activa con la palma arriba; con la palma abajo no pasa nada. Cada gesto se reasigna en *Ajustes → Gestos* (Nada, clic izq./der./central, doble clic, Atrás, Adelante, Arrastrar, Cambiar ventana = Alt+Tab, Centrar cursor, Pausar). Si dispara solo, sube el umbral; si no responde, bájalo. Al actualizar desde la versión anterior, las asignaciones de gestos se reinician a este mapa.

**Sacudón:** al detectar un gesto el cursor se congela 350–500 ms y el PC deshace lo que el cursor se movió en los últimos 90 ms antes del clic (el ajuste viaja dentro del mismo mensaje del clic, así el orden está garantizado).

**Batería:** tras 20 s sin moverte los sensores bajan a 20 Hz y los gestos esperan a que vuelvas a mover la mano para despertar; apagado automático a los 10 min.

## 4c. Etapa 3: modo presentación

**Cómo se usa:** reloj → *Modo presentación* (requiere estar conectado). En la PC, abre tu presentación y pulsa *⋯ → Iniciar presentación* (o inícialo tú). Mientras estás en este modo la mano **no mueve el mouse real**; solo mueve el láser (cuando lo enciendes). La pantalla del reloj se queda encendida (los gestos solo funcionan con la pantalla encendida).

| Gesto / control | Acción por defecto |
|---|---|
| **Toque + deslizar la mano a la derecha** (o ▶, o girar corona/bisel) | Diapositiva siguiente |
| **Toque + deslizar la mano a la izquierda** (o ◀, o girar al revés) | Diapositiva anterior |
| Botón *Láser* (un toque enciende, otro apaga) | Láser. Se apaga solo tras 8 s sin mover la mano (ajustable) |
| Toque solo, o movimiento solo | **Nada** (el toque solo muestra «Toque y desliza…»). Es la protección contra cambios accidentales |
| Giros rápidos | Sin asignar (asignables en *Ajustes → Presentación*, p. ej. «Láser») |
| *⋯ → Salir de la presentación* | Esc. **Pide repetir la acción en 2.5 s** (vibra dos veces la primera); lo mismo si asignas «Salir» a un gesto |
| *⋯ → Ir a diapositiva* | Selector táctil (−10 −1 +1 +10) o girar la corona/bisel; *Ir* escribe el número y Enter |
| *⋯ → Notas* | Resumen de la nota del orador (solo PowerPoint) |
| *⋯ → Temporizador* | Duración (5–120 min), iniciar/pausa/reiniciar. *Iniciar presentación* arranca el cronómetro si está en cero |

**Toque + deslizar:** tras el toque hay 0.6 s para mover la mano en horizontal más de 18° (ajustable en *Ajustes → Presentación → Deslizar tras el toque*); el sacudón del propio toque (primeros 60 ms) se ignora. El cambio llega ~0.2–0.4 s después del toque. Con el láser encendido, el punto se congela durante el deslizamiento para que no salte. El marcador (pluma) se quitó del reloj porque con la deriva natural de la mano rayaba toda la pantalla. El láser no se activa por gesto: así mover mucho las manos al explicar no lo enciende.

**Temporizador:** vibra (sin sonido) a la mitad y a 5, 2 y 1 minuto del final, y al terminar: mitad = dos pulsos; 5 min = uno largo; 2 min = dos cortos; 1 min = tres cortos; fin = dos largos. Las alertas que no tienen sentido se omiten (charla de 10 min: la mitad ya es «faltan 5»; charla de 4 min: no hay «5 min»). Los pulsos del temporizador suenan aunque desactives la vibración de gestos.

**Láser (en la PC):** una ventana transparente, siempre encima y que deja pasar los clics (Tkinter). Se activa con `python -m airwatch_server` (sin `--no-overlay`); si falla, el servidor sigue funcionando sin ella. Se apaga sola si se corta la conexión.

**Diapositiva actual y total:** con **PowerPoint** se lee exacta por COM (`pywin32` se instala con `requirements.txt`; si falla, el resto funciona igual). Con el resto (Google Slides, PDF, Impress…) las aplicaciones no la exponen, así que el reloj la **estima contando** siguiente/anterior/ir a y la muestra con `~` (p. ej. `~3/?`). Puedes dar el total en *Ir a diapositiva → Total −/+*. Si navegas con el teclado de la PC, la estimación se desfasa; entonces usa *Ir a diapositiva* para reajustarla.

**Perfil por aplicación:** la PC mira qué ventana tiene el foco (ejecutable y título) y traduce cada acción a los atajos de esa aplicación. El reloj muestra el perfil activo; *⋯ → Aplicación* lo fija a mano o vuelve a «Auto». Detección: `powerpnt.exe`; `soffice.bin`; `sumatrapdf.exe`; `acrobat.exe`/`acrord32.exe`; y en navegadores (Chrome, Edge, Firefox, Brave, Opera, Vivaldi) por el título: «Google Slides / Presentaciones de Google» → Google Slides, «.pdf» → PDF en navegador.

### Atajos que envía la PC por aplicación

| Acción | PowerPoint | Google Slides | LibreOffice Impress | Acrobat / Reader | SumatraPDF | PDF en navegador | Keynote (Mac, exp.) |
|---|---|---|---|---|---|---|---|
| Siguiente | → | → | → | Re Pág | → | Re Pág | → |
| Anterior | ← | ← | ← | Av Pág | ← | Av Pág | ← |
| Iniciar | F5 | Ctrl+F5 | F5 | Ctrl+L | F5 | F11 | Opción+Cmd+P |
| Iniciar desde la actual | Shift+F5 | Ctrl+F5 ¹ | Shift+F5 ² | Ctrl+L | F5 | F11 | Opción+Cmd+P |
| Salir | Esc | Esc | Esc | Esc | Esc | Esc | Esc |
| Pantalla negra | B | B | B | — | . | — | — |
| Pantalla blanca | W | W | W | — | W | — | — |
| Ir a diapositiva N | N + Enter | N + Enter | N + Enter | Ctrl+Shift+N, N, Enter | Ctrl+G, N, Enter | — | — |

¹ Google solo documenta Ctrl+F5 para presentar. ² Según la versión de LibreOffice. Fuentes: ayuda de Google Docs Editors (atajos de Slides), ayuda de LibreOffice (atajos de Impress), documentación de SumatraPDF, guía de atajos de PowerPoint y de Acrobat. **Keynote queda sin verificar** y no se detecta solo (la detección de ventana es solo para Windows): fija el perfil a mano. En otras apps, «Genérico» usa → ← F5 Shift+F5 Esc B W.

## 4d. Etapa 4: Bluetooth (reloj como ratón y teclado)

El reloj se anuncia como un mouse + teclado Bluetooth normal (API estándar `BluetoothHidDevice`, Android 9+; el Watch 8 Classic con Windows ya se usa así en otras apps). **No hace falta el servidor ni el teléfono**: el teléfono como puente resultó innecesario porque el reloj puede hacerlo solo.

**Emparejar (una vez):** reloj → *Ajustes → Conexión* = *Bluetooth* (o *Auto*) → *Emparejar Bluetooth* (queda visible 2 min; acepta los permisos de Bluetooth la primera vez) → en Windows: *Configuración → Bluetooth y dispositivos → Agregar dispositivo → Bluetooth → «AirWatch»*. Después el reloj se reconecta solo al último PC al iniciar la sesión.

| Modo de *Conexión* | Qué hace |
|---|---|
| Wi-Fi (por defecto) | Como hasta ahora: servidor en la PC, láser, notas, perfiles por aplicación |
| Bluetooth | Solo Bluetooth: cursor, clics, scroll y teclas (flechas, F5, Esc, B, W, ir a diapositiva) |
| Auto | Intenta Bluetooth; si no está conectado, usa Wi-Fi. Con ambos activos manda por Bluetooth |

**Qué funciona por Bluetooth:** cursor, clic izquierdo/derecho/central, doble clic, arrastrar, atrás/adelante, scroll, Alt+Tab y, en presentación, siguiente/anterior (toque + deslizar), iniciar (F5), terminar (Esc), pantalla negra/blanca e *ir a diapositiva*.
**Qué no:** láser, notas, número de diapositiva, perfiles por aplicación (el reloj no sabe qué programa está en primer plano: usa los atajos de PowerPoint/Impress; Google Slides arranca con Ctrl+F5, no F5) y *Centrar cursor* en la PC. El temporizador sí funciona (es del reloj).

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
python tools/presenter_test.py                   # perfiles, detección de app, puntero, diapositivas (COM falso)
python tools/e2e_presentation.py                 # presentación de extremo a extremo (teclas, láser, desconexión)

cd watch-app
./run-logic-tests.sh                              # lógica pura del reloj con señales sintéticas (necesita kotlinc; KOTLINC=/ruta)
```
`presenter_test.py` y `e2e_presentation.py` cubren el modo presentación (el láser se registra en lugar de dibujarse con `--dry-run`; la ventana de Tkinter y la lectura de PowerPoint real solo se pueden probar en tu PC).
`run-logic-tests.sh` simula una muñeca (también prueba temporizador, confirmación y selector) (ejes girados, ruido, sesgo, temblor): comprueba que el asistente recupera los ejes, que 60 s de movimiento no disparan gestos, que toque/giros se detectan 10 de 10, el congelado del cursor, el historial de compensación y el scroll.
`e2e_test.py` verifica: emparejamiento, coincidencia del SAS, cifrado, rechazo de dispositivos no emparejados, replay, tramas alteradas, suma exacta de movimientos y latencia UDP en loopback.

## 7. Limitaciones conocidas

- **JDK:** Gradle 8.11 no soporta Java 25 (el JBR de las versiones recientes de Android Studio). Compila con JDK 17 o 21 (`JAVA_HOME`, o *Settings → Build Tools → Gradle → Gradle JDK*).
- Probado en un Galaxy Watch 8 con Windows 11. Los ejes del cursor pueden requerir *Invertir X/Y* según cómo lleves el reloj.
- **Latencia <30 ms:** el reloj mide el RTT por UDP y lo muestra (la latencia de ida ≈ la mitad). En loopback el protocolo + cifrado cuesta ≈0.1 ms; el resto depende de tu router. `WIFI_MODE_FULL_LOW_LATENCY` solo se aplica con la app en primer plano y la pantalla encendida (por eso el modo mouse mantiene la pantalla activa).
- El tráfico va por Wi-Fi gracias a `requestNetwork(TRANSPORT_WIFI)`, pero si el reloj no tiene Wi-Fi conectado a la red local, no hay conexión (para eso existe el modo Bluetooth (sección 4d); la ruta vía celular quedó descartada).
- Apagado automático tras 10 min sin actividad (fijo por ahora).
- Un solo PC emparejado por reloj en esta etapa.
- Bisel/corona: el Watch 8 **Classic** tiene bisel físico (scroll con el bisel); el Watch 8 sin bisel usa los botones ▲▼.
- **Etapa 2 sin compilar en el entorno de desarrollo:** la lógica de gestos/asistente está probada en JVM, pero el código Android/Compose (`Engine.kt`, `Screens.kt`, `Prefs.kt`) se escribió sin SDK; si Gradle da errores, envíalos tal cual.
- Los umbrales de gestos dependen de cada persona: repite el asistente si hay falsos disparos. Un toque justo tras 20 s de reposo puede no detectarse (sensores a 20 Hz); mueve la mano primero.
- El PC solo acepta teclas de una lista fija (ctrl/alt/shift/win, flechas, F1–F12, letras, dígitos, etc.), máx. 4 a la vez.
- **Etapa 3, sin probar en equipo real:** la ventana del láser (Tkinter) y la lectura de PowerPoint por COM se escribieron sin acceso a Windows/PowerPoint; se prueban con dobles falsos. Si PowerPoint en pantalla completa tapa el láser, dímelo. Solo se dibuja en el monitor principal.
- La estimación de diapositiva fuera de PowerPoint se desfasa si navegas con el teclado o el mouse de la PC.
- Faltan de la especificación original: **tile y complicación** de Wear (dependencia extra que no pude compilar aquí). Los visores de PDF del navegador solo tienen pantalla completa (sin pantalla negra ni «ir a diapositiva»).
- El movimiento viaja por UDP y el aviso «encender láser» por WebSocket, sin orden entre sí: al activar el láser el cursor real puede moverse unos píxeles.

### Limitaciones del Galaxy Watch 8 / Wear OS (a tener en cuenta)

- **Pantalla encendida:** los gestos y el modo presentación solo funcionan con la pantalla encendida (se bloquean con la pantalla apagada a propósito). Sube el tiempo de pantalla del reloj o deja que la app la mantenga encendida; esto gasta batería.
- **Batería:** el giroscopio a 80–100 Hz más el Wi-Fi de baja latencia consumen bastante. Para sesiones largas, carga el reloj o usa Bluetooth. La app baja la frecuencia de sensores tras unos segundos de reposo.
- **Wi-Fi:** el reloj y la PC deben estar en la misma red sin «aislamiento de clientes» (algunas redes de invitados y oficinas lo activan). Wear OS puede enrutar por el teléfono si el Wi-Fi se pierde; la app fuerza Wi-Fi pero no hay conexión sin él.
- **Bluetooth HID:** depende de que Samsung/Wear OS exponga el perfil HID en tu versión; si *Emparejar* no muestra «AirWatch» en Windows o la conexión se cae, apaga y enciende el Bluetooth del reloj y quita el dispositivo en Windows para emparejar de nuevo. Un reloj mantiene un solo PC activo a la vez. No hay ratón en modo táctil (touchpad) todavía.
- **Sensores:** sin un sensor de posición absoluta, apuntar es relativo (como un ratón): con el uso se desvía y se corrige con *Centrar cursor* o *Fijar posición normal*. Los gestos de toque dependen del acelerómetro; un toque muy suave o con el brazo en movimiento puede no detectarse.
- **Sin probar en equipo real:** todo el código Android/Compose (incluido Bluetooth), la ventana del láser y la lectura de PowerPoint se escribieron sin SDK ni Windows de pruebas; se validan con simuladores. Reporta errores de compilación o comportamientos tal cual.
- **Pendiente:** tile y complicación de Wear; un solo monitor para el láser; un solo PC emparejado por Wi-Fi.
