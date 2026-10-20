package com.tclstream.tv

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/** This TV's identity and the keys of the PCs it is paired with (app-private storage). */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("jinky_pairing", Context.MODE_PRIVATE)

    /** Random, stable id for this TV, created on first use. */
    fun deviceId(): Long {
        val existing = prefs.getLong(KEY_DEVICE, 0L)
        if (existing != 0L) return existing
        var id = 0L
        while (id == 0L) id = SecureRandom().nextLong()
        prefs.edit().putLong(KEY_DEVICE, id).apply()
        return id
    }

    fun hosts(): Map<Long, ByteArray> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith(HOST_PREFIX) || v !is String) return@mapNotNull null
        val id = java.lang.Long.parseUnsignedLong(k.removePrefix(HOST_PREFIX), 16)
        id to Base64.decode(v, Base64.NO_WRAP)
    }.toMap()

    fun save(hostId: Long, key: ByteArray) {
        prefs.edit().putString(HOST_PREFIX + java.lang.Long.toHexString(hostId), Base64.encodeToString(key, Base64.NO_WRAP)).apply()
    }

    fun remove(hostId: Long) {
        prefs.edit().remove(HOST_PREFIX + java.lang.Long.toHexString(hostId)).apply()
    }

    companion object {
        private const val KEY_DEVICE = "device_id"
        private const val HOST_PREFIX = "host_"
    }
}

/**
 * One pairing attempt: an ephemeral P-256 key pair. The shared secret (with the PC's public key)
 * goes to the native side, which derives the pair key and the 6-digit code exactly like the PC.
 */
class PairingSession {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
    }.generateKeyPair()
    private val params = (keyPair.public as ECPublicKey).params

    /** Uncompressed point 0x04 || X || Y (65 bytes), the format the PC expects. */
    val publicKey: ByteArray = (keyPair.public as ECPublicKey).w.let { byteArrayOf(4) + fixed32(it.affineX) + fixed32(it.affineY) }

    /** ECDH with the PC's 65-byte public key: the big-endian X coordinate (32 bytes). */
    fun secretWith(pcPublic: ByteArray): ByteArray {
        require(pcPublic.size == 65 && pcPublic[0] == 4.toByte())
        val point = ECPoint(BigInteger(1, pcPublic.copyOfRange(1, 33)), BigInteger(1, pcPublic.copyOfRange(33, 65)))
        val pcKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(pcKey, true)
        val s = ka.generateSecret()
        return if (s.size >= 32) s.copyOfRange(s.size - 32, s.size) else ByteArray(32 - s.size) + s
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)  // drop the sign byte
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}
