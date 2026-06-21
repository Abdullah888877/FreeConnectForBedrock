package com.freeconnect.bedrock.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [ServerEntity].
 * All database interactions for the servers table are defined here.
 */
@Dao
interface ServerDao {

    /** Observe all servers; favorites appear first, then sorted by name. */
    @Query("SELECT * FROM servers ORDER BY is_favorite DESC, name ASC")
    fun getAllServers(): Flow<List<ServerEntity>>

    /** Observe a single server by its primary key. */
    @Query("SELECT * FROM servers WHERE id = :id")
    fun getServerById(id: Long): Flow<ServerEntity?>

    /**
     * Insert a new server. Returns the auto-generated row ID.
     * Uses REPLACE strategy so an upsert is possible if id > 0.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity): Long

    /** Update an existing server record. */
    @Update
    suspend fun updateServer(server: ServerEntity)

    /** Delete a specific server. */
    @Delete
    suspend fun deleteServer(server: ServerEntity)

    /** Delete all servers — used during restore from backup. */
    @Query("DELETE FROM servers")
    suspend fun deleteAllServers()

    /**
     * Update the last-connected timestamp and toggle favorite
     * without loading the full entity.
     */
    @Query("UPDATE servers SET last_connected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)

    /** Toggle the favourite flag for a server. */
    @Query("UPDATE servers SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    /** Return a plain list — used for JSON backup serialisation. */
    @Query("SELECT * FROM servers ORDER BY name ASC")
    suspend fun getAllServersSnapshot(): List<ServerEntity>
}
