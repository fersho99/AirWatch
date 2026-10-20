package com.airwatch.wear

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

// Logica pura (sin Android): la usa Engine con los sensores reales y los tests con senales sinteticas.

enum class Step { WRIST, RIGHT, DOWN, ROLL, TRAIN_REST, TRAIN_TAP, TRAIN_ROLL_OUT, TRAIN_ROLL_IN, SUMMARY }

data class WizardState(
    val step: Step = Step.WRIST,
    val stepIndex: Int = 1,
    val stepCount: Int = 8,
    val message: String = "",
    val progress: String = "",
    val warning: String? = null,
    val summary: List<String>? = null,
    val canSkip: Boolean = false,
)

data class CalResult(
    val wrist: Wrist,
    val right: Vec3,
    val down: Vec3,
    val roll: Vec3,
    val tapThreshold: Float?,
    val rollThreshold: Float?,
    val tapQuality: Quality?,
    val rollQuality: Quality?,
)

/**
 * Detecta un "trazo": un movimiento rapido de la mano (> startRadS) que termina cuando la velocidad angular
 * baja de endRadS durante 120 ms. Devuelve la rotacion integrada (rad) del trazo en el marco del reloj, cuya
 * direccion es el eje de ese movimiento. Los regresos lentos (< startRadS) no cuentan como trazo.
 */
class StrokeRecorder(
    private val startRadS: Float = 1.0f,
    private val endRadS: Float = 0.4f,
    private val endHoldNs: Long = 120_000_000L,
    private val minAngleRad: Float = 0.35f,
    private val maxDurNs: Long = 2_000_000_000L,
) {
    private var bias = Vec3.ZERO
    private var haveBias = false
    private var active = false
    private var sum = Vec3.ZERO
    private var startTs = 0L
    private var belowSince = 0L
    private var lastTs = 0L

    fun reset() { active = false; sum = Vec3.ZERO; belowSince = 0L }

    fun feed(ts: Long, w: Vec3): Vec3? {
        val dt = if (lastTs == 0L) 0f else ((ts - lastTs) / 1e9f).coerceIn(0f, 0.05f)
        lastTs = ts
        if (!haveBias) { bias = w; haveBias = true }
        val c = w - bias
        val speed = c.norm()
        if (!active) {
            if (speed < startRadS) { bias = bias + (w - bias) * 0.02f; return null }
            active = true; sum = Vec3.ZERO; startTs = ts; belowSince = 0L
        }
        sum = sum + c * dt
        if (ts - startTs > maxDurNs) { active = false; sum = Vec3.ZERO; return null }
        if (speed < endRadS) {
            if (belowSince == 0L) belowSince = ts
            if (ts - belowSince >= endHoldNs) {
                active = false
                val r = sum
                sum = Vec3.ZERO
                return if (r.norm() >= minAngleRad) r else null
            }
        } else belowSince = 0L
        return null
    }
}

/**
 * Asistente de configuracion guiada. Pasos:
 *  1 muneca (izquierda/derecha)         2 eje "mano a la derecha"      3 eje "mano hacia abajo"
 *  4 eje de giro del antebrazo (roll)   5 movimiento libre sin gestos  6 entrenar toque/pellizco
 *  7 entrenar giro afuera               8 entrenar giro adentro        -> resumen
 *
 * Los ejes se MIDEN (direccion del giroscopio durante un trazo guiado, 2 repeticiones que deben coincidir),
 * en vez de suponerlos: el cursor queda bien sin importar la muneca ni como este puesto el reloj.
 */
