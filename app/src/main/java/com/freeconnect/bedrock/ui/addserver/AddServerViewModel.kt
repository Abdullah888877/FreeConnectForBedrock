package com.freeconnect.bedrock.ui.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeconnect.bedrock.data.db.ServerEntity
import com.freeconnect.bedrock.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for both the Add Server and Edit Server screens.
 *
 * Call [loadServer] with a non-null ID to enter edit mode.
 * Call [saveServer] to persist the form.
 */
@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val repository: ServerRepository
) : ViewModel() {

    // ── Form fields ───────────────────────────────────────────────────────────
    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    private val _ipAddress = MutableStateFlow("")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    private val _port = MutableStateFlow("19132")
    val port: StateFlow<String> = _port.asStateFlow()

    // ── Validation errors ─────────────────────────────────────────────────────
    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError.asStateFlow()

    private val _ipError = MutableStateFlow<String?>(null)
    val ipError: StateFlow<String?> = _ipError.asStateFlow()

    private val _portError = MutableStateFlow<String?>(null)
    val portError: StateFlow<String?> = _portError.asStateFlow()

    // ── State ─────────────────────────────────────────────────────────────────
    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Non-null when editing an existing server. */
    private var editingServer: ServerEntity? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Field updates
    // ─────────────────────────────────────────────────────────────────────────

    fun onNameChange(value: String) {
        _serverName.value = value
        _nameError.value = null
    }

    fun onIpChange(value: String) {
        _ipAddress.value = value
        _ipError.value = null
    }

    fun onPortChange(value: String) {
        _port.value = value
        _portError.value = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Load an existing server for editing
    // ─────────────────────────────────────────────────────────────────────────

    fun loadServer(id: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getServerById(id).first()?.let { server ->
                editingServer = server
                _serverName.value = server.name
                _ipAddress.value = server.ipAddress
                _port.value = server.port.toString()
            }
            _isLoading.value = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save / validation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validate form fields and persist the server.
     * Sets [isSaved] to true on success so the screen can navigate back.
     */
    fun saveServer() {
        val name = _serverName.value.trim()
        val ip   = _ipAddress.value.trim()
        val portStr = _port.value.trim()

        var valid = true

        // Validate name
        if (name.isBlank()) {
            _nameError.value = "Server name is required"
            valid = false
        } else if (name.length > 64) {
            _nameError.value = "Name must be 64 characters or fewer"
            valid = false
        }

        // Validate IP
        if (ip.isBlank()) {
            _ipError.value = "IP address is required"
            valid = false
        } else if (!isValidHost(ip)) {
            _ipError.value = "Enter a valid IP address or hostname"
            valid = false
        }

        // Validate port
        val portNum = portStr.toIntOrNull()
        if (portNum == null || portNum !in 1..65535) {
            _portError.value = "Port must be between 1 and 65535"
            valid = false
        }

        if (!valid) return

        viewModelScope.launch {
            val existing = editingServer
            if (existing != null) {
                // Update mode — preserve ID, favourite, and last-connected
                repository.updateServer(
                    existing.copy(
                        name      = name,
                        ipAddress = ip,
                        port      = portNum!!
                    )
                )
            } else {
                // Insert mode
                repository.addServer(
                    ServerEntity(
                        name      = name,
                        ipAddress = ip,
                        port      = portNum!!
                    )
                )
            }
            _isSaved.value = true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Basic hostname/IP validation.
     * Accepts IPv4 dotted-decimal, IPv6, or a simple hostname pattern.
     */
    private fun isValidHost(host: String): Boolean {
        if (host.length > 253) return false
        // Allow IPv6 bracketed form
        if (host.startsWith("[") && host.endsWith("]")) return true
        // Allow hostname labels: letters, digits, hyphens, dots
        val hostRegex = Regex("""^[a-zA-Z0-9]([a-zA-Z0-9\-\.]*[a-zA-Z0-9])?$""")
        return hostRegex.matches(host) || host == "localhost"
    }
}
