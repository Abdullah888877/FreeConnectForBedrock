package com.freeconnect.bedrock.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Settings screen.
 *
 * Features:
 *  - Dark mode toggle
 *  - Auto-start LAN broadcast toggle
 *  - Backup server list to JSON
 *  - Restore server list from JSON
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isDarkMode         by viewModel.isDarkMode.collectAsState()
    val autoStartBroadcast by viewModel.autoStartBroadcast.collectAsState()
    val message            by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar on any message
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // File picker for export (creates a new JSON file)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri: Uri ->
                viewModel.exportBackup(uri)
            }
        }
    }

    // File picker for import (opens an existing JSON file)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri: Uri ->
                viewModel.importBackup(uri)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── Appearance ───────────────────────────────────────────────────
            SettingsSectionHeader("Appearance")

            SettingsToggleRow(
                title       = "Dark Mode",
                subtitle    = "Use dark theme throughout the app",
                icon        = Icons.Default.DarkMode,
                checked     = isDarkMode,
                onChecked   = viewModel::setDarkMode
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Broadcast ────────────────────────────────────────────────────
            SettingsSectionHeader("LAN Broadcast")

            SettingsToggleRow(
                title       = "Auto-Start Broadcast",
                subtitle    = "Automatically broadcast on app launch",
                icon        = Icons.Default.Wifi,
                checked     = autoStartBroadcast,
                onChecked   = viewModel::setAutoStartBroadcast
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Data ─────────────────────────────────────────────────────────
            SettingsSectionHeader("Server List")

            SettingsClickRow(
                title    = "Export Backup",
                subtitle = "Save server list as a JSON file",
                icon     = Icons.Default.Upload,
                onClick  = {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type     = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "freeconnect_backup.json")
                    }
                    exportLauncher.launch(intent)
                }
            )

            SettingsClickRow(
                title    = "Import Backup",
                subtitle = "Restore server list from a JSON file (replaces current list)",
                icon     = Icons.Default.Download,
                onClick  = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                    }
                    importLauncher.launch(intent)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── About ────────────────────────────────────────────────────────
            SettingsSectionHeader("About")

            SettingsInfoRow(title = "Version", value = "1.5.0")
            SettingsInfoRow(title = "Build", value = "Release")
            SettingsInfoRow(title = "License", value = "Open Source — No Ads, No Subscriptions")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable settings row composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingsClickRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = title,
            style    = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
