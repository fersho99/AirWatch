package com.airwatch.wear

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

// Logica pura (sin Android) para poder probarla en la JVM: ver watch-app/logic-tests.

/** Magnitud del acelerometro filtrado pasa-altas: responde a impulsos (toques) e ignora la gravedad y el movimiento lento. */
class AccelHighPass(private val cutoffHz: Float = 5f) {
    private var lp = Vec3.ZERO
    private var have = false
    private var lastTs = 0L

    fun reset() { have = false; lastTs = 0L }

    fun update(ts: Long, a: Vec3): Float {
        if (!have) { lp = a; have = true; lastTs = ts; return 0f }
        val dt = ((ts - lastTs) / 1e9f).coerceIn(0.002f, 0.05f)
        lastTs = ts
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val alpha = dt / (rc + dt)
        val hp = a - lp
        lp = lp + (a - lp) * alpha
        return hp.norm()
    }
}

data class GestureConfig(val tapThreshold: Float, val rollThreshold: Float, val rollAxis: Vec3)

/**
 * Detector de gestos de la muneca.
 *
 *  TAP (toque / pellizco): impulso breve en el acelerometro (pasa-altas 5 Hz) por encima de `tapThreshold`,
 *      siempre que el reloj NO este girando rapido (si lo esta, es un movimiento, no un toque).
 *  ROLL_OUT / ROLL_IN: giro rapido del antebrazo (velocidad angular proyectada sobre el eje de roll
 *      calibrado) por encima de `rollThreshold` y dominante sobre el resto del movimiento. Tras un giro
 *      se ignora el de sentido contrario durante 450 ms (es el regreso de la muneca).
 */
class GestureDetector(private val cfg: () -> GestureConfig) {
    companion object {
        const val WARMUP_NS = 400_000_000L
        const val TAP_REFRACTORY_NS = 250_000_000L
        const val ROLL_REFRACTORY_NS = 450_000_000L
        const val GYRO_REJECT_RAD_S = 3.0f          // un toque real casi no gira el reloj
        const val GYRO_REJECT_WINDOW_NS = 80_000_000L
        const val TAP_CONFIRM_NS = 60_000_000L      // espera para confirmar que no era el arranque de un giro
        const val TAP_GYRO_CHANGE_RAD_S = 1.0f      // si el vector de giro cambia mas que esto tras el impulso, era un movimiento
        const val ROLL_DOMINANCE = 0.7f             // el giro debe ser casi todo roll
    }

    private val hp = AccelHighPass(5f)
    private var first = 0L
    private var tapRefractoryUntil = 0L
    private var rollRefractoryUntil = 0L
    private var gyroPeak = 0f
    private var gyroPeakTs = 0L
    private var lastW = Vec3.ZERO
    private var tapPendingTs = 0L        // 0 = ningun toque por confirmar
    private var tapPendingBaseW = Vec3.ZERO   // vector de giro en el instante del impulso
    private var tapPendingPre = 0f       // pico de rapidez de giro justo antes del impulso
    private var tapPendingPost = 0f      // pico de rapidez de giro despues del impulso
    private var tapPendingChange = 0f    // mayor cambio del vector de giro despues del impulso

    fun reset() {
        hp.reset(); first = 0L; tapRefractoryUntil = 0L; rollRefractoryUntil = 0L; gyroPeak = 0f; gyroPeakTs = 0L
        lastW = Vec3.ZERO; tapPendingTs = 0L; tapPendingBaseW = Vec3.ZERO
        tapPendingPre = 0f; tapPendingPost = 0f; tapPendingChange = 0f
    }

