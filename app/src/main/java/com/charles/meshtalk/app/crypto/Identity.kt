package com.charles.meshtalk.app.crypto

import android.content.Context
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.security.SecureRandom

/**
 * Persistent on-device identity: an Ed25519 signing keypair and an X25519
 * key-agreement keypair. Generated once on first launch and reused across
 * app restarts so contacts/DMs stay valid.
 */
class Identity private constructor(
    val signingPublicKey: ByteArray,
    private val signingPrivateKey: Ed25519PrivateKeyParameters,
    val agreementPublicKey: ByteArray,
    private val agreementPrivateKey: X25519PrivateKeyParameters,
    val nickname: String
) {
    val publicKeyHex: String get() = signingPublicKey.toHex()

    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, signingPrivateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /** Raw X25519 ECDH shared secret with a peer's agreement public key. */
    fun deriveSharedSecret(peerAgreementPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(agreementPrivateKey)
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerAgreementPublicKey, 0), out, 0)
        return out
    }

    companion object {
        private const val FILE_NAME = "identity.dat"

        fun verify(signingPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            return try {
                val verifier = Ed25519Signer()
                verifier.init(false, Ed25519PublicKeyParameters(signingPublicKey, 0))
                verifier.update(data, 0, data.size)
                verifier.verifySignature(signature)
            } catch (e: Exception) {
                false
            }
        }

        fun exists(context: Context): Boolean = File(context.filesDir, FILE_NAME).exists()

        fun loadOrCreate(context: Context, nickname: String): Identity {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) return load(file)
            return create(file, nickname)
        }

        private fun create(file: File, nickname: String): Identity {
            val random = SecureRandom()

            val edGen = Ed25519KeyPairGenerator()
            edGen.init(Ed25519KeyGenerationParameters(random))
            val edPair = edGen.generateKeyPair()
            val edPriv = edPair.private as Ed25519PrivateKeyParameters
            val edPub = (edPair.public as Ed25519PublicKeyParameters).encoded

            val xGen = X25519KeyPairGenerator()
            xGen.init(X25519KeyGenerationParameters(random))
            val xPair = xGen.generateKeyPair()
            val xPriv = xPair.private as X25519PrivateKeyParameters
            val xPub = (xPair.public as X25519PublicKeyParameters).encoded

            val nicknameBytes = nickname.toByteArray(Charsets.UTF_8)
            val buffer = java.nio.ByteBuffer.allocate(4 + nicknameBytes.size + 32 + 32)
            buffer.putInt(nicknameBytes.size)
            buffer.put(nicknameBytes)
            buffer.put(edPriv.encoded)
            buffer.put(xPriv.encoded)
            file.writeBytes(buffer.array())

            return Identity(edPub, edPriv, xPub, xPriv, nickname)
        }

        private fun load(file: File): Identity {
            val bytes = file.readBytes()
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            val nickLen = buffer.int
            val nickBytes = ByteArray(nickLen)
            buffer.get(nickBytes)
            val edSeed = ByteArray(32)
            buffer.get(edSeed)
            val xSeed = ByteArray(32)
            buffer.get(xSeed)

            val edPriv = Ed25519PrivateKeyParameters(edSeed, 0)
            val edPub = edPriv.generatePublicKey().encoded
            val xPriv = X25519PrivateKeyParameters(xSeed, 0)
            val xPub = xPriv.generatePublicKey().encoded

            return Identity(edPub, edPriv, xPub, xPriv, String(nickBytes, Charsets.UTF_8))
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0)
    return ByteArray(length / 2) { i -> ((this[i * 2].digitToInt(16) shl 4) + this[i * 2 + 1].digitToInt(16)).toByte() }
}
