package com.freeconnect.bedrock.ui.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeconnect.bedrock.data.db.ServerEntity
import com.freeconnect.bedrock.data.repository.ServerRepository
import com.freeconnect.bedrock.service.LanBroadcastService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 *
 * Manages:
 *  - Dark mode toggle (persisted in DataStore)
 *  - Auto-start LAN broadcast toggle (persisted in DataStore)
 *  - JSON backup export and import
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val repository: ServerRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── DataStore keys ────────────────────────────────────────────────────────
    companion object {
        val KEY_DARK_MODE      = booleanPreferencesKey("dark_mode")
        val KEY_AUTO_BROADCAST = booleanPreferencesKey("auto_broadcast")
    }

    // ── Preferences flows ─────────────────────────────────────────────────────

    /** True = dark theme. Defaults to true (dark by default per spec). */
    val isDarkMode: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_DARK_MODE] ?: true }
        .let { flow ->
            val state = MutableStateFlow(true)
            viewModelScope.launch { flow.collect { state.value = it } }
            state
        }

    /** True = automatically start LAN broadcast on app open. */
    val autoStartBroadcast: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_AUTO_BROADCAST] ?: false }
        .let { flow ->
            val state = MutableStateFlow(false)
            viewModelScope.launch { flow.collect { state.value = it } }
            state
        }

    // ── Feedback messages ─────────────────────────────────────────────────────
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Toggle actions
    // ─────────────────────────────────────────────────────────────────────────

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_DARK_MODE] = enabled }
        }
    }

    fun setAutoStartBroadcast(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { it[KEY_AUTO_BROADCAST] = enabled }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backup / Restore
    // ─────────────────────────────────────────────────────────────────────────

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Export all servers to a JSON file at the given URI.
     * The URI should come from an ACTION_CREATE_DOCUMENT intent result.
     */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val servers = repository.getAllServersForBackup()
                val json    = gson.toJson(servers)

                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream).use { writer ->
                        writer.write(json)
                    }
                }
                _message.value = "Exported ${servers.size} server(s)"
            } catch (e: Exception) {
                _message.value = "Export failed: ${e.message}"
            }
        }
    }

    /**
     * Import servers from a JSON file at the given URI.
     * The URI should come from an ACTION_OPEN_DOCUMENT intent result.
     * All existing servers are replaced.
     */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).readText()
                } ?: throw IllegalStateException("Could not read file")

                val type    = object : TypeToken<List<ServerEntity>>() {}.type
                val servers = gson.fromJson<List<ServerEntity>>(json, type)

                repository.restoreFromBackup(servers)
                _message.value = "Imported ${servers.size} server(s)"
            } catch (e: Exception) {
                _message.value = "Import failed: ${e.message}"
            }
        }
    }

    /** Stop the LAN broadcast service. */
    fun stopBroadcast() {
        LanBroadcastService.stopBroadcast(context)
    }

    fun clearMessage() {
        _message.value = null
    }
}
