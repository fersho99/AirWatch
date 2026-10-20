package com.airwatch.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.LockSupport

/** Estado del temporizador para la pantalla. `remainingMs` negativo = tiempo excedido. */
data class TimerUi(val running: Boolean = false, val elapsedMs: Long = 0L, val remainingMs: Long = 0L, val totalMs: Long = 0L)

/** Ultimo gesto reconocido (para mostrarlo en la pantalla del mouse). */
data class GestureEvent(val gesture: Gesture, val action: Action, val atMs: Long)

/**
 * Motor del air mouse: une sensores -> MotionProcessor / GestureDetector / ScrollPose -> hilo de envio -> WifiLink.
 * Vive en el proceso (no en la actividad); AirMouseService solo lo mantiene vivo en primer plano.
 *
 * Los sensores los pueden pedir dos "usuarios": la sesion (conectado al PC) y el asistente de configuracion
 * (que no necesita conexion). Se apagan cuando ninguno los usa.
 */
object Engine {
    private const val SLOW_PERIOD_US = 50_000     // 20 Hz en reposo largo
    private const val FAST_PERIOD_US = 10_000     // 100 Hz en uso
    private const val IDLE_TO_SLOW_NS = 20_000_000_000L
    private const val AUTO_OFF_MS = 10 * 60 * 1000L
    private const val FREEZE_TAP_NS = 350_000_000L
    private const val FREEZE_ROLL_NS = 500_000_000L
    private const val FREEZE_EXIT_SCROLL_NS = 300_000_000L

    private const val USER_SESSION = 1
    private const val USER_WIZARD = 2

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var prefs: Prefs
        private set
    lateinit var store: PairingStore
        private set
    lateinit var link: WifiLink
        private set

    val settings = MutableStateFlow(Settings())
    val tracking = MutableStateFlow(true)
    val running = MutableStateFlow(false)
    val dragging = MutableStateFlow(false)
    val scrollActive = MutableStateFlow(false)
    val lastGesture = MutableStateFlow<GestureEvent?>(null)

    // --- Modo presentacion
    val presenting = MutableStateFlow(false)
    val pointer = MutableStateFlow(PointerMode.OFF)
    val slide = MutableStateFlow<SlideInfo?>(null)
    val profile = MutableStateFlow<ProfileInfo?>(null)
    val timerUi = MutableStateFlow(TimerUi())
    val endArmed = MutableStateFlow(false)
    /** Aviso corto para mostrar en pantalla (texto, instante en ms); la UI lo oculta sola. */
    val toast = MutableStateFlow<Pair<String, Long>?>(null)

    /** Estado del asistente; null si no esta abierto. */
    val wizardState = MutableStateFlow<WizardState?>(null)

    val motion = MotionProcessor { settings.value }

    private val detector = GestureDetector {
        val s = settings.value
        GestureConfig(s.tapThreshold, s.rollThreshold, s.axisRoll)
    }
    private val scrollPose = ScrollPose()
    private val history = MoveHistory()
    private val tapCombiner = TapCombiner()
    private val timer = PresentationTimer(20 * PresentationTimer.MIN)
    private val endGate = ConfirmGate(2500L)
    private val swipe = SwipeAfterTap { settings.value.presSwipeDeg }
    @Volatile private var laserActiveMs = 0L     // ultimo momento con la mano en movimiento mientras el laser esta encendido

    private val calLock = Any()
    private var calibrator: Calibrator? = null

    private var sm: SensorManager? = null
    private var gyro: Sensor? = null
    private var accel: Sensor? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var sensorUsers = 0
    private var fastRate = true
    @Volatile private var screenOn = true
    @Volatile private var lastGestureTs = 0L

