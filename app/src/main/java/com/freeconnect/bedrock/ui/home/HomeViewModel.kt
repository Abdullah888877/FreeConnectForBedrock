package com.freeconnect.bedrock.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeconnect.bedrock.data.db.ServerEntity
import com.freeconnect.bedrock.data.repository.ServerRepository
import com.freeconnect.bedrock.service.LanBroadcastService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Home screen.
 *
 * Exposes:
 *  - [servers]             — live list of saved servers from Room.
 *  - [broadcastingServer]  — ID of the server currently being broadcast, or null.
 *  - [snackbarMessage]     — one-shot message to surface in the UI.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ServerRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** All saved servers, observed as a StateFlow from Room. */
    val servers: StateFlow<List<ServerEntity>> = repository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The server ID currently being LAN-broadcast, null if broadcast is off. */
    private val _broadcastingServer = MutableStateFlow<Long?>(null)
    val broadcastingServer: StateFlow<Long?> = _broadcastingServer.asStateFlow()

    /** One-shot snackbar message; read once then clear. */
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /**
     * Delete a server from the database and stop its broadcast if active.
     */
    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            if (_broadcastingServer.value == server.id) {
                stopBroadcast()
            }
            repository.deleteServer(server)
            _snackbarMessage.value = "'${server.name}' deleted"
        }
    }

    /**
     * Toggle the favourite flag of a server.
     */
    fun toggleFavorite(server: ServerEntity) {
        viewModelScope.launch {
            repository.setFavorite(server.id, !server.isFavorite)
        }
    }

    /**
     * Start LAN broadcasting for a specific server.
     * Stops any existing broadcast first.
     */
    fun startBroadcast(server: ServerEntity) {
        stopBroadcast()
        _broadcastingServer.value = server.id
        LanBroadcastService.startBroadcast(
            context    = context,
            name       = server.name,
            ip         = server.ipAddress,
            port       = server.port
        )
        _snackbarMessage.value = "Broadcasting '${server.name}' on LAN"
    }

    /**
     * Stop the active LAN broadcast service.
     */
    fun stopBroadcast() {
        if (_broadcastingServer.value != null) {
            LanBroadcastService.stopBroadcast(context)
            _broadcastingServer.value = null
            _snackbarMessage.value = "Broadcast stopped"
        }
    }

    /**
     * Record the connection timestamp when the user taps Connect.
     */
    fun markConnected(server: ServerEntity) {
        viewModelScope.launch {
            repository.markConnected(server.id)
        }
    }

    /** Clear the snackbar message after it has been shown. */
    fun clearSnackbar() {
        _snackbarMessage.value = null
    }
}
