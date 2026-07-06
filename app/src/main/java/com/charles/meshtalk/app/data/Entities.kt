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
    val delivered: Boolean,
    val edited: Boolean = false,
    val deleted: Boolean = false
)

/** One reaction from one person on one message; re-reacting with the same emoji is a toggle
 * (handled at the repository layer), so (messageId, reactorPubKeyHex) is the primary key — a
 * given person has at most one active emoji reaction per message. */
@Entity(tableName = "reactions", primaryKeys = ["messageId", "reactorPubKeyHex"])
data class ReactionEntity(
    val messageId: String,
    val reactorPubKeyHex: String,
    val reactorNickname: String,
    val emoji: String,
    val timestamp: Long
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

/** A saved on-device AI chat conversation — local-only, never touches the mesh. [title] defaults
 * to a truncated copy of the first message so the history list is scannable at a glance. */
@Entity(tableName = "ai_sessions")
data class AiSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "ai_messages")
data class AiMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val fromUser: Boolean,
    val text: String,
    val imageBytes: ByteArray? = null,
    val timestamp: Long
)
