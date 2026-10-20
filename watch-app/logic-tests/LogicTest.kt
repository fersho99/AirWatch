package com.airwatch.wear

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.exitProcess

// Pruebas de la logica pura del reloj con senales sinteticas. Se compilan y ejecutan en la JVM:
//   ./run-logic-tests.sh
// No prueban sensores reales: validan los algoritmos, no sustituyen la calibracion en la muneca.

var failures = 0
fun check(cond: Boolean, msg: String) {
    println((if (cond) "  OK    " else "  FALLO ") + msg)
    if (!cond) failures++
}

fun angleDeg(a: Vec3, b: Vec3): Float {
    val c = ((a dot b) / (a.norm() * b.norm())).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(c.toDouble())).toFloat()
}

/** Marco anatomico: x = a lo largo del antebrazo (roll), y = mano abajo, z = arriba (la derecha es -z). */
object World {
    private val tilt = Math.toRadians(25.0)   // el reloj no esta perfectamente alineado con la muneca
    private fun rotX(v: Vec3): Vec3 {
        val c = cos(tilt).toFloat(); val s = sin(tilt).toFloat()
        return Vec3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }
    val roll = rotX(Vec3(1f, 0f, 0f))
    val down = rotX(Vec3(0f, 1f, 0f))
    val right = rotX(Vec3(0f, 0f, -1f))
    val gravity = rotX(Vec3(0f, 0f, 9.81f))
    /** Gravedad con el antebrazo girado `deg` grados alrededor del eje de roll. */
    fun gravityRolled(deg: Double): Vec3 {
        val a = Math.toRadians(deg)
        val g = gravity
        // rotacion de Rodrigues alrededor de `roll`
        val k = roll
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return g * c + (k cross g) * s + k * ((k dot g) * (1 - c))
    }
}

/** Reproduce senales a 100 Hz (giroscopio en t, acelerometro en t+2 ms) con ruido y sesgo. */
class Sim(
    private val onGyro: (Long, Vec3) -> Unit,
    private val onAccel: (Long, Vec3) -> Unit,
    private val gravity: (Double) -> Vec3 = { World.gravity },
) {
    var t = 0.0
    private val rnd = Random(7)
    private val bias = Vec3(0.012f, -0.02f, 0.015f)
    private fun noise(s: Float) = Vec3(
        (rnd.nextFloat() - 0.5f) * 2 * s, (rnd.nextFloat() - 0.5f) * 2 * s, (rnd.nextFloat() - 0.5f) * 2 * s,
    )

    /** `bg` = movimiento de fondo continuo (apuntar con temblor), en tiempo global para que no haya saltos entre tramos. */
    fun play(dur: Double, bg: Boolean = false, w: (Double) -> Vec3 = { Vec3.ZERO }, a: (Double) -> Vec3 = { Vec3.ZERO }) {
        val t0 = t
        val end = t + dur
        while (t < end - 1e-9) {
            val lt = t - t0
            val ta = t + 0.002
            onGyro((t * 1e9).toLong(), w(lt) + (if (bg) pointingW(t) else Vec3.ZERO) + bias + noise(0.02f))
            onAccel((ta * 1e9).toLong(), gravity(t) + a(lt + 0.002) + (if (bg) pointingA(ta) else Vec3.ZERO) + noise(0.08f))
            t += 0.01
        }
    }

    fun idle(dur: Double) = play(dur)
}

fun sin2(t: Double, dur: Double): Float = if (t < 0 || t > dur) 0f else sin(PI * t / dur).let { (it * it).toFloat() }

/** Trazo rapido (0.35 s, ~0.5 rad) seguido de un regreso lento (no cuenta como trazo). */
fun Sim.stroke(axis: Vec3, angle: Float = 0.5f) {
    val dur = 0.35
    val peak = angle * 2f / dur.toFloat()
    play(dur, w = { axis * (peak * sin2(it, dur)) })
    play(1.4, w = { axis * (-0.35f * sin2(it, 1.4)) })
}

