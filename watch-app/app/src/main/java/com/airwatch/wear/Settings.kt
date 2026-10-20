package com.airwatch.wear

import kotlin.math.sqrt

/** Vector 3D en el marco de ejes del reloj (x, y, z del sensor). Sin dependencias de Android. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(this dot this)
    fun unit(): Vec3 {
        val n = norm()
        return if (n < 1e-6f) this else this * (1f / n)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

enum class Wrist(val label: String) { LEFT("Izquierda"), RIGHT("Derecha") }

/** Gestos que reconoce el reloj. */
enum class Gesture(val label: String) {
    TAP("Toque"),
    DOUBLE_TAP("Doble toque"),
    ROLL_IN("Giro palma abajo"),     // giro rapido hacia el lado contrario a "palma arriba"
    ROLL_OUT("Giro palma arriba"),   // sentido en que se midio el eje de roll (supinacion)
}

/** Acciones que se pueden asignar a cada gesto (reasignables en Ajustes). */
enum class Action(val label: String) {
    NONE("Nada"),
    LEFT_CLICK("Clic izquierdo"),
    RIGHT_CLICK("Clic derecho"),
    MIDDLE_CLICK("Clic central"),
    DOUBLE_CLICK("Doble clic"),
    BACK("Atrás"),
    FORWARD("Adelante"),
    DRAG_TOGGLE("Arrastrar"),
    ALT_TAB("Cambiar ventana"),
    RECENTER("Centrar cursor"),
    PAUSE_TOGGLE("Pausar"),
    // --- Presentacion (se ejecutan en cualquier modo)
    NEXT_SLIDE("Diapositiva siguiente"),
    PREV_SLIDE("Diapositiva anterior"),
    LASER_TOGGLE("Láser (activar/apagar)"),
    BLACK_SCREEN("Pantalla negra"),
    WHITE_SCREEN("Pantalla blanca"),
    START_SHOW("Iniciar presentación"),
    END_SHOW("Salir (pide repetir)"),
}

/** Ajustes del air mouse (se persisten en SharedPreferences, ver Prefs). */
data class Settings(
    // --- Cursor
    val sensitivity: Float = 2.0f,  // multiplicador 0.2..3.0 sobre ~1200 px/rad
    val smoothing: Float = 0.35f,   // 0 = sin filtro .. 0.9 = muy suave (low-pass exponencial)
    val deadzone: Float = 0.06f,    // rad/s ignorados (anti-temblor)
    val accel: Float = 0.4f,        // 0 = lineal .. 2 = mucha aceleracion
    val sendHz: Int = 80,           // 60..100 Hz de envio
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    // --- Configuracion guiada (asistente)
    val wrist: Wrist = Wrist.LEFT,
    val setupDone: Boolean = false,
    // Ejes medidos por el asistente, en el marco del reloj. Por defecto, el mapeo de la Etapa 1.
    val axisRight: Vec3 = Vec3(0f, 0f, -1f),  // giro del reloj cuando la mano va a la DERECHA
    val axisDown: Vec3 = Vec3(-1f, 0f, 0f),   // giro del reloj cuando la mano va hacia ABAJO
    val axisRoll: Vec3 = Vec3(0f, 1f, 0f),    // giro del antebrazo hacia AFUERA (palma arriba)
    // --- Gestos
    val gesturesEnabled: Boolean = true,
    val tapThreshold: Float = 1.5f,   // m/s2 de impulso (aceleracion filtrada pasa-altas)
    val rollThreshold: Float = 5.0f,  // rad/s de giro del antebrazo
    val tapAction: Action = Action.LEFT_CLICK,
    val doubleTapAction: Action = Action.DRAG_TOGGLE,   // dos toques: deja el boton presionado (seleccionar/arrastrar)
    val rollInAction: Action = Action.RIGHT_CLICK,      // giro rapido palma abajo
    val rollOutAction: Action = Action.NONE,            // giro rapido palma arriba (la pose sostenida es el scroll)
    val compMs: Int = 90,             // ventana de movimiento que se deshace al hacer clic
    // --- Scroll por inclinacion sostenida
    val scrollPose: Boolean = true,
    val scrollEnterDeg: Float = 35f,
    // --- Modo presentacion: mapa de gestos propio (en el mouse el cursor se mueve; aqui no)
    // El toque en presentacion no tiene accion propia: abre la ventana de "toque + deslizar" (derecha = siguiente,
    // izquierda = anterior). Los giros rapidos quedan sin asignar para que mover las manos al hablar no active nada.
    val presRollIn: Action = Action.NONE,
    val presRollOut: Action = Action.NONE,
    val presSwipeDeg: Float = 18f,                    // grados de desplazamiento tras el toque para pasar de diapositiva
    val presLaserIdleS: Int = 8,                      // el laser se apaga solo tras estos segundos sin mover la mano
    val presMinutes: Int = 20,                        // duracion total para el temporizador
    val presProfile: String = "auto",                 // perfil de aplicacion: auto o uno fijo
    val presHaptics: Boolean = true,                  // vibracion al reconocer un gesto (el temporizador siempre vibra)
) {
    fun actionFor(g: Gesture): Action = when (g) {
        Gesture.TAP -> tapAction
        Gesture.DOUBLE_TAP -> doubleTapAction
        Gesture.ROLL_OUT -> rollOutAction
        Gesture.ROLL_IN -> rollInAction
    }

    fun presActionFor(g: Gesture): Action = when (g) {
        Gesture.ROLL_IN -> presRollIn
        Gesture.ROLL_OUT -> presRollOut
        else -> Action.NONE               // TAP / DOUBLE_TAP: ver SwipeAfterTap
    }

    fun withPresAction(g: Gesture, a: Action): Settings = when (g) {
        Gesture.ROLL_IN -> copy(presRollIn = a)
        Gesture.ROLL_OUT -> copy(presRollOut = a)
        else -> this
    }

    fun withAction(g: Gesture, a: Action): Settings = when (g) {
        Gesture.TAP -> copy(tapAction = a)
        Gesture.DOUBLE_TAP -> copy(doubleTapAction = a)
        Gesture.ROLL_OUT -> copy(rollOutAction = a)
        Gesture.ROLL_IN -> copy(rollInAction = a)
    }
}
