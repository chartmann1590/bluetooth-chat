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
    val body: String,
    val timestamp: Long,
    val isMine: Boolean,
    val delivered: Boolean
)