/** Movimiento libre apuntando, con temblor de 9 Hz. */
fun pointingW(t: Double): Vec3 =
    World.right * (1.5f * sin(2 * PI * 0.8 * t).toFloat()) +
        World.down * (1.0f * sin(2 * PI * 1.3 * t + 1.0).toFloat()) +
        World.roll * (0.25f * sin(2 * PI * 0.5 * t + 2.0).toFloat()) +
        Vec3(0.12f, 0.12f, 0.12f) * sin(2 * PI * 9.0 * t).toFloat()

fun pointingA(t: Double): Vec3 =
    Vec3(0.9f, 0.5f, 0.3f) * sin(2 * PI * 0.8 * t).toFloat() + Vec3(0.3f, 0.3f, 0.3f) * sin(2 * PI * 9.0 * t).toFloat()

/** Impulso de toque/pellizco: ~2.4 m/s2 durante 30 ms, casi sin giro. */
fun tapA(at: Double, amp: Float = 1.4f): (Double) -> Vec3 =
    { t -> if (t >= at && t < at + 0.03) Vec3(amp, amp * 1.2f, amp) else Vec3.ZERO }

/** Giro rapido del antebrazo con su regreso (rad/s pico `peak`). dir=+1 afuera, -1 adentro. */
fun rollFlick(dir: Float, peak: Float, at: Double): (Double) -> Vec3 = { t ->
    World.roll * (dir * peak * sin2(t - at, 0.15)) + World.roll * (-dir * 0.7f * peak * sin2(t - at - 0.25, 0.2))
}

fun rollJerk(at: Double): (Double) -> Vec3 = { t ->
    // sacudon de aceleracion al inicio y al final del giro
    (if (t >= at && t < at + 0.04) Vec3(2.0f, 2.5f, 1.5f) else Vec3.ZERO) +
        (if (t >= at + 0.15 && t < at + 0.19) Vec3(-2.0f, -2.2f, -1.2f) else Vec3.ZERO)
}