    fun onGyro(ts: Long, gx: Float, gy: Float, gz: Float): Gesture? {
        if (first == 0L) first = ts
        val w = Vec3(gx, gy, gz)
        val speed = w.norm()
        if (speed >= gyroPeak || ts - gyroPeakTs > GYRO_REJECT_WINDOW_NS) { gyroPeak = speed; gyroPeakTs = ts }
        lastW = w
        if (tapPendingTs != 0L) {
            tapPendingPost = max(tapPendingPost, speed)
            tapPendingChange = max(tapPendingChange, (w - tapPendingBaseW).norm())
        }
        if (ts - first < WARMUP_NS || ts < rollRefractoryUntil) return null

        val c = cfg()
        val rho = w dot c.rollAxis
        if (abs(rho) > c.rollThreshold && abs(rho) > ROLL_DOMINANCE * speed) {
            rollRefractoryUntil = ts + ROLL_REFRACTORY_NS
            tapRefractoryUntil = max(tapRefractoryUntil, ts + ROLL_REFRACTORY_NS)  // el sacudon del giro no es un toque
            tapPendingTs = 0L                                                       // y cancela un toque pendiente
            return if (rho > 0) Gesture.ROLL_OUT else Gesture.ROLL_IN
        }
        return null
    }

    fun onAccel(ts: Long, ax: Float, ay: Float, az: Float): Gesture? {
        val mag = hp.update(ts, Vec3(ax, ay, az))
        if (first == 0L) first = ts

        // Confirmacion: el impulso solo cuenta como toque si durante los 40 ms siguientes el reloj no giro rapido.
        if (tapPendingTs != 0L && ts - tapPendingTs >= TAP_CONFIRM_NS) {
            val ok = max(tapPendingPre, tapPendingPost) < GYRO_REJECT_RAD_S && tapPendingChange < TAP_GYRO_CHANGE_RAD_S
            tapPendingTs = 0L
            if (ok) return Gesture.TAP
            return null
        }

        if (ts - first < WARMUP_NS || ts < tapRefractoryUntil || tapPendingTs != 0L) return null
        if (mag > cfg().tapThreshold) {
            tapRefractoryUntil = ts + TAP_REFRACTORY_NS
            tapPendingTs = ts
            tapPendingBaseW = lastW
            tapPendingPost = lastW.norm()
            tapPendingChange = 0f
            tapPendingPre = if (ts - gyroPeakTs <= GYRO_REJECT_WINDOW_NS) gyroPeak else 0f
        }
        return null
    }
}

/**
 * Historial de los movimientos de cursor ya enviados. Al detectar un gesto de clic se deshace lo enviado en
 * los ultimos `compMs` (el sacudon del gesto) para que el clic caiga donde el usuario apuntaba.
 */
class MoveHistory(private val capacity: Int = 64) {
    private val t = LongArray(capacity)
    private val dx = IntArray(capacity)
    private val dy = IntArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized fun add(tNs: Long, mx: Int, my: Int) {
        t[head] = tNs; dx[head] = mx; dy[head] = my
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** Suma de lo enviado desde `sinceNs` (inclusive). */
    @Synchronized fun sumSince(sinceNs: Long, out: IntArray) {
        var sx = 0; var sy = 0
        for (i in 0 until size) {
            val idx = (head - 1 - i + capacity) % capacity
            if (t[idx] < sinceNs) break
            sx += dx[idx]; sy += dy[idx]
        }
        out[0] = sx; out[1] = sy
    }

    @Synchronized fun clear() { head = 0; size = 0 }
}

/**
 * Convierte dos toques seguidos en DOUBLE_TAP. El primer toque se entrega de inmediato como TAP (sin retraso);
 * si el siguiente llega dentro de la ventana, se entrega solo como DOUBLE_TAP (no como un segundo TAP).
 */
class TapCombiner(private val windowNs: Long = 600_000_000L) {
    private var last = 0L
    fun reset() { last = 0L }
    fun onTap(ts: Long): Gesture {
        if (last != 0L && ts - last <= windowNs) { last = 0L; return Gesture.DOUBLE_TAP }
        last = ts
        return Gesture.TAP
    }
}

enum class Swipe { LEFT, RIGHT }

/**
 * Toque + deslizamiento (modo presentacion). Tras un TAP se abre una ventana de ~0.6 s; si en ella la mano se
 * desplaza en horizontal mas de `deg()` grados (velocidad angular integrada sobre los ejes calibrados), se
 * entrega RIGHT / LEFT. Un toque solo, o un movimiento solo, no hace nada: esa es la proteccion contra
 * cambios de diapositiva accidentales. Los primeros ms se ignoran (el sacudon del propio toque).
 */
class SwipeAfterTap(private val deg: () -> Float) {
    companion object {
        const val SKIP_NS = 60_000_000L
        const val WINDOW_NS = 600_000_000L
        const val HORIZONTAL_DOMINANCE = 1.5f      // el desplazamiento horizontal debe superar 1.5x al vertical
    }

