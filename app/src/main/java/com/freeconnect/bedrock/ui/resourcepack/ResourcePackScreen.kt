package com.freeconnect.bedrock.ui.resourcepack

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freeconnect.bedrock.data.db.ServerEntity
import com.freeconnect.bedrock.data.resourcepack.LocalResourcePack
import com.freeconnect.bedrock.network.ResourcePackProxy

/**
 * Resource Pack management screen.
 *
 * Features:
 *  - Import .mcpack files from device storage
 *  - Enable/disable individual packs for injection
 *  - Start/stop the local UDP proxy (connect Minecraft to 127.0.0.1:19135)
 *  - Delete packs from local storage
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcePackScreen(
    serverId: Long,
    onNavigateBack: () -> Unit,
    viewModel: ResourcePackViewModel = hiltViewModel()
) {
    val packs         by viewModel.packs.collectAsState()
    val proxyStatus   by viewModel.proxyStatus.collectAsState()
    val isProxyRunning by viewModel.isProxyRunning.collectAsState()
    val importStatus  by viewModel.importStatus.collectAsState()
    val isImporting   by viewModel.isImporting.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Load the server's IP:port so the proxy knows where to connect
    LaunchedEffect(serverId) {
        viewModel.loadServerTarget(serverId)
    }

    LaunchedEffect(importStatus) {
        importStatus?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportStatus()
        }
    }

    // File picker for .mcpack import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> viewModel.importPack(uri) }
        }
    }

    var packToDelete by remember { mutableStateOf<LocalResourcePack?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Resource Packs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Import button
                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                putExtra(
                                    Intent.EXTRA_MIME_TYPES,
                                    arrayOf("application/zip", "application/octet-stream", "*/*")
                                )
                            }
                            importLauncher.launch(intent)
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Import Pack")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── Proxy control card ────────────────────────────────────────────
            item {
                ProxyControlCard(
                    status         = proxyStatus,
                    isRunning      = isProxyRunning,
                    proxyPort      = ResourcePackProxy.LOCAL_PROXY_PORT,
                    onStart        = { viewModel.startProxy() },
                    onStop         = { viewModel.stopProxy() }
                )
            }

            // ── How-to card ───────────────────────────────────────────────────
            item {
                HowToCard()
            }

            // ── Import loading indicator ──────────────────────────────────────
            if (isImporting) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Importing pack…")
                    }
                }
            }

            // ── Pack list header ──────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Installed Packs (${packs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    val enabledCount = packs.count { it.isEnabled }
                    if (enabledCount > 0) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "$enabledCount active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (packs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No packs installed",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Tap + to import a .mcpack file",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Pack cards ────────────────────────────────────────────────────
            items(packs, key = { it.id }) { pack ->
                ResourcePackCard(
                    pack      = pack,
                    onToggle  = { viewModel.togglePackEnabled(pack) },
                    onDelete  = { packToDelete = pack }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // Delete confirmation
    packToDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { packToDelete = null },
            title = { Text("Delete pack?") },
            text  = { Text("'${pack.name}' will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePack(pack)
                    packToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { packToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProxyControlCard(
    status: String,
    isRunning: Boolean,
    proxyPort: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Router else Icons.Outlined.Router,
                    contentDescription = null,
                    tint = if (isRunning) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Pack Injection Proxy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = isRunning) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connect Minecraft to:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "127.0.0.1 : $proxyPort",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "instead of the real server address",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = if (isRunning) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isRunning)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isRunning) "Stop Proxy" else "Start Proxy")
            }
        }
    }
}

@Composable
private fun HowToCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "How to use custom packs",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            listOf(
                "1. Import a .mcpack file using the + button above.",
                "2. Enable the packs you want to inject.",
                "3. Tap Start Proxy — the proxy runs locally on this device.",
                "4. In Minecraft, add a new server using 127.0.0.1 and port 19135.",
                "5. Connect — your packs will be automatically injected.",
                "Note: Both this device and the target server must be on the same Wi-Fi."
            ).forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ResourcePackCard(
    pack: LocalResourcePack,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (pack.isEnabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pack icon
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (pack.isEnabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = if (pack.isEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = pack.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pack.description.isNotBlank()) {
                    Text(
                        text  = pack.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text  = "v${pack.version} · ${formatBytes(pack.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Enable toggle
            Switch(
                checked           = pack.isEnabled,
                onCheckedChange   = { onToggle() }
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Delete
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Format bytes to a human-readable string (KB / MB). */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024     -> "${bytes / 1_024} KB"
    else               -> "$bytes B"
}
