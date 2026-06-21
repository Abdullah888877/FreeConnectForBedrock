package com.freeconnect.bedrock.data.resourcepack

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a locally-stored Bedrock resource pack (.mcpack / .zip).
 *
 * The pack file is stored under [filePath] in the app's private files directory.
 * The [uuid] and [version] fields are read from the pack's manifest.json and
 * are required for Bedrock's resource pack negotiation protocol.
 */
@Entity(tableName = "resource_packs")
data class LocalResourcePack(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Display name (from manifest or user-chosen). */
    @ColumnInfo(name = "name")
    val name: String,

    /** UUID from manifest.json — used in Bedrock pack negotiation. */
    @ColumnInfo(name = "uuid")
    val uuid: String,

    /** Version string from manifest.json (e.g. "1.0.0"). */
    @ColumnInfo(name = "version")
    val version: String,

    /** Absolute path to the unpacked or raw .mcpack file on device. */
    @ColumnInfo(name = "file_path")
    val filePath: String,

    /** File size in bytes — sent in the Resource Pack Info packet. */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0L,

    /** Whether this pack is enabled for injection during proxy sessions. */
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    /** Description from the manifest, shown in the UI. */
    @ColumnInfo(name = "description")
    val description: String = "",

    /** Import timestamp (ms). */
    @ColumnInfo(name = "imported_at")
    val importedAt: Long = System.currentTimeMillis()
)
