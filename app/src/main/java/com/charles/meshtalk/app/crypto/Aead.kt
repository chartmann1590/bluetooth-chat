package com.charles.meshtalk.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM encryption for DMs, keyed from an X25519 ECDH shared secret via HKDF. */
object Aead {
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12

    fun deriveKey(sharedSecret: ByteArray): SecretKeySpec {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, null, "meshtalk-dm-v1".toByteArray()))
        val keyBytes = ByteArray(32)
        hkdf.generateBytes(keyBytes, 0, 32)
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Returns nonce || ciphertext(+tag). */
    fun encrypt(key: SecretKeySpec, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    fun decrypt(key: SecretKeySpec, nonceAndCiphertext: ByteArray): ByteArray {
        val nonce = nonceAndCiphertext.copyOfRange(0, NONCE_BYTES)
        val ciphertext = nonceAndCiphertext.copyOfRange(NONCE_BYTES, nonceAndCiphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }
}
