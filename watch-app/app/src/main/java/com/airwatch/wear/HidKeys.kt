package com.airwatch.wear

// Logica pura (sin Android) del transporte Bluetooth HID: informes de mouse/teclado y atajos de presentacion.

object HidKeys {
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_WIN = 0x08

    private val MODIFIERS = mapOf("ctrl" to MOD_CTRL, "shift" to MOD_SHIFT, "alt" to MOD_ALT, "win" to MOD_WIN)

    private val USAGE: Map<String, Int> = buildMap {
        for (c in 'a'..'z') put(c.toString(), 0x04 + (c - 'a'))
        for (c in '1'..'9') put(c.toString(), 0x1E + (c - '1'))
        put("0", 0x27)
        put("enter", 0x28); put("esc", 0x29); put("tab", 0x2B); put("space", 0x2C)
        put("-", 0x2D); put(",", 0x36); put(".", 0x37)
        for (i in 1..12) put("f$i", 0x3A + (i - 1))
        put("home", 0x4A); put("page_up", 0x4B); put("end", 0x4D); put("page_down", 0x4E)
        put("right", 0x4F); put("left", 0x50); put("down", 0x51); put("up", 0x52)
    }

    /** Informe de teclado (8 bytes: modificadores, reservado, 6 teclas) para una combinacion; null si alguna tecla no existe. */
    fun keyboardReport(keys: List<String>): ByteArray? {
        var mods = 0
        val codes = ArrayList<Int>()
        for (k in keys) {
            val m = MODIFIERS[k]
            if (m != null) { mods = mods or m; continue }
            codes.add(USAGE[k] ?: return null)
        }
        if (codes.size > 6) return null
        val r = ByteArray(8)
        r[0] = mods.toByte()
        codes.forEachIndexed { i, c -> r[2 + i] = c.toByte() }
        return r
    }

    val RELEASE_ALL = ByteArray(8)

    /** Botones del mouse como mascara de bits (el descriptor define 5 botones). */
    fun buttonMask(name: String): Int = when (name) {
        "left" -> 1; "right" -> 2; "middle" -> 4; "back" -> 8; "forward" -> 16
        else -> 0
    }

    /** Informe de mouse: botones, dx, dy, rueda (cada uno en -127..127). */
    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray = byteArrayOf(
        buttons.toByte(), dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte(),
    )

    /** Divide un desplazamiento grande en pasos de a lo mas 127 por eje. */
    fun splitMove(dx: Int, dy: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var rx = dx; var ry = dy
        while (rx != 0 || ry != 0) {
            val sx = rx.coerceIn(-127, 127); val sy = ry.coerceIn(-127, 127)
            out.add(intArrayOf(sx, sy)); rx -= sx; ry -= sy
        }
        return out
    }

    /**
     * Atajos de presentacion sin la app del PC (no conoce la aplicacion en primer plano): los de PowerPoint,
     * Impress y la mayoria de visores. Google Slides arranca con Ctrl+F5 (aqui F5); laser y marcador no existen.
     * Devuelve la secuencia de combinaciones, o null si la accion no tiene equivalente.
     */
    fun presSequence(action: String, n: Int? = null): List<List<String>>? = when (action) {
        "next" -> listOf(listOf("right"))
        "prev" -> listOf(listOf("left"))
        "start" -> listOf(listOf("f5"))
        "start_current" -> listOf(listOf("shift", "f5"))
        "end" -> listOf(listOf("esc"))
        "black" -> listOf(listOf("b"))
        "white" -> listOf(listOf("w"))
        "goto" -> if (n != null && n in 1..9999) n.toString().map { listOf(it.toString()) } + listOf(listOf("enter")) else null
        else -> null
    }

    /** Descriptor HID: mouse (id 1: 5 botones, x, y, rueda relativos) + teclado (id 2). */
    val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x02, 0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x03,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xC0,
    ).map { it.toByte() }.toByteArray()

    const val REPORT_MOUSE = 1
    const val REPORT_KEYBOARD = 2
}
