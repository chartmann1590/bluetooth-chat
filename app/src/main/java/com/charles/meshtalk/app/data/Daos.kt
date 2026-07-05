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
}
