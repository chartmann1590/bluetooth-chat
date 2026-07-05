package com.charles.meshtalk.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE signingPubKeyHex = :keyHex LIMIT 1")
    suspend fun getByKey(keyHex: String): ContactEntity?
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE type = 'PUBLIC' ORDER BY timestamp ASC")
    fun observePublicFeed(): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE type = 'DM' AND peerPubKeyHex = :peerKeyHex ORDER BY timestamp ASC"
    )
    fun observeDmThread(peerKeyHex: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT DISTINCT peerPubKeyHex FROM messages WHERE type = 'DM' AND peerPubKeyHex IS NOT NULL"
    )
    fun observeDmPeerKeys(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT senderPubKeyHex FROM messages WHERE id = :id LIMIT 1")
    suspend fun senderOf(id: String): String?

    @Query("UPDATE messages SET body = :newText, edited = 1 WHERE id = :id")
    suspend fun editText(id: String, newText: String)

    @Query("UPDATE messages SET deleted = 1, body = '', mediaBytes = NULL, mediaFilename = NULL WHERE id = :id")
    suspend fun markDeleted(id: String)
}

@Dao
interface ReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND reactorPubKeyHex = :reactorPubKeyHex")
    suspend fun remove(messageId: String, reactorPubKeyHex: String)

    @Query("SELECT * FROM reactions WHERE messageId = :messageId")
    suspend fun forMessageOnce(messageId: String): List<ReactionEntity>

    @Query("SELECT * FROM reactions")
    fun observeAll(): Flow<List<ReactionEntity>>
}

@Dao
interface ReadReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: ReadReceiptEntity)

    @Query("SELECT * FROM read_receipts WHERE messageId = :messageId ORDER BY timestamp ASC")
    fun observeReadersFor(messageId: String): Flow<List<ReadReceiptEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM read_receipts WHERE messageId = :messageId AND readerPubKeyHex = :readerPubKeyHex)")
    suspend fun hasRead(messageId: String, readerPubKeyHex: String): Boolean
}
