package com.freeconnect.bedrock.data.resourcepack

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ResourcePackRepo"

/**
 * Repository for managing locally-stored Bedrock resource packs.
 *
 * Packs are imported from .mcpack files (which are ZIP archives) and stored
 * in the app's private files directory under "resource_packs/".
 * The manifest.json inside the ZIP is parsed to extract uuid, version, name,
 * and description.
 */
@Singleton
class ResourcePackRepository @Inject constructor(
    private val dao: ResourcePackDao,
    @ApplicationContext private val context: Context
) {
    /** Root directory for stored pack files. */
    private val packsDir: File get() =
        File(context.filesDir, "resource_packs").also { it.mkdirs() }

    /** Live stream of all packs. */
    val allPacks: Flow<List<LocalResourcePack>> = dao.getAllPacks()

    /** Live stream of enabled packs (for proxy injection). */
    val enabledPacks: Flow<List<LocalResourcePack>> = dao.getEnabledPacks()

    // ─────────────────────────────────────────────────────────────────────────
    // Import
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Import a resource pack from a content URI (e.g. from a file picker).
     * The pack must be a .mcpack file (ZIP containing manifest.json).
     *
     * @return Result with the imported [LocalResourcePack] or an error message.
     */
    suspend fun importPack(uri: Uri): Result<LocalResourcePack> = withContext(Dispatchers.IO) {
        try {
            // Copy stream into a temp file so we can read it twice (once for manifest, once to copy)
            val tempFile = File(packsDir, "temp_import.mcpack")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open file"))

            // Parse manifest.json from the ZIP
            val manifest = parseManifest(tempFile)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid .mcpack — no manifest.json found"))

            val uuid        = manifest.optString("uuid", "")
            val version     = manifest.optString("version", "1.0.0")
            val name        = manifest.optString("name", "Unknown Pack")
            val description = manifest.optString("description", "")

            if (uuid.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("manifest.json missing UUID"))
            }

            // Move to permanent location using the UUID as the filename
            val packFile = File(packsDir, "$uuid.mcpack")
            tempFile.renameTo(packFile)

            val pack = LocalResourcePack(
                name        = name,
                uuid        = uuid,
                version     = version,
                filePath    = packFile.absolutePath,
                sizeBytes   = packFile.length(),
                description = description,
                isEnabled   = true
            )

            val id = dao.insertPack(pack)
            Log.i(TAG, "Imported pack '$name' ($uuid) — ${packFile.length()} bytes")
            Result.success(pack.copy(id = id))

        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun toggleEnabled(pack: LocalResourcePack) =
        dao.setEnabled(pack.id, !pack.isEnabled)

    /**
     * Delete a pack record and its file from storage.
     */
    suspend fun deletePack(pack: LocalResourcePack) = withContext(Dispatchers.IO) {
        dao.deletePack(pack)
        val file = File(pack.filePath)
        if (file.exists()) file.delete()
        Log.i(TAG, "Deleted pack '${pack.name}'")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract and parse the manifest.json from a .mcpack (ZIP) file.
     * Returns a JSONObject with header fields, or null if not found.
     */
    private fun parseManifest(zipFile: File): JSONObject? {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.equals("manifest.json", ignoreCase = true) ||
                    entry.name.endsWith("/manifest.json")
                ) {
                    val text = zis.readBytes().toString(Charsets.UTF_8)
                    return try {
                        val root = JSONObject(text)
                        // Bedrock manifest structure: { "header": { "uuid", "version", "name" }, ... }
                        val header = root.optJSONObject("header") ?: return null
                        val versionArray = header.optJSONArray("version")
                        val versionStr = if (versionArray != null) {
                            "${versionArray.getInt(0)}.${versionArray.getInt(1)}.${versionArray.getInt(2)}"
                        } else {
                            header.optString("version", "1.0.0")
                        }
                        JSONObject().apply {
                            put("uuid",        header.optString("uuid", ""))
                            put("version",     versionStr)
                            put("name",        header.optString("name", "Unknown Pack"))
                            put("description", header.optString("description", ""))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse manifest.json: ${e.message}")
                        null
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }
}