    @Volatile private var senderRun = false
    private var sender: Thread? = null
    @Volatile private var lastActivityMs = 0L
    private var scrollAcc = 0f
    private var scrollRateAcc = 0f

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = Prefs(app)
        store = PairingStore(app)
        settings.value = prefs.load()
        link = WifiLink(app, scope, store, prefs)
        link.onServerMessage = ::onServerMessage
        timer.setTotal(settings.value.presMinutes * PresentationTimer.MIN, 0L)
        publishTimer()
        scope.launch {                         // al conectar: reaplicar el perfil fijo y arrancar con el laser apagado
            link.state.collect { st ->
                if (st.phase == Phase.CONNECTED) {
                    val p = settings.value.presProfile
                    if (p != "auto") link.sendCtrl(JSONObject().put("t", "profile").put("name", p))
                } else {
                    pointer.value = PointerMode.OFF
                    slide.value = null
                }
            }
        }
        scope.launch {                         // reloj del temporizador
            while (true) {
                delay(500)
                if (presenting.value && pointer.value == PointerMode.LASER &&
                    SystemClock.elapsedRealtime() - laserActiveMs > settings.value.presLaserIdleS * 1000L) {
                    setPointer(PointerMode.OFF)
                    showToast("Láser apagado")
                }
                if (timer.running) {
                    val alerts = timer.tick(SystemClock.elapsedRealtime())
                    publishTimer()
                    alerts.lastOrNull()?.let { timerHaptic(it) }
                }
            }
        }
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                screenOn = i.action == Intent.ACTION_SCREEN_ON
                detector.reset()               // al apagar/encender se reinicia el calentamiento: sin gestos accidentales
            }
        }, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
    }

    fun isPaired() = store.load() != null

    fun update(f: (Settings) -> Settings) {
        settings.value = f(settings.value).also { prefs.save(it) }
    }

    // ------------------------------------------------------------ arranque / paro (via servicio)
    fun requestStart(ctx: Context, pair: Boolean = false, host: String? = null) {
        val i = Intent(ctx, AirMouseService::class.java)
            .putExtra(AirMouseService.EXTRA_PAIR, pair)
            .putExtra(AirMouseService.EXTRA_HOST, host)
        ctx.startForegroundService(i)
    }

    fun requestStop(ctx: Context) {
        ctx.startService(Intent(ctx, AirMouseService::class.java).setAction(AirMouseService.ACTION_STOP))
    }

    fun forgetPairing(ctx: Context) {
        requestStop(ctx)
        store.clear()
    }

    /** Llamado por el servicio una vez en primer plano. */
    fun startSession(pair: Boolean, host: String?) {
        stopInternal()
        running.value = true
        touch()
        resetSignalState()
        acquireSensors(USER_SESSION)
        startSender()
        link.start(pair, host)
    }

    fun stopSession() {
        stopInternal()
        running.value = false
    }

    private fun stopInternal() {
        senderRun = false
        sender?.interrupt(); sender = null
        releaseSensors(USER_SESSION)
        link.stop()
        dragging.value = false
        scrollActive.value = false
        motion.cursorSuspended = false
    }

    private fun resetSignalState() {
        motion.reset(); detector.reset(); scrollPose.reset(); history.clear()
        scrollAcc = 0f; scrollRateAcc = 0f
        dragging.value = false; scrollActive.value = false; motion.cursorSuspended = false; tapCombiner.reset(); swipe.reset()
    }

    // ------------------------------------------------------------ asistente de configuracion
    fun startWizard() {
        synchronized(calLock) {
            calibrator = Calibrator(
                onChange = { wizardState.value = it },
                onBuzz = { haptic(it.toLong()) },
            )
        }
        acquireSensors(USER_WIZARD)
    }

    fun stopWizard() {
        synchronized(calLock) { calibrator = null }
        wizardState.value = null
        releaseSensors(USER_WIZARD)
        resetSignalState()
    }

    fun wizardSetWrist(w: Wrist) { synchronized(calLock) { calibrator?.setWrist(w) } }
    fun wizardRetry() { synchronized(calLock) { calibrator?.retryStep() } }
    fun wizardSkip() { synchronized(calLock) { calibrator?.skip() } }
    fun wizardRestart() { synchronized(calLock) { calibrator?.restart() } }

    /** Guarda lo medido (ejes, muneca y umbrales entrenados) y cierra el asistente. */
    fun wizardSave() {
        val r = synchronized(calLock) { calibrator?.result() }
        if (r != null) {
            update {
                it.copy(
                    wrist = r.wrist,
                    axisRight = r.right,
                    axisDown = r.down,
                    axisRoll = r.roll,
                    tapThreshold = r.tapThreshold ?: it.tapThreshold,
                    rollThreshold = r.rollThreshold ?: it.rollThreshold,
                    invertX = false,
                    invertY = false,
                    setupDone = true,
                )
            }
        }
        stopWizard()
    }

    // ------------------------------------------------------------ sensores
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> onGyro(e.timestamp, v[0], v[1], v[2])
                Sensor.TYPE_ACCELEROMETER -> onAccel(e.timestamp, v[0], v[1], v[2])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun wizardOpen(): Boolean = synchronized(calLock) { calibrator != null }

    private fun onGyro(ts: Long, gx: Float, gy: Float, gz: Float) {
        synchronized(calLock) { calibrator }?.let { cal ->
            synchronized(calLock) { cal.feedGyro(ts, gx, gy, gz) }
            return
        }
        val s = settings.value
        motion.onGyro(gx, gy, gz, ts)

        if (s.scrollPose && !presenting.value) {
            scrollPose.onGyro(ts, motion.omega, ScrollConfig(s.axisRoll, s.axisDown, s.scrollEnterDeg, s.scrollEnterDeg * 0.55f))
        }
        if (presenting.value) presentationGyro(ts, s)
        if (gesturesActive(s)) {
            detector.onGyro(ts, gx, gy, gz)?.let { onDetected(it, ts) }
        }

        val last = maxOf(motion.lastActiveNs, lastGestureTs)
        val idleFor = ts - last
        if (sensorUsers and USER_WIZARD == 0) {
            if (fastRate && last != 0L && idleFor > IDLE_TO_SLOW_NS && !scrollPose.active) setSensorRate(fast = false)
            else if (!fastRate && idleFor < 1_000_000_000L) setSensorRate(fast = true)
        }
    }

    private fun onAccel(ts: Long, ax: Float, ay: Float, az: Float) {
        synchronized(calLock) { calibrator }?.let { cal ->
            synchronized(calLock) { cal.feedAccel(ts, ax, ay, az) }
            return
        }
        val s = settings.value
        if (s.scrollPose && !presenting.value && screenOn) {
            val cfg = ScrollConfig(s.axisRoll, s.axisDown, s.scrollEnterDeg, s.scrollEnterDeg * 0.55f)
            when (scrollPose.onAccel(ts, Vec3(ax, ay, az), cfg)) {
                ScrollPose.Transition.ENTER -> {
                    lastGestureTs = ts
                    motion.cursorSuspended = true
                    scrollActive.value = true
                    haptic(30)
                }
                ScrollPose.Transition.EXIT -> {
                    motion.cursorSuspended = false
                    motion.freeze(ts + FREEZE_EXIT_SCROLL_NS)
                    scrollActive.value = false
                    haptic(20)
                }
                null -> {}
            }
        }
        if (gesturesActive(s)) {
            detector.onAccel(ts, ax, ay, az)?.let { onDetected(it, ts) }
        }
    }

    private fun gesturesActive(s: Settings) =
        s.gesturesEnabled && screenOn && running.value && link.state.value.phase == Phase.CONNECTED && !scrollPose.active

    @Synchronized private fun acquireSensors(user: Int) {
        val first = sensorUsers == 0
        sensorUsers = sensorUsers or user
        if (first) startSensors()
    }

    @Synchronized private fun releaseSensors(user: Int) {
        sensorUsers = sensorUsers and user.inv()
        if (sensorUsers == 0) stopSensors()
    }

    private fun startSensors() {
        val mgr = app.getSystemService(SensorManager::class.java)
        sm = mgr
        gyro = mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel = mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val t = HandlerThread("airwatch-sensors", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
        sensorThread = t
        sensorHandler = Handler(t.looper)
        fastRate = true
        registerAll(FAST_PERIOD_US)
    }

    private fun registerAll(period: Int) {
        val mgr = sm ?: return
        mgr.registerListener(listener, gyro, period, sensorHandler)
        mgr.registerListener(listener, accel, period, sensorHandler)
    }

    private fun setSensorRate(fast: Boolean) {
        val mgr = sm ?: return
        fastRate = fast
        mgr.unregisterListener(listener)
        registerAll(if (fast) FAST_PERIOD_US else SLOW_PERIOD_US)
        detector.reset()     // los filtros asumen una frecuencia estable
    }

    private fun stopSensors() {
        sm?.unregisterListener(listener)
        sensorThread?.quitSafely()
        sensorThread = null; sensorHandler = null; sm = null
    }

    // ------------------------------------------------------------ gestos -> acciones
    private fun onDetected(g: Gesture, ts: Long) {
        if (presenting.value) {
            // Presentacion: un toque solo abre la ventana de "toque + deslizar"; no cambia nada por si mismo.
            if (g == Gesture.TAP) armSwipe(ts) else handleGesture(g, ts)
            return
        }
        handleGesture(if (g == Gesture.TAP) tapCombiner.onTap(ts) else g, ts)
    }

    private fun handleGesture(g: Gesture, ts: Long) {
        val s = settings.value
        // Con el boton presionado (arrastre), cualquier toque lo suelta.
        if (dragging.value && (g == Gesture.TAP || g == Gesture.DOUBLE_TAP)) {
            lastGestureTs = ts
            motion.freeze(ts + FREEZE_TAP_NS)
            history.clear(); tapCombiner.reset()
            touch(); haptic(20)
            btn("left", "up"); dragging.value = false
            lastGesture.value = GestureEvent(g, Action.DRAG_TOGGLE, System.currentTimeMillis())
            return
        }
        val action = if (presenting.value) s.presActionFor(g) else s.actionFor(g)
        if (action == Action.NONE) return
        if (!tracking.value && action != Action.PAUSE_TOGGLE) return

        lastGestureTs = ts
        motion.freeze(ts + if (g == Gesture.TAP) FREEZE_TAP_NS else FREEZE_ROLL_NS)

        // Deshacer lo que el sacudon del gesto movio el cursor en los ultimos compMs.
        var adj: IntArray? = null
        if (action.needsCompensation()) {
            val sum = IntArray(2)
            history.sumSince(System.nanoTime() - s.compMs * 1_000_000L, sum)
            if (sum[0] != 0 || sum[1] != 0) adj = intArrayOf(-sum[0], -sum[1])
        }
        history.clear()

        touch()
        if (!presenting.value || s.presHaptics) haptic(20)
        lastGesture.value = GestureEvent(g, action, System.currentTimeMillis())
        perform(action, adj)
    }

    private fun Action.needsCompensation() = when (this) {
        Action.LEFT_CLICK, Action.RIGHT_CLICK, Action.MIDDLE_CLICK, Action.DOUBLE_CLICK, Action.DRAG_TOGGLE -> true
        else -> false
    }

    private fun btn(name: String, state: String, adj: IntArray? = null) {
        val m = JSONObject().put("t", "btn").put("b", name).put("s", state)
        if (adj != null) m.put("adj", JSONArray().put(adj[0]).put(adj[1]))
        link.sendCtrl(m)
    }

    fun keyCombo(vararg keys: String) {
        touch()
        link.sendCtrl(JSONObject().put("t", "key").put("k", JSONArray(keys.toList())))
    }

    private fun perform(action: Action, adj: IntArray?) {
        when (action) {
            Action.NONE -> {}
            Action.LEFT_CLICK -> btn("left", "click", adj)
            Action.RIGHT_CLICK -> btn("right", "click", adj)
            Action.MIDDLE_CLICK -> btn("middle", "click", adj)
            Action.DOUBLE_CLICK -> btn("left", "double", adj)
            Action.BACK -> btn("back", "click")
            Action.FORWARD -> btn("forward", "click")
            Action.DRAG_TOGGLE -> {
                if (!dragging.value) { btn("left", "down", adj); dragging.value = true; haptic(40) }
                else { btn("left", "up"); dragging.value = false }
            }
            Action.ALT_TAB -> keyCombo("alt", "tab")
            Action.RECENTER -> recenter()
            Action.PAUSE_TOGGLE -> toggleTracking()
            Action.NEXT_SLIDE -> pres("next")
            Action.PREV_SLIDE -> pres("prev")
            Action.LASER_TOGGLE -> toggleLaser()
            Action.BLACK_SCREEN -> pres("black")
            Action.WHITE_SCREEN -> pres("white")
            Action.START_SHOW -> startShow(fromCurrent = false)
            Action.END_SHOW -> requestEnd()
        }
    }

    // ------------------------------------------------------------ modo presentacion
    fun setPresenting(on: Boolean) {
        if (presenting.value == on) return
        presenting.value = on
        swipe.reset()
        scrollPose.reset()
        scrollActive.value = false
        motion.cursorSuspended = false
        motion.recenter()
        if (!on) setPointer(PointerMode.OFF)
    }

    /** Pide a la PC una accion de presentacion (siguiente, anterior, black, white, start, end, goto...). */
    fun pres(action: String, n: Int? = null) {
        touch()
        val m = JSONObject().put("t", "pres").put("a", action)
        if (n != null) m.put("n", n)
        link.sendCtrl(m)
    }

    fun startShow(fromCurrent: Boolean) {
        pres(if (fromCurrent) "start_current" else "start")
        if (!timer.running && timer.elapsed(SystemClock.elapsedRealtime()) == 0L) timerStartPause()   // la charla empieza con el show
    }

    /** Salir de la presentacion pide repetir la accion dentro de 2.5 s (evita salidas por un gesto accidental). */
    fun requestEnd() {
        val now = SystemClock.elapsedRealtime()
        if (endGate.request(now)) {
            endArmed.value = false
            pres("end")
            haptic(40)
        } else {
            endArmed.value = true
            showToast("Repite para salir")
            vibratePattern(longArrayOf(0, 60, 80, 60))
            scope.launch {
                delay(2600)
                if (!endGate.isArmed(SystemClock.elapsedRealtime())) endArmed.value = false
            }
        }
    }

    fun toggleLaser() = setPointer(if (pointer.value == PointerMode.LASER) PointerMode.OFF else PointerMode.LASER)

    /** Enciende o apaga el laser. El movimiento de la mano solo mueve el punto mientras esta encendido. */
    fun setPointer(mode: PointerMode) {
        if (pointer.value == mode) return
        pointer.value = mode
        laserActiveMs = SystemClock.elapsedRealtime()
        motion.recenter()
        link.sendCtrl(JSONObject().put("t", "ptr").put("m", mode.wire))
        if (presenting.value && settings.value.presHaptics) haptic(if (mode == PointerMode.OFF) 15 else 30)
    }

    // ------------------------------------------------------------ toque + deslizar (presentacion)
    private fun armSwipe(ts: Long) {
        lastGestureTs = ts
        swipe.arm(ts)
        if (pointer.value != PointerMode.OFF) motion.freeze(ts + SwipeAfterTap.WINDOW_NS)   // el laser no salta con el deslizamiento
        if (settings.value.presHaptics) haptic(10)
    }

    /** Hilo de sensores, en cada muestra del giroscopio mientras se presenta. */
    private fun presentationGyro(ts: Long, s: Settings) {
        if (swipe.armed && screenOn) {
            val w = motion.omega
            var rx = w dot s.axisRight
            val ry = w dot s.axisDown
            if (s.invertX) rx = -rx
            val dir = swipe.onGyro(ts, rx, ry)
            if (dir != null) {
                lastGestureTs = ts
                val action = if (dir == Swipe.RIGHT) Action.NEXT_SLIDE else Action.PREV_SLIDE
                if (pointer.value != PointerMode.OFF) motion.freeze(ts + FREEZE_ROLL_NS)
                if (s.presHaptics) haptic(20)
                lastGesture.value = GestureEvent(Gesture.TAP, action, System.currentTimeMillis())
                pres(if (dir == Swipe.RIGHT) "next" else "prev")
            } else if (swipe.consumeExpired(ts)) {
                showToast("Toque y desliza → siguiente · ← anterior")
            }
        }
        if (pointer.value == PointerMode.LASER && motion.omega.norm() > 0.3f) laserActiveMs = SystemClock.elapsedRealtime()
    }

    fun cycleProfile() {
        val next = Profiles.next(settings.value.presProfile)
        update { it.copy(presProfile = next) }
        link.sendCtrl(JSONObject().put("t", "profile").put("name", next))
    }

    fun setSlidesTotal(n: Int?) {
        link.sendCtrl(JSONObject().put("t", "slides_total").put("n", n ?: JSONObject.NULL))
    }

    private fun onServerMessage(m: JSONObject) {
        when (m.optString("t")) {
            "slide" -> slide.value = SlideInfo(
                cur = if (m.isNull("cur")) null else m.optInt("cur"),
                total = if (m.isNull("total")) null else m.optInt("total"),
                notes = m.optString("notes", ""),
                exact = m.optBoolean("exact", false),
            )
            "profile" -> profile.value = ProfileInfo(
                m.optString("name"), m.optString("label"), m.optBoolean("auto", true), m.optString("note", ""),
            )
            "pres_ack" -> if (!m.optBoolean("ok", true)) showToast(m.optString("why", "No disponible"))
        }
    }

    private fun showToast(msg: String) { toast.value = msg to SystemClock.elapsedRealtime() }

    // ------------------------------------------------------------ temporizador
    private fun publishTimer() {
        val now = SystemClock.elapsedRealtime()
        timerUi.value = TimerUi(timer.running, timer.elapsed(now), timer.remaining(now), timer.totalMs)
    }

    fun timerStartPause() { timer.toggle(SystemClock.elapsedRealtime()); publishTimer(); haptic(25) }
    fun timerReset() { timer.reset(); publishTimer(); haptic(40) }

    fun setPresMinutes(min: Int) {
        val m = min.coerceIn(1, 240)
        update { it.copy(presMinutes = m) }
        timer.setTotal(m * PresentationTimer.MIN, SystemClock.elapsedRealtime())
        publishTimer()
    }

    private fun timerHaptic(a: TimerAlert) {
        vibratePattern(
            when (a) {
                TimerAlert.HALF -> longArrayOf(0, 200, 150, 200)
                TimerAlert.MIN5 -> longArrayOf(0, 600)
                TimerAlert.MIN2 -> longArrayOf(0, 150, 100, 150)
                TimerAlert.MIN1 -> longArrayOf(0, 150, 100, 150, 100, 150)
                TimerAlert.END -> longArrayOf(0, 900, 200, 900)
            }
        )
    }

    private fun vibratePattern(p: LongArray) {
        val vib = app.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vib.vibrate(VibrationEffect.createWaveform(p, -1))
    }

    // ------------------------------------------------------------ envio periodico (60-100 Hz)
    private fun startSender() {
        senderRun = true
        sender = Thread({
            val out = IntArray(2)
            var next = System.nanoTime()
            var lastLoop = next
            while (senderRun) {
                if (link.state.value.phase != Phase.CONNECTED || wizardOpen()) {
                    motion.drain(out)                       // descarta lo acumulado sin conexion
                    if (dragging.value) dragging.value = false
                    if (pointer.value != PointerMode.OFF) pointer.value = PointerMode.OFF
                    LockSupport.parkNanos(100_000_000L)
                    next = System.nanoTime(); lastLoop = next
                    continue
                }
                val hz = settings.value.sendHz.coerceIn(30, 200)
                next += 1_000_000_000L / hz
                val now = System.nanoTime()
                val dt = (now - lastLoop) / 1e9f
                lastLoop = now

                motion.drain(out)
                // En presentacion el mouse real no se mueve: el movimiento solo viaja si el laser o el marcador estan activos.
                val sendMoves = !presenting.value || pointer.value != PointerMode.OFF
                if (sendMoves && (out[0] != 0 || out[1] != 0)) {
                    touch()
                    val dx = out[0].coerceIn(-32768, 32767)
                    val dy = out[1].coerceIn(-32768, 32767)
                    history.add(now, dx, dy)
                    link.sendMove(dx, dy)
                }

                // Scroll por inclinacion sostenida: tasa (muescas/s) integrada aqui
                val rate = if (presenting.value) 0f else scrollPose.rate
                if (rate != 0f) {
                    scrollRateAcc += rate * dt
                    val n = scrollRateAcc.toInt()
                    if (n != 0) {
                        scrollRateAcc -= n
                        touch()
                        link.sendCtrl(JSONObject().put("t", "scroll").put("h", 0).put("v", -n))   // v>0 = rueda arriba
                    }
                } else scrollRateAcc = 0f

                if (System.currentTimeMillis() - lastActivityMs > AUTO_OFF_MS) {
                    senderRun = false
                    app.stopService(Intent(app, AirMouseService::class.java))   // apagado por inactividad
                    break
                }
                val wait = next - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait) else next = System.nanoTime()
            }
        }, "airwatch-sender").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun touch() { lastActivityMs = System.currentTimeMillis() }

    // ------------------------------------------------------------ acciones de la UI
    fun button(name: String, state: String) {
        touch()
        link.sendCtrl(JSONObject().put("t", "btn").put("b", name).put("s", state))
    }

    /** `deltaPx` del bisel/corona; ~40 px = una muesca de rueda en el PC. */
    fun scroll(deltaPx: Float) {
        scrollAcc += deltaPx
        val notches = (scrollAcc / 40f).toInt()
        if (notches != 0) {
            scrollAcc -= notches * 40f
            touch()
            link.sendCtrl(JSONObject().put("t", "scroll").put("h", 0).put("v", -notches))
        }
    }

    fun recenter() {
        touch()
        motion.recenter()
        scrollPose.setNeutralFromCurrent()
        link.sendCtrl(JSONObject().put("t", "recenter"))
    }

    fun toggleTracking() {
        val on = !tracking.value
        tracking.value = on
        motion.tracking = on
        if (on) motion.recenter()
        haptic(if (on) 30 else 60)
    }

    fun haptic(ms: Long = 15) {
        val vib = app.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vib.vibrate(VibrationEffect.createOneShot(ms, 120))
    }
}