// =====================================================================================================
fun testCalibrationAndGestures() {
    println("[1] Asistente: medir ejes y entrenar gestos")
    var state = WizardState()
    var buzz = 0
    val cal = Calibrator({ state = it }, { buzz++ })
    val sim = Sim({ ts, w -> cal.feedGyro(ts, w.x, w.y, w.z) }, { ts, a -> cal.feedAccel(ts, a.x, a.y, a.z) })

    check(state.step == Step.WRIST, "arranca preguntando la muneca")
    cal.setWrist(Wrist.LEFT)
    check(state.step == Step.RIGHT, "tras elegir muneca pide el movimiento a la DERECHA")

    // 1.5 s quieto (armado) y dos trazos a la derecha
    sim.idle(1.5); sim.stroke(World.right); sim.idle(0.3); sim.stroke(World.right, 0.6f)
    check(state.step == Step.DOWN, "dos trazos coincidentes -> pasa a ABAJO (paso=${state.step})")

    // Un trazo en sentido contrario al primero debe rechazarse con aviso
    sim.idle(1.3); sim.stroke(World.down); sim.idle(0.3); sim.stroke(World.down * -1f)
    check(state.step == Step.DOWN && state.warning != null, "trazos contradictorios -> aviso y se repite (${state.warning})")
    sim.idle(1.3); sim.stroke(World.down); sim.idle(0.3); sim.stroke(World.down, 0.55f)
    check(state.step == Step.ROLL, "ABAJO medido -> pasa al giro del antebrazo (paso=${state.step})")

    // Mover la mano a los lados en el paso de roll debe rechazarse (eje igual al de la derecha)
    sim.idle(1.3); sim.stroke(World.right); sim.idle(0.3); sim.stroke(World.right)
    check(state.step == Step.ROLL && state.warning != null, "roll parecido a la derecha -> rechazado (${state.warning})")
    sim.idle(1.3); sim.stroke(World.roll); sim.idle(0.3); sim.stroke(World.roll, 0.7f)
    check(state.step == Step.TRAIN_REST, "roll medido -> pasa a movimiento libre (paso=${state.step})")

    // 5 s de movimiento libre sin gestos (+1 s de armado)
    sim.play(6.8, bg = true)
    check(state.step == Step.TRAIN_TAP, "tras el movimiento libre pasa a entrenar el toque (paso=${state.step})")

    // 5 toques
    repeat(5) {
        val amp = 1.3f + 0.2f * it            // variabilidad natural entre intentos
        sim.play(if (it == 0) 2.4 else 1.0, bg = true, a = tapA(if (it == 0) 1.5 else 0.3, amp))
    }
    sim.idle(0.5)
    check(state.step == Step.TRAIN_ROLL_OUT, "5 toques -> pasa a giro afuera (paso=${state.step}, picos=${state.progress})")

    // 5 giros afuera (1 s de armado en el primero)
    repeat(5) {
        sim.play(if (it == 0) 2.6 else 1.3, bg = true, w = rollFlick(1f, 7f + it * 0.4f, if (it == 0) 1.5 else 0.3))
    }
    sim.idle(0.5)
    check(state.step == Step.TRAIN_ROLL_IN, "5 giros afuera -> pasa a giro adentro (paso=${state.step})")
    repeat(5) {
        sim.play(if (it == 0) 2.6 else 1.3, bg = true, w = rollFlick(-1f, 7f + it * 0.3f, if (it == 0) 1.5 else 0.3))
    }
    sim.idle(0.5)
    check(state.step == Step.SUMMARY, "5 giros adentro -> resumen (paso=${state.step})")

    val r = cal.result()!!
    println("      resumen: ${state.summary}")
    println("      medido:  ${cal.debugInfo()}")
    check(r != null, "hay resultado")
    val eR = angleDeg(r.right, World.right); val eD = angleDeg(r.down, World.down); val eO = angleDeg(r.roll, World.roll)
    check(eR < 5f, "eje DERECHA recuperado con ${"%.1f".format(eR)} deg de error (reloj girado 25 deg)")
    check(eD < 8f, "eje ABAJO recuperado con ${"%.1f".format(eD)} deg de error")
    check(eO < 5f, "eje ROLL recuperado con ${"%.1f".format(eO)} deg de error")
    check(abs(r.right dot r.down) < 0.1f, "derecha y abajo quedan ortogonales")
    check(r.tapThreshold != null && r.tapThreshold!! in 0.8f..2.5f, "umbral de toque razonable: ${r.tapThreshold} (${r.tapQuality})")
    check(r.rollThreshold != null && r.rollThreshold!! in 4f..7f, "umbral de giro razonable: ${r.rollThreshold} (${r.rollQuality})")
    check(r.tapQuality == Quality.GOOD && r.rollQuality == Quality.GOOD, "calidad buena en ambos gestos")
    check(buzz > 10, "el asistente vibra al aceptar cada intento ($buzz vibraciones)")

    // ---------------------------------------------------------------------------------------------
    println("[2] Detector de gestos con esos umbrales (sin falsos positivos)")
    val cfg = GestureConfig(r.tapThreshold!!, r.rollThreshold!!, r.roll)
    fun runDetector(script: (Sim) -> Unit): List<Gesture> {
        val out = ArrayList<Gesture>()
        val det = GestureDetector { cfg }
        val s = Sim({ ts, w -> det.onGyro(ts, w.x, w.y, w.z)?.let { out += it } },
            { ts, a -> det.onAccel(ts, a.x, a.y, a.z)?.let { out += it } })
        s.idle(1.0)
        script(s)
        return out
    }

    val calm = runDetector { it.play(60.0, bg = true) }
    check(calm.isEmpty(), "60 s apuntando con temblor -> 0 gestos (${calm.size})")

    val taps = runDetector { s -> repeat(10) { s.play(2.0, bg = true, a = tapA(1.0, 1.8f)) } }
    check(taps.size == 10 && taps.all { it == Gesture.TAP }, "10 toques -> 10 TAP (${taps.size}: ${taps.toSet()})")

    val outs = runDetector { s -> repeat(10) { s.play(2.0, bg = true, w = rollFlick(1f, 7.5f, 1.0), a = rollJerk(1.0)) } }
    check(outs.size == 10 && outs.all { it == Gesture.ROLL_OUT }, "10 giros afuera (con regreso y sacudon) -> 10 ROLL_OUT exactos (${outs.size}: ${outs.toSet()})")

    val ins = runDetector { s -> repeat(10) { s.play(2.0, bg = true, w = rollFlick(-1f, 7.5f, 1.0), a = rollJerk(1.0)) } }
    check(ins.size == 10 && ins.all { it == Gesture.ROLL_IN }, "10 giros adentro -> 10 ROLL_IN exactos (${ins.size}: ${ins.toSet()})")

    val swipes = runDetector { s ->
        repeat(10) {
            s.play(2.0, bg = true,
                w = { t -> World.right * (6.5f * sin2(t - 1.0, 0.25)) },
                a = { t -> if (t in 1.0..1.04) Vec3(2.5f, 2f, 2f) else Vec3.ZERO })
        }
    }
    check(swipes.isEmpty(), "barrido rapido del brazo (giro 6.5 rad/s + sacudon) -> 0 gestos (${swipes.size}: ${swipes.toSet()})")

    // Cursor: los toques no deben mover el cursor mas alla de lo que se compensa
    println("[3] Cursor con ejes calibrados")
    val settings = Settings(axisRight = r.right, axisDown = r.down, axisRoll = r.roll, setupDone = true)
    val mp = MotionProcessor { settings }
    val sink = IntArray(2)
    var px = 0; var py = 0
    val s3 = Sim({ ts, w -> mp.onGyro(w.x, w.y, w.z, ts); mp.drain(sink); px += sink[0]; py += sink[1] }, { _, _ -> })
    s3.idle(2.0); px = 0; py = 0
    s3.play(1.0, w = { World.right * 1.0f })         // 1 rad/s a la derecha durante 1 s
    check(px > 800 && abs(py) < px / 15, "mano a la derecha -> cursor a la derecha (px=$px, py=$py)")
    px = 0; py = 0
    s3.play(1.0, w = { World.down * 1.0f })
    check(py > 800 && abs(px) < py / 15, "mano abajo -> cursor abajo (px=$px, py=$py)")
    px = 0; py = 0
    s3.play(1.0, w = { World.roll * 1.5f })
    check(abs(px) < 120 && abs(py) < 120, "girar el antebrazo (roll) casi no mueve el cursor (px=$px, py=$py)")
    px = 0; py = 0
    mp.freeze((s3.t * 1e9).toLong() + 400_000_000L)
    s3.play(0.3, w = { World.right * 2f })
    check(px == 0 && py == 0, "cursor congelado durante el gesto (px=$px, py=$py)")
    s3.play(0.5, w = { World.right * 1.0f })
    check(px > 200, "y se reanuda al terminar la congelacion (px=$px)")
}

