package com.freeconnect.bedrock.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a saved Minecraft Bedrock server.
 *
 * @property id         Auto-generated primary key.
 * @property name       Display name chosen by the user.
 * @property ipAddress  IPv4 or IPv6 address (or hostname) of the server.
 * @property port       UDP port — Bedrock default is 19132.
 * @property lastConnected Timestamp (ms) of the most recent connection, or null if never connected.
 * @property isFavorite Whether the user marked this server as a favourite.
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "ip_address")
    val ipAddress: String,

    @ColumnInfo(name = "port")
    val port: Int = 19132,

    @ColumnInfo(name = "last_connected")
    val lastConnected: Long? = null,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false
)
