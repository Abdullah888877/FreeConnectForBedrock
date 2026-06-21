package com.freeconnect.bedrock.ui.addserver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Add / Edit Server screen.
 *
 * When [serverId] is null the screen operates in Add mode.
 * When [serverId] is non-null the ViewModel loads the existing server
 * and the screen operates in Edit mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    serverId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel()
) {
    val isEditMode = serverId != null

    // Load existing server data once when in edit mode
    LaunchedEffect(serverId) {
        serverId?.let { viewModel.loadServer(it) }
    }

    // Navigate back automatically after a successful save
    val isSaved by viewModel.isSaved.collectAsState()
    LaunchedEffect(isSaved) {
        if (isSaved) onNavigateBack()
    }

    val serverName by viewModel.serverName.collectAsState()
    val ipAddress  by viewModel.ipAddress.collectAsState()
    val port       by viewModel.port.collectAsState()

    val nameError by viewModel.nameError.collectAsState()
    val ipError   by viewModel.ipError.collectAsState()
    val portError by viewModel.portError.collectAsState()

    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Server" else "Add Server") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Save action in the toolbar
                    IconButton(onClick = { viewModel.saveServer() }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(modifier = Modifier.padding(32.dp))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Server Name ──────────────────────────────────────────────────
            OutlinedTextField(
                value         = serverName,
                onValueChange = viewModel::onNameChange,
                label         = { Text("Server Name") },
                placeholder   = { Text("My Bedrock Server") },
                leadingIcon   = { Icon(Icons.Default.Label, contentDescription = null) },
                isError       = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // ── IP Address ───────────────────────────────────────────────────
            OutlinedTextField(
                value         = ipAddress,
                onValueChange = viewModel::onIpChange,
                label         = { Text("IP Address or Hostname") },
                placeholder   = { Text("192.168.1.100") },
                leadingIcon   = { Icon(Icons.Default.Dns, contentDescription = null) },
                isError       = ipError != null,
                supportingText = ipError?.let { { Text(it) } },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction    = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // ── Port ─────────────────────────────────────────────────────────
            OutlinedTextField(
                value         = port,
                onValueChange = viewModel::onPortChange,
                label         = { Text("Port") },
                placeholder   = { Text("19132") },
                leadingIcon   = { Icon(Icons.Default.Numbers, contentDescription = null) },
                isError       = portError != null,
                supportingText = portError?.let { { Text(it) } }
                    ?: { Text("Default Bedrock port is 19132") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction    = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Save button ──────────────────────────────────────────────────
            Button(
                onClick  = { viewModel.saveServer() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isEditMode) "Save Changes" else "Save Server")
            }
        }
    }
}