fun testHistory() {
    println("[4] Historial para deshacer el sacudon antes del clic")
    val h = MoveHistory(8)
    val out = IntArray(2)
    for (i in 0 until 12) h.add(i * 10_000_000L, 1, -2)     // 12 > capacidad: el anillo descarta lo viejo
    h.sumSince(60_000_000L, out)                              // t >= 60 ms -> muestras 6..11 = 6 muestras
    check(out[0] == 6 && out[1] == -12, "suma de los ultimos 60 ms = (${out[0]},${out[1]})")
    h.sumSince(0L, out)
    check(out[0] == 8, "el anillo conserva solo ${out[0]} muestras (capacidad 8)")
    h.clear(); h.sumSince(0L, out)
    check(out[0] == 0 && out[1] == 0, "clear() vacia el historial")
}

fun testScrollPose() {
    println("[5] Scroll por inclinacion sostenida")
    val cfg = ScrollConfig(World.roll, World.down, 35f, 20f)
    val pose = ScrollPose()
    var enters = 0; var exits = 0
    var angle = 0.0
    val sim = Sim(
        { ts, w -> pose.onGyro(ts, w, cfg) },
        { ts, a -> pose.onAccel(ts, a, cfg)?.let { if (it == ScrollPose.Transition.ENTER) enters++ else exits++ } },
        gravity = { World.gravityRolled(angle) },
    )
    sim.idle(2.0)                                   // postura neutra automatica (1 s quieto)
    angle = -50.0                                   // palma arriba 50 grados, sostenida
    sim.idle(0.3)
    check(enters == 0, "a los 0.3 s todavia no entra (hay que sostener 450 ms)")
    sim.idle(0.6)
    check(enters == 1 && pose.active, "sostener 0.9 s con la palma arriba -> entra a modo scroll (angulo ${"%.0f".format(pose.angleDeg)} deg)")
    // inclinar la muneca hacia abajo: 0.4 rad en 0.5 s
    sim.play(0.5, w = { World.down * 0.8f })
    check(pose.rate > 5f, "mano hacia abajo -> scroll hacia abajo (${"%.1f".format(pose.rate)} muescas/s)")
    sim.play(1.0, w = { World.down * -0.8f })
    check(pose.rate < -5f, "mano hacia arriba -> scroll hacia arriba (${"%.1f".format(pose.rate)} muescas/s)")
    angle = 0.0
    sim.idle(0.5)
    check(exits == 1 && !pose.active && pose.rate == 0f, "volver a la postura neutra -> sale de scroll")

    // Un giro rapido (clic derecho) pasa por >35 grados pero no debe activar el scroll
    val p2 = ScrollPose(); var e2 = 0; var a2 = 0.0
    val s2 = Sim({ ts, w -> p2.onGyro(ts, w, cfg) }, { ts, a -> p2.onAccel(ts, a, cfg)?.let { if (it == ScrollPose.Transition.ENTER) e2++ } },
        gravity = { World.gravityRolled(a2) })
    s2.idle(2.0)
    repeat(5) { a2 = -55.0; s2.idle(0.2); a2 = 0.0; s2.idle(1.0) }
    check(e2 == 0, "giros breves de 0.2 s a 55 grados -> no activan scroll ($e2)")
}