    private var armedAt = 0L
    private var lastTs = 0L
    private var ax = 0f          // grados acumulados hacia la derecha (+) / izquierda (-)
    private var ay = 0f          // grados acumulados hacia abajo (+) / arriba (-)

    val armed: Boolean get() = armedAt != 0L

    fun arm(ts: Long) { armedAt = ts; lastTs = ts; ax = 0f; ay = 0f }
    fun reset() { armedAt = 0L; lastTs = 0L; ax = 0f; ay = 0f }

    /** `wx`: giro hacia la derecha (rad/s, ya con invertX aplicado); `wy`: giro hacia abajo (rad/s). */
    fun onGyro(ts: Long, wx: Float, wy: Float): Swipe? {
        if (armedAt == 0L) return null
        val dt = ((ts - lastTs) / 1e9f).coerceIn(0f, 0.05f)
        lastTs = ts
        if (ts - armedAt < SKIP_NS || ts - armedAt > WINDOW_NS) return null
        val k = (180.0 / PI).toFloat() * dt
        ax += wx * k; ay += wy * k
        if (abs(ax) >= deg() && abs(ax) >= HORIZONTAL_DOMINANCE * abs(ay)) {
            val s = if (ax > 0) Swipe.RIGHT else Swipe.LEFT
            reset()
            return s
        }
        return null
    }

    /** true una sola vez cuando la ventana se cierra sin deslizamiento (el toque quedo solo). */
    fun consumeExpired(ts: Long): Boolean {
        if (armedAt != 0L && ts - armedAt > WINDOW_NS) { reset(); return true }
        return false
    }
}

/** `palmUpOnly`: el scroll solo se activa con la palma hacia arriba (th < 0 en el marco del reloj). */
data class ScrollConfig(
    val rollAxis: Vec3, val downAxis: Vec3, val enterDeg: Float, val exitDeg: Float,
    val palmUpOnly: Boolean = true,
)

/**
 * Scroll por inclinacion sostenida. Si el antebrazo se mantiene girado mas de `enterDeg` respecto a la
 * postura neutra durante 450 ms (y casi quieto), se entra en modo scroll: el cursor se congela y subir o
 * bajar la muneca desplaza la pagina, mas rapido cuanto mas se incline (tipo joystick). Al volver a la
 * postura neutra (< `exitDeg`) se vuelve al cursor.
 */
class ScrollPose {
    enum class Transition { ENTER, EXIT }

    companion object {
        const val HOLD_NS = 450_000_000L
        const val STEADY_RAD_S = 1.5f
        const val GRAVITY_FC_HZ = 1.5f
        const val DEAD_RAD = 0.10f
        const val NOTCHES_PER_RAD = 30f
        const val MAX_PHI = 1.2f
        private const val AUTO_NEUTRAL_NS = 1_000_000_000L
    }

    /** Muescas por segundo; positivo = desplazar hacia abajo. 0 si no esta activo. */
    @Volatile var rate = 0f
        private set
    @Volatile var active = false
        private set
    /** Angulo actual de giro respecto a la postura neutra, en grados (solo informativo). */
    @Volatile var angleDeg = 0f
        private set

    private var neutral: Vec3? = null
    private var g = Vec3.ZERO
    private var haveG = false
    private var lastAccelTs = 0L
    private var enterSince = 0L
    private var steadySince = 0L
    private var lastGyroSpeed = 0f
    private var lastGyroTs = 0L
    private var phi = 0f

    fun reset() {
        neutral = null; haveG = false; lastAccelTs = 0L; enterSince = 0L; steadySince = 0L
        lastGyroSpeed = 0f; lastGyroTs = 0L; phi = 0f; rate = 0f; active = false; angleDeg = 0f
    }

