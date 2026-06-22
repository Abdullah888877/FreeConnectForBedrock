package com.freeconnect.bedrock.ui.resourcepack

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeconnect.bedrock.data.repository.ServerRepository
import com.freeconnect.bedrock.data.resourcepack.LocalResourcePack
import com.freeconnect.bedrock.data.resourcepack.ResourcePackRepository
import com.freeconnect.bedrock.network.ResourcePackProxy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Resource Pack screen.
 *
 * Manages:
 *  - Listing and importing resource packs
 *  - Enabling/disabling packs for injection
 *  - Starting/stopping the UDP proxy that injects packs into a server session
 */
@HiltViewModel
class ResourcePackViewModel @Inject constructor(
    private val repository: ResourcePackRepository,
    private val serverRepository: ServerRepository,
    private val proxy: ResourcePackProxy
) : ViewModel() {

    // ── Pack list ─────────────────────────────────────────────────────────────

    /** All locally stored packs. */
    val packs: StateFlow<List<LocalResourcePack>> = repository.allPacks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Proxy state ───────────────────────────────────────────────────────────

    private val _proxyStatus = MutableStateFlow("Proxy stopped")
    val proxyStatus: StateFlow<String> = _proxyStatus.asStateFlow()

    private val _isProxyRunning = MutableStateFlow(false)
    val isProxyRunning: StateFlow<Boolean> = _isProxyRunning.asStateFlow()

    private var proxyJob: Job? = null

    // ── Import state ──────────────────────────────────────────────────────────

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    // ── Server context ────────────────────────────────────────────────────────
    private var targetServerIp: String = ""
    private var targetServerPort: Int  = 19132

    /**
     * Load the server's IP and port from the database by [serverId].
     * Must be called before [startProxy] so the proxy knows where to connect.
     * Called automatically via LaunchedEffect in ResourcePackScreen.
     */
    fun loadServerTarget(serverId: Long) {
        viewModelScope.launch {
            serverRepository.getServerById(serverId).first()?.let { server ->
                targetServerIp   = server.ipAddress
                targetServerPort = server.port
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Import
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Import a .mcpack file from the given content URI.
     * Parses manifest.json automatically and stores the pack.
     */
    fun importPack(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importStatus.value = "Importing…"
            val result = repository.importPack(uri)
            _importStatus.value = result.fold(
                onSuccess = { "Imported '${it.name}' successfully" },
                onFailure = { "Import failed: ${it.message}" }
            )
            _isImporting.value = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Enable / delete packs
    // ─────────────────────────────────────────────────────────────────────────

    fun togglePackEnabled(pack: LocalResourcePack) {
        viewModelScope.launch { repository.toggleEnabled(pack) }
    }

    fun deletePack(pack: LocalResourcePack) {
        viewModelScope.launch {
            if (_isProxyRunning.value) stopProxy()
            repository.deletePack(pack)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Proxy control
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start the resource-pack proxy for the server set via [loadServerTarget].
     * The proxy intercepts the server's pack list and prepends all enabled
     * local packs so the Minecraft client downloads them automatically.
     *
     * Connect Minecraft to 127.0.0.1:19135 instead of the real server address.
     */
    fun startProxy() {
        if (_isProxyRunning.value) return
        if (targetServerIp.isBlank()) {
            _proxyStatus.value = "Error: server address not set"
            return
        }
        viewModelScope.launch {
            val enabledPacks = repository.enabledPacks.first()
            proxy.configure(packs = enabledPacks, enabled = true)

            _isProxyRunning.value = true
            _proxyStatus.value = "Starting proxy…"

            proxyJob = launch {
                proxy.runProxy(
                    remoteHost = targetServerIp,
                    remotePort = targetServerPort,
                    onStatus   = { status -> _proxyStatus.value = status }
                )
            }
        }
    }

    /** Stop the proxy and clean up. */
    fun stopProxy() {
        proxyJob?.cancel()
        proxyJob = null
        _isProxyRunning.value  = false
        _proxyStatus.value     = "Proxy stopped"
        proxy.configure(emptyList(), false)
    }

    fun clearImportStatus() { _importStatus.value = null }

    override fun onCleared() {
        super.onCleared()
        stopProxy()
    }
}