fun testPalmSideAndTaps() {
    println("[5b] Lado del scroll, doble toque y mapa por defecto")
    val cfg = ScrollConfig(World.roll, World.down, 35f, 20f)
    val pose = ScrollPose(); var enters = 0; var ang = 0.0
    val sim = Sim({ ts, w -> pose.onGyro(ts, w, cfg) },
        { ts, a -> pose.onAccel(ts, a, cfg)?.let { if (it == ScrollPose.Transition.ENTER) enters++ } },
        gravity = { World.gravityRolled(ang) })
    sim.idle(2.0)
    ang = 55.0; sim.idle(1.5)
    check(enters == 0 && !pose.active, "palma ABAJO sostenida 1.5 s -> no activa el scroll ($enters)")
    ang = 0.0; sim.idle(1.0)
    ang = -55.0; sim.idle(1.0)
    check(enters == 1 && pose.active, "palma ARRIBA sostenida -> activa el scroll")

    // Coherencia de signo: un giro fisico hacia palma arriba (w . roll > 0) debe terminar en th < 0 (palma arriba)
    val c = TapCombiner()
    check(c.onTap(1_000_000_000L) == Gesture.TAP, "primer toque -> TAP inmediato")
    check(c.onTap(1_400_000_000L) == Gesture.DOUBLE_TAP, "segundo toque a 0.4 s -> DOUBLE_TAP")
    check(c.onTap(1_900_000_000L) == Gesture.TAP, "tercer toque (la secuencia se reinicia) -> TAP")
    check(c.onTap(2_700_000_000L) == Gesture.TAP, "toque a 0.8 s del anterior -> TAP (fuera de la ventana)")
    c.reset()
    check(c.onTap(3_000_000_000L) == Gesture.TAP, "tras reset() -> TAP")

    val d = Settings()
    check(d.actionFor(Gesture.TAP) == Action.LEFT_CLICK && d.actionFor(Gesture.DOUBLE_TAP) == Action.DRAG_TOGGLE &&
        d.actionFor(Gesture.ROLL_IN) == Action.RIGHT_CLICK && d.actionFor(Gesture.ROLL_OUT) == Action.NONE,
        "mapa por defecto: toque=clic, doble toque=mantener, giro palma abajo=clic derecho, palma arriba=nada")
    check(d.withAction(Gesture.DOUBLE_TAP, Action.DOUBLE_CLICK).actionFor(Gesture.DOUBLE_TAP) == Action.DOUBLE_CLICK,
        "withAction(DOUBLE_TAP) asigna")
}

