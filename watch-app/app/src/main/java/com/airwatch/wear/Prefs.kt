package com.airwatch.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ajustes del air mouse (se persisten en SharedPreferences). */
data class Settings(
    val sensitivity: Float = 1.0f,  // multiplicador 0.2..3.0 sobre ~1200 px/rad
    val smoothing: Float = 0.35f,   // 0 = sin filtro .. 0.9 = muy suave (low-pass exponencial)
    val deadzone: Float = 0.06f,    // rad/s ignorados (anti-temblor)
    val accel: Float = 0.4f,        // 0 = lineal .. 2 = mucha aceleracion
    val sendHz: Int = 80,           // 60..100 Hz de envio
    val invertX: Boolean = false,
    val invertY: Boolean = false,
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        sensitivity = sp.getFloat("sensitivity", 1.0f),
        smoothing = sp.getFloat("smoothing", 0.35f),
        deadzone = sp.getFloat("deadzone", 0.06f),
        accel = sp.getFloat("accel", 0.4f),
        sendHz = sp.getInt("sendHz", 80),
        invertX = sp.getBoolean("invertX", false),
        invertY = sp.getBoolean("invertY", false),
    )

    fun save(s: Settings) = sp.edit()
        .putFloat("sensitivity", s.sensitivity).putFloat("smoothing", s.smoothing)
        .putFloat("deadzone", s.deadzone).putFloat("accel", s.accel)
        .putInt("sendHz", s.sendHz).putBoolean("invertX", s.invertX).putBoolean("invertY", s.invertY)
        .apply()

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
