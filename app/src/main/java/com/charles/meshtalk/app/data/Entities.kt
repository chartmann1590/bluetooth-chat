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
    val contentType: String = "TEXT", // "TEXT", "IMAGE", or "FILE"
    val body: String, // text body for TEXT, a short display caption (e.g. filename) for IMAGE/FILE
    val mediaBytes: ByteArray? = null, // raw bytes for IMAGE/FILE, ≤ ~200KB by policy
    val mediaMimeType: String? = null,
    val mediaFilename: String? = null, // FILE only
    val timestamp: Long,
    val isMine: Boolean,
    val delivered: Boolean
)
