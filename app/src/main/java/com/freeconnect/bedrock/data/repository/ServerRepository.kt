package com.freeconnect.bedrock.data.repository

import com.freeconnect.bedrock.data.db.ServerDao
import com.freeconnect.bedrock.data.db.ServerEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that abstracts the data layer from the ViewModels.
 * All server persistence operations go through here.
 */
@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao
) {

    /** Live stream of all saved servers (favorites first, then alphabetical). */
    val allServers: Flow<List<ServerEntity>> = serverDao.getAllServers()

    /** Live stream of a single server by ID. */
    fun getServerById(id: Long): Flow<ServerEntity?> = serverDao.getServerById(id)

    /** Insert a brand-new server. Returns the generated ID. */
    suspend fun addServer(server: ServerEntity): Long = serverDao.insertServer(server)

    /** Persist changes to an existing server. */
    suspend fun updateServer(server: ServerEntity) = serverDao.updateServer(server)

    /** Remove a server permanently. */
    suspend fun deleteServer(server: ServerEntity) = serverDao.deleteServer(server)

    /** Record a successful connection timestamp. */
    suspend fun markConnected(id: Long) =
        serverDao.updateLastConnected(id, System.currentTimeMillis())

    /** Toggle the favourite flag. */
    suspend fun setFavorite(id: Long, isFavorite: Boolean) =
        serverDao.setFavorite(id, isFavorite)

    /** Snapshot list for JSON export. */
    suspend fun getAllServersForBackup(): List<ServerEntity> =
        serverDao.getAllServersSnapshot()

    /**
     * Restore from backup: wipe the current list and insert the
     * provided servers (IDs are reset so Room auto-generates new ones).
     */
    suspend fun restoreFromBackup(servers: List<ServerEntity>) {
        serverDao.deleteAllServers()
        servers.forEach { server ->
            serverDao.insertServer(server.copy(id = 0))
        }
    }
}
