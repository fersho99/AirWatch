package com.airwatch.wear

import kotlin.math.abs
import kotlin.math.max

// Logica pura (sin Android) del modo presentacion: se prueba en la JVM con run-logic-tests.sh.

/** Diapositiva actual segun la PC. `exact` = leida de PowerPoint; si no, es una estimacion por conteo (se muestra con ~). */
data class SlideInfo(val cur: Int?, val total: Int?, val notes: String, val exact: Boolean) {
    fun label(): String {
        val c = cur?.toString() ?: "–"
        val t = total?.toString() ?: "?"
        return (if (exact || cur == null) "" else "~") + "$c/$t"
    }
}

/** Perfil de aplicacion elegido en la PC (automatico por la ventana en primer plano, o fijo). */
data class ProfileInfo(val name: String, val label: String, val auto: Boolean, val note: String = "")

enum class PointerMode(val wire: String) { OFF("off"), LASER("laser") }

/** Perfiles que entiende la PC (el orden es el del ciclo en el reloj). */
object Profiles {
    val all = listOf(
        "auto" to "Automático",
        "powerpoint" to "PowerPoint",
        "gslides" to "Google Slides",
        "impress" to "LibreOffice Impress",
        "acrobat" to "Acrobat / Reader",
        "sumatra" to "SumatraPDF",
        "pdf_browser" to "PDF en navegador",
        "keynote" to "Keynote (exp.)",
        "generic" to "Genérico",
    )

    fun next(current: String): String {
        val i = all.indexOfFirst { it.first == current }
        return all[(i + 1).mod(all.size)].first
    }

    fun label(name: String) = all.firstOrNull { it.first == name }?.second ?: name
}

enum class TimerAlert { HALF, MIN5, MIN2, MIN1, END }

/**
 * Cronometro de la presentacion con alertas a la mitad y a 5, 2 y 1 minuto del final.
 * No lee el reloj por su cuenta: todo recibe `now` (ms), asi se prueba sin esperar.
 * Las alertas que no tienen sentido se omiten (p. ej. «5 min» en una charla de 4 min, o si coincide con la mitad).
 */
class PresentationTimer(totalMs: Long) {
    companion object {
        const val MIN = 60_000L
        private const val DEDUP_MS = 5_000L
    }

    var totalMs: Long = totalMs
        private set
    var running = false
        private set
    private var startedAt = 0L
    private var accumulated = 0L
    private val fired = HashSet<TimerAlert>()

    fun elapsed(now: Long): Long = accumulated + if (running) now - startedAt else 0L
    fun remaining(now: Long): Long = totalMs - elapsed(now)

    fun start(now: Long) {
        if (running) return
        running = true; startedAt = now
    }

    fun pause(now: Long) {
        if (!running) return
        accumulated += now - startedAt
        running = false
    }

    fun toggle(now: Long) = if (running) pause(now) else start(now)

    fun reset() {
        running = false; accumulated = 0L; startedAt = 0L; fired.clear()
    }

    /** Cambia la duracion; las alertas que ya quedaron atras no suenan retroactivamente. */
    fun setTotal(ms: Long, now: Long) {
        totalMs = ms
        val el = elapsed(now)
        fired.clear()
        for ((alert, t) in schedule()) if (t <= el) fired.add(alert)
    }

    private fun schedule(): List<Pair<TimerAlert, Long>> {
        val half = totalMs / 2
        val out = ArrayList<Pair<TimerAlert, Long>>()
        out += TimerAlert.HALF to half
        for ((a, before) in listOf(TimerAlert.MIN5 to 5 * MIN, TimerAlert.MIN2 to 2 * MIN, TimerAlert.MIN1 to MIN)) {
            val t = totalMs - before
            if (t > 0 && abs(t - half) >= DEDUP_MS) out += a to t
        }
        out += TimerAlert.END to totalMs
        return out
    }

    /** Alertas que se cruzaron desde la ultima llamada (cada una suena una sola vez). */
    fun tick(now: Long): List<TimerAlert> {
        if (!running) return emptyList()
        val el = elapsed(now)
        val due = schedule().filter { (a, t) -> el >= t && a !in fired }.sortedBy { it.second }
        due.forEach { fired.add(it.first) }
        return due.map { it.first }
    }
}

/** Texto mm:ss (o h:mm:ss); con signo menos cuando ya se paso del tiempo. */
fun formatClock(ms: Long): String {
    val neg = ms < 0
    val s = abs(ms) / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    val body = if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    return if (neg) "-$body" else body
}

/** Confirmacion por doble accion: la primera solo «arma»; la segunda dentro de la ventana ejecuta. */
class ConfirmGate(private val windowMs: Long = 2500L) {
    private var armedAt = -1L
    fun isArmed(now: Long) = armedAt >= 0 && now - armedAt <= windowMs
    fun request(now: Long): Boolean {
        if (isArmed(now)) { armedAt = -1L; return true }
        armedAt = now
        return false
    }
    fun reset() { armedAt = -1L }
}

/** Convierte desplazamiento de bisel/corona (px) en pasos enteros, conservando el resto. */
class RotaryStepper(private val pxPerStep: Float = 40f) {
    private var acc = 0f
    fun add(px: Float): Int {
        acc += px
        val n = (acc / pxPerStep).toInt()
        acc -= n * pxPerStep
        return n
    }
    fun reset() { acc = 0f }
}

/** Limita el numero de diapositiva a 1..total (si el total se conoce) o a 1..999. */
fun clampSlide(n: Int, total: Int?): Int = n.coerceIn(1, max(1, total ?: 999))
