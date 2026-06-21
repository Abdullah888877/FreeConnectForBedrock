package com.freeconnect.bedrock.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.freeconnect.bedrock.data.resourcepack.LocalResourcePack
import com.freeconnect.bedrock.data.resourcepack.ResourcePackDao

/**
 * Single Room database for the application.
 *
 * Entities:
 *  - [ServerEntity]      — saved Bedrock servers
 *  - [LocalResourcePack] — imported .mcpack files
 *
 * Increment [version] and supply a [androidx.room.migration.Migration]
 * whenever the schema changes between releases.
 */
@Database(
    entities  = [ServerEntity::class, LocalResourcePack::class],
    version   = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun resourcePackDao(): ResourcePackDao
}