fun testPresentation() {
    println("[7] Modo presentacion: temporizador, confirmacion, bisel, mapa")
    val m = PresentationTimer.MIN
    val tm = PresentationTimer(20 * m)
    check(tm.tick(0).isEmpty(), "detenido no genera alertas")
    tm.start(0)
    check(tm.tick(9 * m + 59_000).isEmpty(), "a 9:59 todavia nada")
    check(tm.tick(10 * m) == listOf(TimerAlert.HALF), "a los 10:00 suena la mitad")
    check(tm.tick(10 * m + 500).isEmpty(), "la mitad suena una sola vez")
    check(tm.tick(15 * m) == listOf(TimerAlert.MIN5), "faltando 5 min -> MIN5")
    check(tm.tick(18 * m) == listOf(TimerAlert.MIN2), "faltando 2 min -> MIN2")
    check(tm.tick(19 * m) == listOf(TimerAlert.MIN1), "faltando 1 min -> MIN1")
    check(tm.tick(20 * m) == listOf(TimerAlert.END), "al final -> END")
    check(tm.remaining(21 * m) == -m && formatClock(tm.remaining(21 * m)) == "-01:00", "tiempo excedido se muestra en negativo")

    val t2 = PresentationTimer(10 * m)          // la mitad coincide con "faltan 5 min": una sola alerta
    t2.start(0)
    check(t2.tick(5 * m) == listOf(TimerAlert.HALF), "charla de 10 min: mitad = 5 min, sin alerta duplicada")
    val t3 = PresentationTimer(4 * m)           // charla corta: no hay "5 minutos"
    t3.start(0)
    val seen = ArrayList<TimerAlert>()
    var now = 0L
    while (now <= 4 * m) { seen += t3.tick(now); now += 1000 }
    check(seen == listOf(TimerAlert.HALF, TimerAlert.MIN1, TimerAlert.END),
        "charla de 4 min: mitad (= faltan 2, sin duplicar), 1 y fin; sin 5: $seen")

    val t4 = PresentationTimer(20 * m)          // pausa: el tiempo parado no cuenta
    t4.start(0); t4.pause(5 * m)
    check(t4.elapsed(30 * m) == 5 * m && t4.tick(30 * m).isEmpty(), "en pausa no avanza ni alerta")
    t4.start(30 * m)
    check(t4.elapsed(35 * m) == 10 * m && t4.tick(35 * m) == listOf(TimerAlert.HALF), "al reanudar suma (10 min) y suena la mitad")
    t4.setTotal(12 * m, 35 * m)                 // se acorta la charla a 12 min estando en 10: nada atrasado debe sonar
    check(t4.tick(35 * m).isEmpty() && t4.remaining(35 * m) == 2 * m, "cambiar la duracion no hace sonar alertas pasadas")
    t4.reset()
    check(!t4.running && t4.elapsed(99 * m) == 0L, "reiniciar deja el cronometro en cero")
    check(formatClock(75_000) == "01:15" && formatClock(3_725_000) == "1:02:05", "formato de reloj")

    val g = ConfirmGate(2500)
    check(!g.request(0) && g.isArmed(1000), "primera pulsacion solo arma")
    check(g.request(2000), "segunda dentro de 2.5 s confirma")
    check(!g.request(2100), "despues de confirmar vuelve a pedir doble")
    check(!g.request(10_000) && !g.isArmed(13_000), "la confirmacion caduca")
    check(!g.request(20_000) && g.request(21_000), "rearmar y confirmar")

    val r = RotaryStepper(40f)
    check(r.add(30f) == 0 && r.add(30f) == 1 && r.add(-100f) == -2, "bisel: pasos enteros con resto conservado")
    check(clampSlide(0, 10) == 1 && clampSlide(50, 10) == 10 && clampSlide(5, null) == 5 && clampSlide(5000, null) == 999, "limites del selector")

    check(SlideInfo(3, 20, "", true).label() == "3/20" && SlideInfo(3, null, "", false).label() == "~3/?" &&
        SlideInfo(null, null, "", false).label() == "–/?", "etiqueta de diapositiva (exacta / estimada / desconocida)")
    check(Profiles.next("auto") == "powerpoint" && Profiles.next("generic") == "auto" && Profiles.label("gslides") == "Google Slides",
        "ciclo de perfiles")

    val s = Settings()
    check(s.presActionFor(Gesture.TAP) == Action.NONE && s.presActionFor(Gesture.DOUBLE_TAP) == Action.NONE &&
        s.presActionFor(Gesture.ROLL_IN) == Action.NONE && s.presActionFor(Gesture.ROLL_OUT) == Action.NONE,
        "presentacion: ni el toque ni los giros tienen accion por defecto (el toque abre 'toque + deslizar')")
    check(s.withPresAction(Gesture.ROLL_OUT, Action.LASER_TOGGLE).presActionFor(Gesture.ROLL_OUT) == Action.LASER_TOGGLE &&
        s.withPresAction(Gesture.TAP, Action.END_SHOW).presActionFor(Gesture.TAP) == Action.NONE &&
        s.actionFor(Gesture.TAP) == Action.LEFT_CLICK, "mapa de presentacion independiente del de mouse; el toque no es asignable")

    // ---- toque + deslizar
    fun run(wx: (Int) -> Float, wy: (Int) -> Float = { 0f }, ms: Int = 700, arm: Boolean = true): Pair<Swipe?, Boolean> {
        val sw = SwipeAfterTap { 18f }
        if (arm) sw.arm(1_000_000_000L)
        var got: Swipe? = null
        for (i in 1..ms / 10) {
            val r = sw.onGyro(1_000_000_000L + i * 10_000_000L, wx(i), wy(i))
            if (r != null && got == null) got = r
        }
        return got to sw.consumeExpired(1_000_000_000L + ms * 1_000_000L + 1_000_000L)
    }
    check(run({ 3f }).first == Swipe.RIGHT && run({ -3f }).first == Swipe.LEFT, "deslizar a la derecha = RIGHT, a la izquierda = LEFT")
    check(run({ 0.1f }).first == null && run({ 0f }).second, "toque solo (sin movimiento): no hay deslizamiento y la ventana expira")
    check(run({ 0.2f }).first == null, "deriva lenta de la mano (0.2 rad/s) no cuenta")
    check(run({ 0.5f }, { 3f }).first == null, "movimiento vertical no cuenta")
    check(run({ if (it <= 5) 10f else 0f }).first == null, "el sacudon del propio toque (primeros 60 ms) se ignora")
    check(run({ 3f }, arm = false).first == null, "sin toque previo, mover la mano no hace nada")
    check(run({ if (it < 40) 3f else 0f }, ms = 300).first == Swipe.RIGHT, "el deslizamiento se detecta antes de que cierre la ventana")
    check(run({ if (it > 80) 3f else 0f }, ms = 1000).first == null, "un movimiento que llega tarde (despues de 0.6 s) ya no cuenta")
    val sw2 = SwipeAfterTap { 18f }
    sw2.arm(1_000_000_000L)
    var fired = 0
    for (i in 1..50) if (sw2.onGyro(1_000_000_000L + i * 10_000_000L, 3f, 0f) != null) fired++
    check(fired == 1 && !sw2.armed, "un toque produce como maximo un cambio de diapositiva")
}

