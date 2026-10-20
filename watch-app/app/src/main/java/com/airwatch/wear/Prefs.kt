package com.airwatch.wear

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persistencia de [Settings] (la clase de datos vive en Settings.kt, sin dependencias de Android). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun vec(key: String, def: Vec3) =
        Vec3(sp.getFloat("${key}X", def.x), sp.getFloat("${key}Y", def.y), sp.getFloat("${key}Z", def.z))

    private fun SharedPreferences.Editor.putVec(key: String, v: Vec3): SharedPreferences.Editor =
        putFloat("${key}X", v.x).putFloat("${key}Y", v.y).putFloat("${key}Z", v.z)

    private inline fun <reified E : Enum<E>> enumOf(key: String, def: E): E =
        try {
            enumValueOf<E>(sp.getString(key, def.name) ?: def.name)
        } catch (_: IllegalArgumentException) {
            def
        }

    fun load(): Settings {
        val d = Settings()
        // v2: nuevo mapa de gestos (toque / doble toque / giro palma abajo). Se reinician las asignaciones guardadas.
        if (sp.getInt("mapVer", 1) < 2) {
            sp.edit().remove("tapAction").remove("doubleTapAction").remove("rollOutAction").remove("rollInAction")
                .putInt("mapVer", 2).apply()
        }
        // v3: presentacion = toque + deslizar; los giros quedan sin asignar (se descartan las asignaciones viejas).
        if (sp.getInt("mapVer", 1) < 3) {
            sp.edit().remove("presTap").remove("presDoubleTap").remove("presRollIn").remove("presRollOut")
                .remove("presPoseLaser").putInt("mapVer", 3).apply()
        }
        return Settings(
            sensitivity = sp.getFloat("sensitivity", d.sensitivity),
            smoothing = sp.getFloat("smoothing", d.smoothing),
            deadzone = sp.getFloat("deadzone", d.deadzone),
            accel = sp.getFloat("accel", d.accel),
            sendHz = sp.getInt("sendHz", d.sendHz),
            invertX = sp.getBoolean("invertX", d.invertX),
            invertY = sp.getBoolean("invertY", d.invertY),
            transport = enumOf("transport", d.transport),
            wrist = enumOf("wrist", d.wrist),
            setupDone = sp.getBoolean("setupDone", d.setupDone),
            axisRight = vec("axisRight", d.axisRight),
            axisDown = vec("axisDown", d.axisDown),
            axisRoll = vec("axisRoll", d.axisRoll),
            gesturesEnabled = sp.getBoolean("gesturesEnabled", d.gesturesEnabled),
            tapThreshold = sp.getFloat("tapThreshold", d.tapThreshold),
            rollThreshold = sp.getFloat("rollThreshold", d.rollThreshold),
            tapAction = enumOf("tapAction", d.tapAction),
            doubleTapAction = enumOf("doubleTapAction", d.doubleTapAction),
            rollOutAction = enumOf("rollOutAction", d.rollOutAction),
            rollInAction = enumOf("rollInAction", d.rollInAction),
            compMs = sp.getInt("compMs", d.compMs),
            scrollPose = sp.getBoolean("scrollPose", d.scrollPose),
            scrollEnterDeg = sp.getFloat("scrollEnterDeg", d.scrollEnterDeg),
            presRollIn = enumOf("presRollIn", d.presRollIn),
            presRollOut = enumOf("presRollOut", d.presRollOut),
            presSwipeDeg = sp.getFloat("presSwipeDeg", d.presSwipeDeg),
            presLaserIdleS = sp.getInt("presLaserIdleS", d.presLaserIdleS),
            presMinutes = sp.getInt("presMinutes", d.presMinutes),
            presProfile = sp.getString("presProfile", d.presProfile) ?: d.presProfile,
            presHaptics = sp.getBoolean("presHaptics", d.presHaptics),
        )
    }

    fun save(s: Settings) {
        sp.edit()
            .putFloat("sensitivity", s.sensitivity).putFloat("smoothing", s.smoothing)
            .putFloat("deadzone", s.deadzone).putFloat("accel", s.accel)
            .putInt("sendHz", s.sendHz).putBoolean("invertX", s.invertX).putBoolean("invertY", s.invertY)
            .putString("transport", s.transport.name).putString("wrist", s.wrist.name).putBoolean("setupDone", s.setupDone)
            .putVec("axisRight", s.axisRight).putVec("axisDown", s.axisDown).putVec("axisRoll", s.axisRoll)
            .putBoolean("gesturesEnabled", s.gesturesEnabled)
            .putFloat("tapThreshold", s.tapThreshold).putFloat("rollThreshold", s.rollThreshold)
            .putString("tapAction", s.tapAction.name).putString("doubleTapAction", s.doubleTapAction.name)
            .putString("rollOutAction", s.rollOutAction.name)
            .putString("rollInAction", s.rollInAction.name)
            .putInt("compMs", s.compMs).putBoolean("scrollPose", s.scrollPose)
            .putFloat("scrollEnterDeg", s.scrollEnterDeg)
            .putString("presRollIn", s.presRollIn.name).putString("presRollOut", s.presRollOut.name)
            .putFloat("presSwipeDeg", s.presSwipeDeg).putInt("presLaserIdleS", s.presLaserIdleS).putInt("presMinutes", s.presMinutes)
            .putString("presProfile", s.presProfile).putBoolean("presHaptics", s.presHaptics)
            .apply()
    }

    var btHost: String?
        get() = sp.getString("btHost", null)
        set(v) = sp.edit().putString("btHost", v).apply()

    var lastHost: String?
        get() = sp.getString("lastHost", null)
        set(v) = sp.edit().putString("lastHost", v).apply()
}

data class Paired(val devId: String, val secret: ByteArray)

/** Guarda el secreto de emparejamiento cifrado con una clave del Android Keystore. */
class PairingStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val alias = "airwatch_pairing_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(p: Paired) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val blob = c.iv + c.doFinal(p.secret)
        sp.edit().putString("dev", p.devId).putString("blob", Crypto.b64e(blob)).apply()
    }

    fun load(): Paired? {
        val dev = sp.getString("dev", null) ?: return null
        val blob = sp.getString("blob", null)?.let(Crypto::b64d) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            Paired(dev, c.doFinal(blob, 12, blob.size - 12))
        } catch (_: Exception) {
            null
        }
    }

    fun clear() = sp.edit().clear().apply()
}
