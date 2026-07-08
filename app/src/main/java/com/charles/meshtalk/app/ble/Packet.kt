package com.charles.meshtalk.app.ble

enum class PacketType(val code: Byte) {
    ANNOUNCE(1),
    PUBLIC(2),
    DM(3),
    READ_RECEIPT(4),
    TYPING(5),
    EDIT(6),
    DELETE(7),
    REACTION(8),
    VOICE(9);

    companion object {
        fun fromCode(code: Byte): PacketType = entries.first { it.code == code }
    }
}

/** Zero-filled recipient key used for broadcast (ANNOUNCE/PUBLIC, and public READ_RECEIPTs) packets. */
val BROADCAST_KEY: ByteArray = ByteArray(32)

// v5: added EDIT/DELETE/REACTION packet types for message actions.
// v6: added VOICE packet type for push-to-talk voice messages.
const val PACKET_VERSION: Byte = 6

/**
 * A single mesh packet. `senderSigningPubKey` is the sender's persistent
 * Ed25519 identity key, `recipientSigningPubKey` is [BROADCAST_KEY] for
 * ANNOUNCE/PUBLIC or a specific peer's identity key for DM.
 */
data class Packet(
    val version: Byte = PACKET_VERSION,
    val type: PacketType,
    var ttl: Int,
    val messageId: ByteArray,
    val timestamp: Long,
    val senderSigningPubKey: ByteArray,
    val recipientSigningPubKey: ByteArray,
    val payload: ByteArray,
    val signature: ByteArray
) {
    val messageIdHex: String get() = messageId.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean = other is Packet && other.messageId.contentEquals(messageId)
    override fun hashCode(): Int = messageId.contentHashCode()
}
