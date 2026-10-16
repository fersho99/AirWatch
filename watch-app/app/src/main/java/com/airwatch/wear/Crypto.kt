package com.airwatch.wear

import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Espejo exacto de pc-server/airwatch_server/crypto.py (protocolo v1).
 * Cualquier cambio aqui debe hacerse tambien alla.
 */
object Crypto {
    const val SERVER_SID_MASK = 0xFFFFFFFF.toInt()
    const val HEADER_LEN = 12
    const val TAG_LEN = 16

    private val rnd = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun b64d(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    // ------------------------------------------------------------------ ECDH P-256
    class Ephemeral(val keyPair: KeyPair) {
        val priv: PrivateKey get() = keyPair.private
        val pubRaw: ByteArray = run {
            val w = (keyPair.public as ECPublicKey).w
            byteArrayOf(0x04) + fixed32(w.affineX) + fixed32(w.affineY)
        }
    }

    fun newKeyPair(): Ephemeral {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return Ephemeral(g.generateKeyPair())
    }

    fun ecdh(me: Ephemeral, peerRaw: ByteArray): ByteArray {
        require(peerRaw.size == 65 && peerRaw[0] == 0x04.toByte()) { "clave publica invalida" }
        val params = (me.keyPair.public as ECPublicKey).params
        val point = ECPoint(BigInteger(1, peerRaw.copyOfRange(1, 33)), BigInteger(1, peerRaw.copyOfRange(33, 65)))
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(me.priv)
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) System.arraycopy(raw, raw.size - 32, out, 0, 32)
        else System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        return out
    }

    /** Devuelve (secreto de emparejamiento, SAS de 6 digitos). */
    fun pairingMaterial(shared: ByteArray, watchPub: ByteArray, serverPub: ByteArray): Pair<ByteArray, String> {
        val salt = sha256(watchPub, serverPub)
        val secret = hkdf(shared, salt, "airwatch pairing secret".toByteArray())
        val n = ByteBuffer.wrap(hkdf(shared, salt, "airwatch sas".toByteArray(), 4)).int.toLong() and 0xFFFFFFFFL
        return secret to "%06d".format(n % 1_000_000L)
    }

    fun deviceId(watchPub: ByteArray): String =
        sha256(watchPub).copyOfRange(0, 8).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ Sesion
    fun sessionKeys(secret: ByteArray, cn: ByteArray, sn: ByteArray): Pair<ByteArray, Int> {
        val salt = cn + sn
        val key = hkdf(secret, salt, "airwatch session key".toByteArray())
        val sid = ByteBuffer.wrap(hkdf(secret, salt, "airwatch session id".toByteArray(), 4)).int
        return key to sid
    }

    fun serverProof(secret: ByteArray, cn: ByteArray, sn: ByteArray) = hmac(secret, "srv".toByteArray() + cn + sn)

    fun seal(key: ByteArray, sid: Int, ctr: Long, plaintext: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(HEADER_LEN).putInt(sid).putLong(ctr).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header))
        c.updateAAD(header)
        return header + c.doFinal(plaintext)
    }

    class Opened(val sid: Int, val ctr: Long, val plain: ByteArray)

    /** null si la trama es corta o no autentica. */
    fun open(key: ByteArray, frame: ByteArray, length: Int = frame.size): Opened? {
        if (length < HEADER_LEN + TAG_LEN) return null
        val header = frame.copyOfRange(0, HEADER_LEN)
        val bb = ByteBuffer.wrap(header)
        val sid = bb.int
        val ctr = bb.long
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header))
            c.updateAAD(header)
            Opened(sid, ctr, c.doFinal(frame, HEADER_LEN, length - HEADER_LEN))
        } catch (_: Exception) {
            null
        }
    }
}

/** Ventana anti-replay deslizante (misma logica que el servidor). */
class ReplayWindow(private val size: Int = 128) {
    private var top = -1L
    private var bits = BigInteger.ZERO
    private val mask = BigInteger.ONE.shiftLeft(size).subtract(BigInteger.ONE)

    @Synchronized
    fun checkAndUpdate(n: Long): Boolean {
        if (n > top) {
            bits = if (top < 0) BigInteger.ONE
            else bits.shiftLeft((n - top).toInt().coerceAtMost(size)).or(BigInteger.ONE).and(mask)
            top = n
            return true
        }
        val offset = (top - n)
        if (offset >= size || bits.testBit(offset.toInt())) return false
        bits = bits.setBit(offset.toInt())
        return true
    }
}