class Calibrator(
    private val onChange: (WizardState) -> Unit,
    private val onBuzz: (Int) -> Unit = {},
) {
    companion object {
        const val ARM_NS = 1_000_000_000L           // tras cada paso se ignora 1 s (el toque al boton no cuenta)
        const val LOCK_NS = 1_200_000_000L          // tras un trazo se ignora el regreso de la mano
        const val ATTEMPTS = 2
        const val TRAIN_COUNT = 5
        const val REST_NS = 5_000_000_000L
        const val TAP_FLOOR_MIN = 1.0f              // m/s2: por debajo es temblor, no un gesto
        const val ROLL_FLOOR_MIN = 2.0f             // rad/s
        const val FLOOR_MARGIN = 1.3f               // el piso es 1.3x lo mayor visto en el movimiento libre
        const val STALL_NS = 12_000_000_000L
        const val TAP_WINDOW_NS = 120_000_000L
        const val ROLL_WINDOW_NS = 200_000_000L
        const val TAP_LOCK_NS = 350_000_000L
        const val ROLL_LOCK_NS = 500_000_000L
        const val SAME_DIR_COS = 0.8f
    }

    var step: Step = Step.WRIST
        private set

    private var wrist = Wrist.LEFT
    private var right: Vec3? = null
    private var down: Vec3? = null
    private var roll: Vec3? = null

    private val recorder = StrokeRecorder()
    private val hp = AccelHighPass(5f)
    private var armUntil = 0L
    private var lockUntil = 0L
    private val attempts = ArrayList<Vec3>()
    private var warning: String? = null

    private var restStart = 0L
    private var restTapMax = 0f
    private var restRollMax = 0f
    private var lastRestSec = -1
    private val tapPeaks = ArrayList<Float>()
    private val rollOutPeaks = ArrayList<Float>()
    private val rollInPeaks = ArrayList<Float>()
    private var inPeak = false
    private var peak = 0f
    private var peakEnd = 0L

    private var summaryLines: List<String>? = null
    private var result: CalResult? = null

    init { publish() }

    // ------------------------------------------------------------------ control desde la UI
    fun restart() {
        right = null; down = null; roll = null; result = null
        tapPeaks.clear(); rollOutPeaks.clear(); rollInPeaks.clear()
        restTapMax = 0f; restRollMax = 0f
        enter(Step.WRIST)
    }

    fun setWrist(w: Wrist) {
        if (step != Step.WRIST) return
        wrist = w
        enter(Step.RIGHT)
    }

    /** Repite el paso actual desde cero. */
    fun retryStep() {
        if (step != Step.WRIST && step != Step.SUMMARY) enter(step)
    }

    /** Salta un paso de entrenamiento (se quedan los valores por defecto de ese gesto). */
    fun skip() {
        when (step) {
            Step.TRAIN_REST -> { restTapMax = 0f; restRollMax = 0f; enter(Step.TRAIN_TAP) }
            Step.TRAIN_TAP -> { tapPeaks.clear(); enter(Step.TRAIN_ROLL_OUT) }
            Step.TRAIN_ROLL_OUT -> { rollOutPeaks.clear(); enter(Step.TRAIN_ROLL_IN) }
            Step.TRAIN_ROLL_IN -> { rollInPeaks.clear(); enter(Step.SUMMARY) }
            else -> {}
        }
    }

    fun result(): CalResult? = result

    /** Valores medidos (solo para diagnostico y pruebas). */
    fun debugInfo(): String =
        "restTapMax=%.2f restRollMax=%.2f tapPeaks=%s rollOut=%s rollIn=%s".format(
            restTapMax, restRollMax, tapPeaks.map { "%.2f".format(it) }, rollOutPeaks.map { "%.2f".format(it) },
            rollInPeaks.map { "%.2f".format(it) },
        )

    // ------------------------------------------------------------------ senales
    fun feedGyro(ts: Long, gx: Float, gy: Float, gz: Float) {
        if (armUntil == 0L) armUntil = ts + ARM_NS
        checkStall(ts)
        val w = Vec3(gx, gy, gz)
        when (step) {
            Step.RIGHT, Step.DOWN, Step.ROLL -> feedStroke(ts, w)
            Step.TRAIN_REST -> {
                recorder.feed(ts, w)
                if (ts >= armUntil) {
                    if (restStart == 0L) restStart = ts
                    restRollMax = max(restRollMax, abs(w dot roll!!))
                    val remain = ceil(((REST_NS - (ts - restStart)) / 1e9f).toDouble()).toInt()
                    if (ts - restStart >= REST_NS) enter(Step.TRAIN_TAP)
                    else if (remain != lastRestSec) { lastRestSec = remain; publish() }
                }
            }
            Step.TRAIN_ROLL_OUT -> trainRoll(ts, w dot roll!!, rollOutPeaks, Step.TRAIN_ROLL_IN)
            Step.TRAIN_ROLL_IN -> trainRoll(ts, -(w dot roll!!), rollInPeaks, Step.SUMMARY)
            else -> {}
        }
    }

    fun feedAccel(ts: Long, ax: Float, ay: Float, az: Float) {
        if (armUntil == 0L) armUntil = ts + ARM_NS
        val m = hp.update(ts, Vec3(ax, ay, az))
        when (step) {
            Step.TRAIN_REST -> if (restStart != 0L) restTapMax = max(restTapMax, m)
            Step.TRAIN_TAP -> {
                if (ts < armUntil) return
                if (inPeak) {
                    peak = max(peak, m)
                    if (ts >= peakEnd) {
                        inPeak = false
                        lockUntil = ts + TAP_LOCK_NS
                        tapPeaks.add(peak)
                        warning = null
                        onBuzz(25)
                        if (tapPeaks.size >= TRAIN_COUNT) enter(Step.TRAIN_ROLL_OUT) else publish()
                    }
                } else if (ts >= lockUntil && m > tapFloor()) {
                    inPeak = true; peak = m; peakEnd = ts + TAP_WINDOW_NS
                }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ pasos
    private fun tapFloor() = max(TAP_FLOOR_MIN, restTapMax * FLOOR_MARGIN)
    private fun rollFloor() = max(ROLL_FLOOR_MIN, restRollMax * FLOOR_MARGIN)

    /** Si tras 12 s no se detecto ningun intento, sugiere hacerlo mas marcado o saltar el paso. */
    private fun checkStall(ts: Long) {
        if (warning != null || armUntil == 0L || ts - armUntil < STALL_NS) return
        val stalled = when (step) {
            Step.TRAIN_TAP -> tapPeaks.isEmpty()
            Step.TRAIN_ROLL_OUT -> rollOutPeaks.isEmpty()
            Step.TRAIN_ROLL_IN -> rollInPeaks.isEmpty()
            else -> false
        }
        if (stalled) {
            warning = "No detecto el gesto. Hazlo más marcado y rápido, o salta este paso."
            publish()
        }
    }

    private fun feedStroke(ts: Long, w: Vec3) {
        val r = recorder.feed(ts, w) ?: return
        if (ts < armUntil || ts < lockUntil) return
        lockUntil = ts + LOCK_NS
        val v = r.unit()
        if (attempts.isNotEmpty() && (attempts[0] dot v) < SAME_DIR_COS) {
            attempts.clear()
            warning = "No coincidió con el primero. Repite desde el inicio."
            onBuzz(80); publish(); return
        }
        attempts.add(v)
        warning = null
        onBuzz(25)
        if (attempts.size < ATTEMPTS) { publish(); return }
        val axis = attempts.fold(Vec3.ZERO) { acc, a -> acc + a }.unit()
        attempts.clear()
        when (step) {
            Step.RIGHT -> { right = axis; enter(Step.DOWN) }
            Step.DOWN -> {
                val r0 = right!!
                if (abs(r0 dot axis) > 0.5f) {
                    warning = "Se parece al movimiento a la derecha. Mueve la mano hacia ABAJO."
                    publish(); return
                }
                down = (axis - r0 * (axis dot r0)).unit()
                enter(Step.ROLL)
            }
            Step.ROLL -> {
                if (abs(right!! dot axis) > 0.7f || abs(down!! dot axis) > 0.7f) {
                    warning = "Gira el antebrazo como abriendo una perilla, sin mover la mano a los lados."
                    publish(); return
                }
                roll = axis
                enter(Step.TRAIN_REST)
            }
            else -> {}
        }
    }

    private fun trainRoll(ts: Long, rho: Float, store: ArrayList<Float>, next: Step) {
        if (ts < armUntil) return
        if (inPeak) {
            peak = max(peak, rho)
            if (ts >= peakEnd) {
                inPeak = false
                lockUntil = ts + ROLL_LOCK_NS
                store.add(peak)
                warning = null
                onBuzz(25)
                if (store.size >= TRAIN_COUNT) enter(next) else publish()
            }
        } else if (ts >= lockUntil && rho > rollFloor()) {
            inPeak = true; peak = rho; peakEnd = ts + ROLL_WINDOW_NS
        }
    }

    private fun enter(s: Step) {
        step = s
        warning = null; armUntil = 0L; lockUntil = 0L; attempts.clear(); inPeak = false
        restStart = 0L; lastRestSec = -1
        recorder.reset()
        when (s) {
            Step.TRAIN_REST -> { restTapMax = 0f; restRollMax = 0f }
            Step.TRAIN_TAP -> tapPeaks.clear()
            Step.TRAIN_ROLL_OUT -> rollOutPeaks.clear()
            Step.TRAIN_ROLL_IN -> rollInPeaks.clear()
            Step.SUMMARY -> buildSummary()
            else -> {}
        }
        publish()
    }

    private fun buildSummary() {
        val lines = ArrayList<String>()
        lines += "Muñeca: ${wrist.label}"
        lines += "Ejes de movimiento: medidos ✓"

        var tapThr: Float? = null; var tapQ: Quality? = null
        if (tapPeaks.size >= 3) {
            val (t, q) = ThresholdFit.fit(tapPeaks, restTapMax, 0.8f, 6f)
            tapThr = t; tapQ = q
            lines += "Toque: umbral ${"%.1f".format(t)} (${q.es()})"
        } else lines += "Toque: sin entrenar (valor por defecto)"

        val rollPeaks = rollOutPeaks + rollInPeaks
        var rollThr: Float? = null; var rollQ: Quality? = null
        if (rollPeaks.size >= 3) {
            val (t, q) = ThresholdFit.fit(rollPeaks, restRollMax, 4f, 12f)
            rollThr = t; rollQ = q
            lines += "Giro: umbral ${"%.1f".format(t)} (${q.es()})"
        } else lines += "Giro: sin entrenar (valor por defecto)"

        if (tapQ == Quality.POOR || rollQ == Quality.POOR) {
            lines += "Poca separación entre gesto y movimiento normal: usa los botones táctiles o ajusta el umbral."
        }
        summaryLines = lines
        result = CalResult(wrist, right!!, down!!, roll!!, tapThr, rollThr, tapQ, rollQ)
    }

    private fun Quality.es() = when (this) { Quality.GOOD -> "bueno"; Quality.FAIR -> "regular"; Quality.POOR -> "bajo" }

    // ------------------------------------------------------------------ estado para la UI
    private fun publish() {
        val index = when (step) {
            Step.WRIST -> 1; Step.RIGHT -> 2; Step.DOWN -> 3; Step.ROLL -> 4
            Step.TRAIN_REST -> 5; Step.TRAIN_TAP -> 6; Step.TRAIN_ROLL_OUT -> 7
            Step.TRAIN_ROLL_IN, Step.SUMMARY -> 8
        }
        val message = when (step) {
            Step.WRIST -> "¿En qué muñeca llevas el reloj?"
            Step.RIGHT -> "Mueve la mano a la DERECHA, rápido. Regrésala despacio."
            Step.DOWN -> "Ahora mueve la mano hacia ABAJO, rápido. Regrésala despacio."
            Step.ROLL -> "Gira el antebrazo para poner la palma ARRIBA, rápido. Regrésalo despacio."
            Step.TRAIN_REST -> "Mueve la mano como apuntando. SIN hacer gestos."
            Step.TRAIN_TAP -> "Haz tu gesto de clic: toque o pellizco."
            Step.TRAIN_ROLL_OUT -> "Gira rápido hacia PALMA ARRIBA y regrésalo."
            Step.TRAIN_ROLL_IN -> "Gira rápido hacia PALMA ABAJO (clic derecho) y regrésalo."
            Step.SUMMARY -> "Listo. Resumen:"
        }
        val progress = when (step) {
            Step.RIGHT, Step.DOWN, Step.ROLL -> "${attempts.size}/$ATTEMPTS"
            Step.TRAIN_REST -> if (lastRestSec >= 0) "$lastRestSec s" else "5 s"
            Step.TRAIN_TAP -> "${tapPeaks.size}/$TRAIN_COUNT"
            Step.TRAIN_ROLL_OUT -> "${rollOutPeaks.size}/$TRAIN_COUNT"
            Step.TRAIN_ROLL_IN -> "${rollInPeaks.size}/$TRAIN_COUNT"
            else -> ""
        }
        val canSkip = step == Step.TRAIN_REST || step == Step.TRAIN_TAP ||
            step == Step.TRAIN_ROLL_OUT || step == Step.TRAIN_ROLL_IN
        onChange(
            WizardState(
                step = step, stepIndex = index, stepCount = 8, message = message, progress = progress,
                warning = warning, summary = if (step == Step.SUMMARY) summaryLines else null, canSkip = canSkip,
            )
        )
    }
}
