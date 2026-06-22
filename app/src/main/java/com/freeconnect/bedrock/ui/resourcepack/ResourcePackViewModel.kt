package com.freeconnect.bedrock.ui.resourcepack

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeconnect.bedrock.data.resourcepack.LocalResourcePack
import com.freeconnect.bedrock.data.resourcepack.ResourcePackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Resource Pack screen.
 *
 * Manages importing, enabling/disabling, and deleting locally stored packs.
 * Enabled packs are automatically served to consoles when LAN Broadcast is
 * active — no proxy or manual step needed.
 */
@HiltViewModel
class ResourcePackViewModel @Inject constructor(
    private val repository: ResourcePackRepository
) : ViewModel() {

    /** All locally stored packs. */
    val packs: StateFlow<List<LocalResourcePack>> = repository.allPacks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Import state ──────────────────────────────────────────────────────────

    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

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
        viewModelScope.launch { repository.deletePack(pack) }
    }

    fun clearImportStatus() { _importStatus.value = null }
}
