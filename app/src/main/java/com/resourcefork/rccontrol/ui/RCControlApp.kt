package com.resourcefork.rccontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.resourcefork.rccontrol.CameraFrameProvider
import com.resourcefork.rccontrol.ModelCatalog
import com.resourcefork.rccontrol.R
import com.resourcefork.rccontrol.RCViewModel

/**
 * Root Compose UI for the Strix RC controller app.
 *
 * Two screens, switched by simple state:
 * - Main: connection controls, joystick, camera/VLM section. The top bar's menu (top right) holds
 *   the on-device/remote checkbox and the Settings entry.
 * - Settings ([SettingsScreen]): remote server configuration and on-device model management.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RCControlApp(
    viewModel: RCViewModel = viewModel(),
    cameraFrameProvider: CameraFrameProvider? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Show errors as snackbar messages (the host is present on both screens).
    LaunchedEffect(uiState.errorMessage) {
        val msg = uiState.errorMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.clearError()
    }

    if (showSettings) {
        SettingsScreen(
            onDeviceModelAvailable = uiState.onDeviceModelAvailable,
            onDeviceModelName = uiState.onDeviceModelName,
            onDeviceWarming = uiState.onDeviceWarming,
            onDeviceReady = uiState.onDeviceReady,
            depthModelName = uiState.depthModelName,
            downloadInProgress = uiState.downloadInProgress,
            downloadProgress = uiState.downloadProgress,
            downloadingModelName = uiState.downloadingModelName,
            hfToken = uiState.hfToken,
            cloudBaseUrl = uiState.cloudBaseUrl,
            cloudModel = uiState.cloudModel,
            apiKey = uiState.cloudApiKey,
            snackbarHostState = snackbar,
            onBack = { showSettings = false },
            onRescanModel = { viewModel.rescanForModel() },
            onHfTokenChange = { token -> viewModel.setHfToken(token) },
            onCloudBaseUrlChange = { url -> viewModel.setCloudBaseUrl(url) },
            onCloudModelChange = { m -> viewModel.setCloudModel(m) },
            onApiKeyChange = { key -> viewModel.setCloudApiKey(key) },
            onDownloadModel = { model, token -> viewModel.downloadModel(model, token) },
            onDownloadDepthModel = { viewModel.downloadModel(ModelCatalog.depthModel, "") },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Strix RC") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                actions = {
                    ConnectionStatusChip(
                        connected = uiState.isConnected,
                        armed = uiState.isArmed,
                        isMockMode = uiState.isMockMode,
                    )
                    // ── Menu (top right): quick toggles + Settings ────────
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Run on-device (offline)") },
                                trailingIcon = {
                                    Checkbox(
                                        checked = uiState.useOnDeviceVlm,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = { viewModel.setUseOnDeviceVlm(!uiState.useOnDeviceVlm) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    showSettings = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Fixed banner: keeps on-device model loading visible regardless of scroll position.
            if (uiState.onDeviceWarming) {
                ModelLoadingBanner()
            }
            Column(
                modifier =
                    Modifier.weight(1f)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                // ── Connection + arm controls ─────────────────────────────────
                ConnectionCard(
                    isConnected = uiState.isConnected,
                    isArmed = uiState.isArmed,
                    isMockMode = uiState.isMockMode,
                    onConnect = { viewModel.connect() },
                    onDisconnect = { viewModel.disconnect() },
                    onArm = { viewModel.arm() },
                    onDisarm = { viewModel.disarm() },
                    onToggleMockMode = { viewModel.toggleMockMode() },
                )

                // ── Mock receiver panel ───────────────────────────────────────
                if (uiState.isMockMode) {
                    Card(colors = cardColors()) {
                        MockReceiverPanel(
                            mockController = viewModel.mockController,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // ── Virtual joystick ─────────────────────────────────────────
                Card(colors = cardColors()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Drive", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Thrust: ${uiState.throttle[0]}%  Steer: ${uiState.throttle[1]}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        VirtualJoystick(onInput = { x, y -> viewModel.onJoystickInput(x, y) })
                    }
                }

                // ── Drive pad (declarative DriveAction steps) ───────────────
                Card(colors = cardColors()) {
                    DrivePad(
                        enabled = uiState.isConnected && uiState.isArmed,
                        lastCommand = uiState.lastDriveCommand,
                        reflexDriveEnabled = uiState.reflexDriveEnabled,
                        reflexDriveAvailable = uiState.depthModelName != null,
                        onReflexDriveChange = { viewModel.setReflexDrive(it) },
                        onAction = { action, speed -> viewModel.driveAction(action, speed) },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }

                // ── Camera / VLM ─────────────────────────────────────────────
                val backendReady =
                    if (uiState.useOnDeviceVlm) uiState.onDeviceModelAvailable
                    else uiState.cloudBaseUrl.isNotBlank()
                val backendStatus =
                    when {
                        uiState.useOnDeviceVlm && uiState.onDeviceWarming ->
                            "On-device · loading model…"
                        uiState.useOnDeviceVlm && uiState.onDeviceReady ->
                            "On-device · ready · ${uiState.onDeviceModelName}"
                        uiState.useOnDeviceVlm && uiState.onDeviceModelAvailable ->
                            "On-device · ${uiState.onDeviceModelName}"
                        uiState.useOnDeviceVlm -> "On-device · no model — download one in Settings"
                        uiState.cloudBaseUrl.isBlank() ->
                            "Remote server · not configured — see Settings"
                        else ->
                            "Remote · ${uiState.cloudModel} @ ${
                                uiState.cloudBaseUrl
                                    .removePrefix("https://")
                                    .removePrefix("http://")
                            }"
                    }
                // Depth reflex-layer status: corridor nearness when reporting, hint when a
                // model is present but silent, and a how-to-enable pointer when absent.
                val geometryStatus =
                    uiState.corridors?.let { c ->
                        "Depth · L ${(c.left * 100).toInt()}%  C ${(c.center * 100).toInt()}%  " +
                            "R ${(c.right * 100).toInt()}%  (nearness – higher = closer)"
                    }
                        ?: uiState.depthModelName?.let { "Depth · ${it} · waiting for frames…" }
                        ?: ("Depth · no model – reflex layer off. " +
                            "Run scripts/download-depth-model.sh, then Re-scan in Settings.")
                // Routes a VLM op to single-shot or continuous execution based on the toggle.
                // Continuous mode gets a frame *provider* so every iteration uses the freshest
                // camera frame rather than the one captured at press time.
                val runVlmOp: (RCViewModel.VlmOp, String) -> Unit = { op, prompt ->
                    if (uiState.continuousMode) {
                        viewModel.startContinuous(
                            op = op,
                            useOnDevice = uiState.useOnDeviceVlm,
                            apiKey = uiState.cloudApiKey,
                            frameProvider = { cameraFrameProvider?.lastFrame },
                            prompt = prompt,
                        )
                    } else {
                        cameraFrameProvider?.lastFrame?.let { frame ->
                            viewModel.runVlm(
                                op = op,
                                useOnDevice = uiState.useOnDeviceVlm,
                                apiKey = uiState.cloudApiKey,
                                frameJpeg = frame,
                                prompt = prompt,
                            )
                        }
                    }
                }
                Card(colors = cardColors()) {
                    CameraSection(
                        previewView = cameraFrameProvider?.previewView,
                        vlmOutput = uiState.vlmOutput,
                        vlmRunning = uiState.vlmRunning,
                        cameraPermissionGranted = uiState.cameraPermissionGranted,
                        cameras = cameraFrameProvider?.availableCameras ?: emptyList(),
                        selectedCameraId = cameraFrameProvider?.selectedCameraId,
                        detections = uiState.detections,
                        corridors = uiState.corridors,
                        backendStatus = backendStatus,
                        backendReady = backendReady,
                        geometryStatus = geometryStatus,
                        continuousMode = uiState.continuousMode,
                        continuousActive = uiState.continuousActive,
                        onContinuousModeChange = { viewModel.setContinuousMode(it) },
                        onCameraPermissionResult = { viewModel.onCameraPermissionResult(it) },
                        onSelectCamera = { id -> cameraFrameProvider?.selectCamera(id) },
                        onAnalyze = { prompt -> runVlmOp(RCViewModel.VlmOp.ANALYZE, prompt) },
                        onDetect = { prompt -> runVlmOp(RCViewModel.VlmOp.DETECT, prompt) },
                        onPilot = { prompt -> runVlmOp(RCViewModel.VlmOp.PILOT, prompt) },
                        modifier = Modifier.padding(16.dp),
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

/** Slim, always-visible banner shown under the app bar while the on-device model loads. */
@Composable
private fun ModelLoadingBanner() {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            "Loading on-device model… first run can take a moment",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ConnectionCard(
    isConnected: Boolean,
    isArmed: Boolean,
    isMockMode: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onToggleMockMode: () -> Unit,
) {
    Card(colors = cardColors()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Connect / Arm buttons ─────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isConnected) {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Disconnect")
                    }
                    if (isArmed) {
                        Button(
                            onClick = onDisarm,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Disarm")
                        }
                    } else {
                        FilledTonalButton(onClick = onArm, modifier = Modifier.weight(1f)) {
                            Text("Arm")
                        }
                    }
                } else {
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Link, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            if (isMockMode) stringResource(R.string.connect_mock)
                            else "Connect to Arduino"
                        )
                    }
                }
            }

            // ── Mock mode toggle ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        stringResource(R.string.mock_mode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.mock_mode_description),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = isMockMode, onCheckedChange = { onToggleMockMode() })
            }
        }
    }
}

@Composable
private fun ConnectionStatusChip(connected: Boolean, armed: Boolean, isMockMode: Boolean) {
    val color =
        when {
            armed -> Color(0xFFFF6F00) // amber
            connected -> Color(0xFF4CAF50) // green
            else -> Color(0xFF9E9E9E) // grey
        }
    val label =
        when {
            armed -> stringResource(R.string.status_armed)
            connected ->
                if (isMockMode) stringResource(R.string.status_mock)
                else stringResource(R.string.status_connected)
            else -> stringResource(R.string.status_disconnected)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(end = 12.dp),
    ) {
        Icon(
            Icons.Default.Circle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.padding(4.dp),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun cardColors() =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
