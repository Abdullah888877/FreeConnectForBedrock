package com.freeconnect.bedrock.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freeconnect.bedrock.data.db.ServerEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * Home screen — lists all saved Bedrock servers.
 *
 * Each server card exposes: Connect, Edit, Delete, Favorite, and LAN Broadcast.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddServer: () -> Unit,
    onEditServer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onResourcePack: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsState()
    val broadcastingId by viewModel.broadcastingServer.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Show snackbar when a message is posted
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    // Delete confirmation dialog state
    var serverToDelete by remember { mutableStateOf<ServerEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "FreeConnect",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Bedrock Edition",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddServer,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Server") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->

        if (servers.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No servers yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Tap + to add your first Bedrock server",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server        = server,
                        isBroadcasting = broadcastingId == server.id,
                        onConnect     = {
                            viewModel.markConnected(server)
                            // Deep-link intent — opens Minecraft if installed
                            val uri = Uri.parse("minecraft://?addExternalServer=${server.name}|${server.ipAddress}:${server.port}")
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Minecraft not installed — user will see snackbar via ViewModel
                            }
                        },
                        onEdit           = { onEditServer(server.id) },
                        onDelete         = { serverToDelete = server },
                        onToggleFavorite = { viewModel.toggleFavorite(server) },
                        onToggleBroadcast = {
                            if (broadcastingId == server.id) viewModel.stopBroadcast()
                            else viewModel.startBroadcast(server)
                        },
                        onResourcePack = { onResourcePack(server.id) }
                    )
                }
                // Bottom padding so the FAB doesn't overlap the last card
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // Delete confirmation dialog
    serverToDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text("Delete server?") },
            text  = { Text("'${server.name}' will be permanently removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteServer(server)
                        serverToDelete = null
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server card composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServerCard(
    server: ServerEntity,
    isBroadcasting: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleBroadcast: () -> Unit,
    onResourcePack: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isBroadcasting)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header row ───────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Favorite star
                IconButton(
                    onClick  = onToggleFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (server.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (server.isFavorite) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text  = "${server.ipAddress}:${server.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    server.lastConnected?.let { ts ->
                        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                        Text(
                            text  = "Last: ${fmt.format(Date(ts))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Broadcast indicator badge
                AnimatedVisibility(visible = isBroadcasting, enter = fadeIn(), exit = fadeOut()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text  = "LIVE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Primary action row ───────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Connect
                Button(
                    onClick  = onConnect,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Connect")
                }

                // LAN Broadcast toggle
                OutlinedButton(
                    onClick  = onToggleBroadcast,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isBroadcasting) Icons.Default.WifiOff else Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isBroadcasting) "Stop" else "Broadcast")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Secondary action row ─────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onResourcePack) {
                    Icon(Icons.Default.Folder, contentDescription = "Resource Packs")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