fun testHid() {
    println("[8] Bluetooth HID")
    val kb = { k: List<String> -> HidKeys.keyboardReport(k)!!.map { it.toInt() and 0xFF } }
    check(kb(listOf("right")) == listOf(0, 0, 0x4F, 0, 0, 0, 0, 0), "flecha derecha = uso 0x4F")
    check(kb(listOf("shift", "f5")) == listOf(2, 0, 0x3E, 0, 0, 0, 0, 0), "Shift+F5: modificador + F5")
    check(kb(listOf("ctrl", "alt", "a"))[0] == 5 && kb(listOf("ctrl", "alt", "a"))[2] == 0x04, "Ctrl+Alt+A")
    check(kb(listOf("0"))[2] == 0x27 && kb(listOf("1"))[2] == 0x1E && kb(listOf("z"))[2] == 0x1D && kb(listOf("."))[2] == 0x37, "digitos, letras y punto")
    check(HidKeys.keyboardReport(listOf("rm")) == null, "tecla desconocida -> sin informe")
    check(HidKeys.presSequence("goto", 12) == listOf(listOf("1"), listOf("2"), listOf("enter")) &&
        HidKeys.presSequence("goto", 0) == null && HidKeys.presSequence("goto", 10000) == null &&
        HidKeys.presSequence("rm") == null && HidKeys.presSequence("next") == listOf(listOf("right")),
        "atajos de presentacion (ir a 12 = 1,2,Enter; rango; accion inventada)")
    check(HidKeys.mouseReport(3, 300, -300, 5).map { it.toInt() } == listOf(3, 127, -127, 5), "informe de mouse acotado a -127..127")
    check(HidKeys.splitMove(300, -10).let { p -> p.size == 3 && p.sumOf { it[0] } == 300 && p.sumOf { it[1] } == -10 && p.all { Math.abs(it[0]) <= 127 } },
        "un movimiento grande se divide sin perder pixeles")
    check(HidKeys.splitMove(0, 0).isEmpty(), "movimiento nulo = ningun informe")
    check(HidKeys.buttonMask("left") == 1 && HidKeys.buttonMask("right") == 2 && HidKeys.buttonMask("middle") == 4 &&
        HidKeys.buttonMask("back") == 8 && HidKeys.buttonMask("x") == 0, "mascara de botones")
    val d = HidKeys.DESCRIPTOR.map { it.toInt() and 0xFF }
    check(d.size == 107 || d.size > 90, "descriptor HID presente (${d.size} bytes)")
    check(d.take(8) == listOf(0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01) && d.count { it == 0xC0 } >= 3,
        "descriptor: empieza como mouse (id 1) y cierra las colecciones")
}

fun testThresholdFit() {
    println("[6] Ajuste de umbrales")
    val good = ThresholdFit.fit(listOf(2.2f, 2.6f, 2.4f, 2.9f, 2.5f), 0.4f, 0.8f, 6f)
    check(good.second == Quality.GOOD && good.first in 0.5f..2.2f, "separacion clara -> GOOD, umbral ${good.first}")
    val fair = ThresholdFit.fit(listOf(1.5f, 1.8f, 1.7f, 1.9f, 1.6f), 1.0f, 0.8f, 6f)
    check(fair.second != Quality.GOOD, "separacion pequena -> no GOOD (${fair.second}, umbral ${fair.first})")
    val poor = ThresholdFit.fit(listOf(0.9f, 1.1f, 1.0f, 1.2f, 0.95f), 1.4f, 0.8f, 6f)
    check(poor.second == Quality.POOR, "gesto mas debil que el movimiento normal -> POOR")
}

fun main() {
    testCalibrationAndGestures()
    testHistory()
    testScrollPose()
    testPalmSideAndTaps()
    testPresentation()
    testThresholdFit()
    testHid()
    println(if (failures == 0) "\nTODO OK" else "\n$failures FALLOS")
    exitProcess(if (failures == 0) 0 else 1)
}