    /** Toma la postura actual como neutra (boton "Centrar"). */
    fun setNeutralFromCurrent() { if (haveG) neutral = g }

    fun onGyro(ts: Long, w: Vec3, cfg: ScrollConfig) {
        val dt = if (lastGyroTs == 0L) 0.01f else ((ts - lastGyroTs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastGyroTs = ts
        lastGyroSpeed = w.norm()
        if (!active) { rate = 0f; return }
        phi = (phi + (w dot cfg.downAxis) * dt).coerceIn(-MAX_PHI, MAX_PHI)
        val excess = abs(phi) - DEAD_RAD
        rate = if (excess > 0f) (if (phi > 0) 1f else -1f) * excess * NOTCHES_PER_RAD else 0f
    }

    fun onAccel(ts: Long, a: Vec3, cfg: ScrollConfig): Transition? {
        val dt = if (lastAccelTs == 0L) 0.01f else ((ts - lastAccelTs) / 1e9f).coerceIn(0.002f, 0.05f)
        lastAccelTs = ts
        if (!haveG) { g = a; haveG = true } else {
            val rc = 1f / (2f * PI.toFloat() * GRAVITY_FC_HZ)
            g = g + (a - g) * (dt / (rc + dt))
        }

        val steady = lastGyroSpeed < STEADY_RAD_S
        var n = neutral
        if (n == null) {   // postura neutra automatica: la primera vez que el reloj queda quieto 1 s
            if (lastGyroSpeed < 0.5f) {
                if (steadySince == 0L) steadySince = ts
                if (ts - steadySince >= AUTO_NEUTRAL_NS) { neutral = g; n = g }
            } else steadySince = 0L
            if (n == null) return null
        }

        val o = cfg.rollAxis
        val p0 = n - o * (n dot o)
        val p = g - o * (g dot o)
        if (p0.norm() < 2.5f || p.norm() < 2.5f) return null   // eje de roll casi vertical: angulo indefinido
        val th = Math.toDegrees(atan2((o dot (p0 cross p)).toDouble(), (p0 dot p).toDouble())).toFloat()
        angleDeg = th
        // La gravedad vista desde el reloj gira al reves que el reloj: palma arriba => th negativo.
        val absDeg = if (cfg.palmUpOnly) -th else abs(th)

        if (!active) {
            if (absDeg > cfg.enterDeg && steady) {
                if (enterSince == 0L) enterSince = ts
                if (ts - enterSince >= HOLD_NS) {
                    active = true; phi = 0f; enterSince = 0L
                    return Transition.ENTER
                }
            } else enterSince = 0L
        } else if (absDeg < cfg.exitDeg) {
            active = false; enterSince = 0L; phi = 0f; rate = 0f
            return Transition.EXIT
        }
        return null
    }
}

/** Ajuste de umbrales a partir de los picos medidos al entrenar un gesto y del maximo observado sin gesto. */
enum class Quality { GOOD, FAIR, POOR }

object ThresholdFit {
    /** Devuelve (umbral, calidad). `restMax` = pico maximo observado moviendose sin hacer el gesto. */
    fun fit(peaks: List<Float>, restMax: Float, floor: Float, ceil: Float): Pair<Float, Quality> {
        require(peaks.isNotEmpty())
        val sorted = peaks.sorted()
        val lo = if (sorted.size >= 5) sorted[1] else sorted.first()   // con 5 muestras se descarta la mas baja
        val rest = restMax * 1.15f
        var thr: Float
        var q: Quality
        when {
            lo >= rest * 1.5f -> {
                thr = if (rest > 0f) kotlin.math.sqrt(lo * rest) else lo * 0.6f
                q = Quality.GOOD
            }
            lo > rest -> { thr = (lo + rest) / 2f; q = Quality.FAIR }
            else -> { thr = rest * 1.1f; q = Quality.POOR }
        }
        thr = thr.coerceIn(floor, ceil)
        val hit = peaks.count { it >= thr }.toFloat() / peaks.size
        if (hit < 0.8f) q = Quality.POOR
        return thr to q
    }
}
