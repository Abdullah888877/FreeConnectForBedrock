package com.freeconnect.bedrock.data.resourcepack

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for locally-stored resource packs.
 */
@Dao
interface ResourcePackDao {

    /** Observe all packs ordered by name. */
    @Query("SELECT * FROM resource_packs ORDER BY name ASC")
    fun getAllPacks(): Flow<List<LocalResourcePack>>

    /** Observe only the enabled packs (used for proxy injection). */
    @Query("SELECT * FROM resource_packs WHERE is_enabled = 1 ORDER BY name ASC")
    fun getEnabledPacks(): Flow<List<LocalResourcePack>>

    /** Get a single pack by ID. */
    @Query("SELECT * FROM resource_packs WHERE id = :id")
    suspend fun getPackById(id: Long): LocalResourcePack?

    /** Insert a new pack, returns row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPack(pack: LocalResourcePack): Long

    /** Update pack metadata (e.g., toggle enabled). */
    @Update
    suspend fun updatePack(pack: LocalResourcePack)

    /** Delete a pack record. */
    @Delete
    suspend fun deletePack(pack: LocalResourcePack)

    /** Toggle the enabled flag. */
    @Query("UPDATE resource_packs SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
