package com.airwatch.wear

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Convierte velocidad angular del giroscopio (rad/s) en desplazamiento de cursor (px).
 *
 * Los ejes NO estan fijos: el asistente de configuracion mide en que direccion del marco del reloj
 * giran "mano a la derecha" ([Settings.axisRight]) y "mano hacia abajo" ([Settings.axisDown]) y
 * aqui se proyecta la velocidad angular sobre ellos. Asi funciona con cualquier muneca y postura.
 *
 * Pipeline: sesgo del giroscopio -> proyeccion a ejes -> zona muerta suave -> low-pass exponencial ->
 *           aceleracion -> acumulador de pixeles fraccionarios.
 */
class MotionProcessor(private val settings: () -> Settings) {
    companion object {
        const val BASE_PX_PER_RAD = 1200f
        private const val STILL_RAD_S = 0.05f
        private const val STILL_NS = 500_000_000L
        private const val ACCEL_REF_RAD_S = 2.0f
    }

    @Volatile var tracking = true

    /** true mientras el scroll por inclinacion esta activo: el cursor no se mueve. */
    @Volatile var cursorSuspended = false

    /** Velocidad angular con el sesgo ya restado (rad/s); la usan los demas detectores. */
    @Volatile var omega = Vec3.ZERO
        private set

    private val lock = Any()
    private var lastTs = 0L
    private var bx = 0f; private var by = 0f; private var bz = 0f
    private var stillSince = 0L
    private var fx = 0f; private var fy = 0f
    private var accX = 0f; private var accY = 0f
    private var freezeUntil = 0L

    /** Ultimo timestamp de sensor con movimiento intencional (0 = aun sin datos); sirve para bajar la frecuencia. */
    @Volatile var lastActiveNs = 0L
        private set

    fun reset() {
        synchronized(lock) {
            lastTs = 0L; fx = 0f; fy = 0f; accX = 0f; accY = 0f; stillSince = 0L; lastActiveNs = 0L
            freezeUntil = 0L
        }
    }

    /**
     * Congela el cursor hasta `untilTs` (nanosegundos en el dominio de los eventos de sensor) y descarta
     * lo acumulado: asi el sacudon de un gesto de clic no se convierte en movimiento del cursor.
     */
    fun freeze(untilTs: Long) {
        synchronized(lock) {
            freezeUntil = max(freezeUntil, untilTs)
            accX = 0f; accY = 0f; fx = 0f; fy = 0f
        }
    }

    /** Llamar desde el hilo de sensores. gx, gy, gz en rad/s; ts en nanosegundos del evento. */
    fun onGyro(gx: Float, gy: Float, gz: Float, ts: Long) {
        synchronized(lock) { process(gx, gy, gz, ts) }
    }

    private fun process(gx: Float, gy: Float, gz: Float, ts: Long) {
        val s = settings()
        val dt = if (lastTs == 0L) 0.01f else ((ts - lastTs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastTs = ts
        if (lastActiveNs == 0L) lastActiveNs = ts

        // 1) Sesgo: si el reloj esta quieto ~0.5 s, el promedio de la lectura es el sesgo.
        val still = abs(gx - bx) < STILL_RAD_S && abs(gy - by) < STILL_RAD_S && abs(gz - bz) < STILL_RAD_S
        if (still) {
            if (stillSince == 0L) stillSince = ts
            if (ts - stillSince > STILL_NS) {
                bx += 0.02f * (gx - bx); by += 0.02f * (gy - by); bz += 0.02f * (gz - bz)
            }
        } else {
            stillSince = 0L
        }
        val w = Vec3(gx - bx, gy - by, gz - bz)
        omega = w

        if (!tracking || cursorSuspended || ts < freezeUntil) {
            fx = 0f; fy = 0f; accX = 0f; accY = 0f
            return
        }

        // 2) Ejes calibrados -> pantalla
        var rx = w dot s.axisRight
        var ry = w dot s.axisDown
        if (s.invertX) rx = -rx
        if (s.invertY) ry = -ry

        // 3) Zona muerta suave sobre la magnitud (sin saltos al cruzar el umbral)
        val m = hypot(rx, ry)
        if (m < s.deadzone) { rx = 0f; ry = 0f } else {
            val k = (m - s.deadzone) / m
            rx *= k; ry *= k
        }

        // 4) Low-pass exponencial
        val alpha = 1f - s.smoothing.coerceIn(0f, 0.95f)
        fx += alpha * (rx - fx)
        fy += alpha * (ry - fy)

        val speed = hypot(fx, fy)
        if (speed > 0.25f) lastActiveNs = ts

        // 5) Aceleracion y conversion a pixeles
        val gain = s.sensitivity * BASE_PX_PER_RAD * (1f + s.accel * min(speed / ACCEL_REF_RAD_S, 3f))
        accX += fx * dt * gain
        accY += fy * dt * gain
    }

    /** Devuelve los pixeles enteros acumulados y conserva la parte fraccionaria. */
    fun drain(out: IntArray) {
        synchronized(lock) {
            val ix = accX.toInt(); val iy = accY.toInt()
            accX -= ix; accY -= iy
            out[0] = if (tracking && !cursorSuspended) ix else 0
            out[1] = if (tracking && !cursorSuspended) iy else 0
        }
    }

    fun recenter() {
        synchronized(lock) { accX = 0f; accY = 0f; fx = 0f; fy = 0f }
    }
}
