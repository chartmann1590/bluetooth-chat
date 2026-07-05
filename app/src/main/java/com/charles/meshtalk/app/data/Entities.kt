package com.charles.meshtalk.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val signingPubKeyHex: String,
    val agreementPubKeyHex: String,
    val nickname: String,
    val lastSeen: Long
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val type: String, // "PUBLIC" or "DM"
    val senderPubKeyHex: String,
    val senderNickname: String,
    val peerPubKeyHex: String?, // null for PUBLIC; the other party's key for DM
    val contentType: String = "TEXT", // "TEXT", "IMAGE", "FILE", or "LOCATION"
    val body: String, // text body for TEXT, a short display caption otherwise
    val mediaBytes: ByteArray? = null, // raw bytes for IMAGE/FILE, or a static map thumbnail for LOCATION
    val mediaMimeType: String? = null,
    val mediaFilename: String? = null, // FILE only
    val latitude: Double? = null, // LOCATION only
    val longitude: Double? = null, // LOCATION only
    val timestamp: Long,
    val isMine: Boolean,
    val delivered: Boolean
)

/** Records that [readerPubKeyHex] has read the message [messageId]; the pair is the primary key
 * so re-receiving the same peer's receipt for the same message is a natural no-op. */
@Entity(tableName = "read_receipts", primaryKeys = ["messageId", "readerPubKeyHex"])
data class ReadReceiptEntity(
    val messageId: String,
    val readerPubKeyHex: String,
    val readerNickname: String,
    val timestamp: Long
)
