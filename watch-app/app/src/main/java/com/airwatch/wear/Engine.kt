package com.airwatch.wear

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.locks.LockSupport

/**
 * Motor del air mouse: une sensores -> MotionProcessor -> hilo de envio -> WifiLink.
 * Vive en el proceso (no en la actividad); AirMouseService solo lo mantiene vivo en primer plano.
 */
object Engine {
    private const val SLOW_PERIOD_US = 50_000     // 20 Hz en reposo
    private const val FAST_PERIOD_US = 10_000     // 100 Hz en uso
    private const val IDLE_TO_SLOW_NS = 5_000_000_000L
    private const val AUTO_OFF_MS = 10 * 60 * 1000L

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
    val motion = MotionProcessor { settings.value }

    private var sm: SensorManager? = null
    private var gyro: Sensor? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var fastRate = true

    @Volatile private var senderRun = false
    private var sender: Thread? = null
    @Volatile private var lastActivityMs = 0L
    private var scrollAcc = 0f

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = Prefs(app)
        store = PairingStore(app)
        settings.value = prefs.load()
        link = WifiLink(app, scope, store, prefs)
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
        motion.reset()
        startSensors()
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
        stopSensors()
        link.stop()
    }

    // ------------------------------------------------------------ sensores
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            motion.onGyro(e.values[0], e.values[1], e.values[2], e.timestamp)
            val idleFor = e.timestamp - motion.lastActiveNs
            if (fastRate && idleFor > IDLE_TO_SLOW_NS) setSensorRate(fast = false)
            else if (!fastRate && idleFor < 1_000_000_000L) setSensorRate(fast = true)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startSensors() {
        val mgr = app.getSystemService(SensorManager::class.java)
        sm = mgr
        gyro = mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val t = HandlerThread("airwatch-sensors", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
        sensorThread = t
        sensorHandler = Handler(t.looper)
        fastRate = true
        mgr.registerListener(gyroListener, gyro, FAST_PERIOD_US, sensorHandler)
    }

    private fun setSensorRate(fast: Boolean) {
        val mgr = sm ?: return
        fastRate = fast
        mgr.unregisterListener(gyroListener)
        mgr.registerListener(gyroListener, gyro, if (fast) FAST_PERIOD_US else SLOW_PERIOD_US, sensorHandler)
    }

    private fun stopSensors() {
        sm?.unregisterListener(gyroListener)
        sensorThread?.quitSafely()
        sensorThread = null; sensorHandler = null; sm = null
    }

    // ------------------------------------------------------------ envio periodico (60-100 Hz)
    private fun startSender() {
        senderRun = true
        sender = Thread({
            val out = IntArray(2)
            var next = System.nanoTime()
            while (senderRun) {
                if (link.state.value.phase != Phase.CONNECTED) {
                    motion.drain(out)                       // descarta lo acumulado sin conexion
                    LockSupport.parkNanos(100_000_000L)
                    next = System.nanoTime()
                    continue
                }
                val hz = settings.value.sendHz.coerceIn(30, 200)
                next += 1_000_000_000L / hz
                motion.drain(out)
                if (out[0] != 0 || out[1] != 0) {
                    touch()
                    link.sendMove(out[0].coerceIn(-32768, 32767), out[1].coerceIn(-32768, 32767))
                }
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
