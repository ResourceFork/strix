package com.resourcefork.rccontrol.ui

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.resourcefork.rccontrol.CameraOption
import com.resourcefork.rccontrol.CorridorReport
import com.resourcefork.rccontrol.DistanceReport
import com.resourcefork.rccontrol.DriveAction
import com.resourcefork.rccontrol.DriveCommand
import com.resourcefork.rccontrol.DriveSpeed
import com.resourcefork.rccontrol.MockMotorController
import com.resourcefork.rccontrol.R
import com.resourcefork.rccontrol.RCViewModel
import com.resourcefork.rccontrol.ui.theme.StrixTheme

/**
 * Every callback the main screen can emit, bundled so [MainScreen] stays legible and previews can
 * default the lot to no-ops. The stateful wrapper ([RCControlApp]) wires these to the ViewModel and
 * camera sources.
 */
data class MainScreenActions(
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onArm: () -> Unit = {},
    val onDisarm: () -> Unit = {},
    val onToggleMockMode: () -> Unit = {},
    val onJoystickInput: (Float, Float) -> Unit = { _, _ -> },
    val onDriveAction: (DriveAction, DriveSpeed) -> Unit = { _, _ -> },
    val onReflexDriveChange: (Boolean) -> Unit = {},
    val onSelectCamera: (String) -> Unit = {},
    val onCameraPermissionResult: (Boolean) -> Unit = {},
    val onContinuousModeChange: (Boolean) -> Unit = {},
    val onRunVlm: (RCViewModel.VlmOp, String) -> Unit = { _, _ -> },
    val onUseOnDeviceVlmChange: (Boolean) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/**
 * The main control screen, stateless and preview-friendly: everything it shows comes in through
 * parameters, everything it does goes out through [MainScreenActions].
 *
 * Layout, hero-first:
 * - The camera hero (video + all perception overlays) is pinned under the app bar – it never
 *   scrolls away, because it is the screen's reason to exist.
 * - A quiet status strip sits directly below the hero.
 * - The scrollable region groups the rest by purpose: Vision (VLM ops + output), Drive (joystick +
 *   action pad), and the mock receiver when mock mode is on.
 * - Connection is a stateful extended FAB (Connect → Arm → Disarm) so the safety-critical action is
 *   always one thumb-reach away; mode toggles live in the top-bar menu.
 * - The VLM prompt is edited in a modal bottom sheet instead of occupying the main flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: RCViewModel.UiState,
    mockState: MockMotorController.MockState,
    previewView: View?,
    cameras: List<CameraOption>,
    selectedCameraId: String?,
    snackbarHostState: SnackbarHostState,
    actions: MainScreenActions,
) {
    var prompt by rememberSaveable {
        mutableStateOf(
            "Describe what you see. What obstacles are ahead? Answer in at most 3 short sentences."
        )
    }
    var showPromptSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            MainTopBar(
                connected = uiState.isConnected,
                armed = uiState.isArmed,
                isMockMode = uiState.isMockMode,
                useOnDeviceVlm = uiState.useOnDeviceVlm,
                onToggleMockMode = actions.onToggleMockMode,
                onUseOnDeviceVlmChange = actions.onUseOnDeviceVlmChange,
                onDisconnect = actions.onDisconnect,
                onOpenSettings = actions.onOpenSettings,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ConnectionFab(
                connected = uiState.isConnected,
                armed = uiState.isArmed,
                isMockMode = uiState.isMockMode,
                onConnect = actions.onConnect,
                onArm = actions.onArm,
                onDisarm = actions.onDisarm,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Fixed banner: keeps on-device model loading visible regardless of scroll position.
            AnimatedVisibility(
                visible = uiState.onDeviceWarming,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ModelLoadingBanner()
            }

            // ── Hero: pinned, never scrolls away ─────────────────────────────
            CameraHero(
                previewView = previewView,
                cameraPermissionGranted = uiState.cameraPermissionGranted,
                cameras = cameras,
                selectedCameraId = selectedCameraId,
                detections = uiState.detections,
                corridors = uiState.corridors,
                distances = uiState.distances,
                driveCommand = uiState.lastDriveCommand,
                onCameraPermissionResult = actions.onCameraPermissionResult,
                onSelectCamera = actions.onSelectCamera,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            CameraStatusStrip(
                backendStatus = backendStatusText(uiState),
                geometryStatus = geometryStatusText(uiState),
                rangeStatus = rangeStatusText(uiState),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            // ── Scrollable supporting controls ───────────────────────────────
            Column(
                modifier =
                    Modifier.weight(1f)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(0.dp))

                // Mock receiver: only meaningful in mock mode; animates with the mode switch.
                // First in the list so the virtual device is visible next to the hero while
                // bench-testing commands.
                AnimatedVisibility(
                    visible = uiState.isMockMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Card(colors = sectionCardColors()) {
                        MockReceiverPanel(state = mockState, modifier = Modifier.padding(16.dp))
                    }
                }

                // Drive: manual joystick and the declarative action pad, grouped as one concern.
                Card(colors = sectionCardColors()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Drive", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Thrust: ${uiState.throttle[0]}%  Steer: ${uiState.throttle[1]}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        VirtualJoystick(onInput = actions.onJoystickInput)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DrivePad(
                            enabled = uiState.isConnected && uiState.isArmed,
                            lastCommand = uiState.lastDriveCommand,
                            reflexDriveEnabled = uiState.reflexDriveEnabled,
                            reflexDriveAvailable = uiState.depthModelName != null,
                            onReflexDriveChange = actions.onReflexDriveChange,
                            onAction = actions.onDriveAction,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Card(colors = sectionCardColors()) {
                    VlmControlsCard(
                        prompt = prompt,
                        vlmOutput = uiState.vlmOutput,
                        vlmRunning = uiState.vlmRunning,
                        backendReady = backendReady(uiState),
                        continuousMode = uiState.continuousMode,
                        continuousActive = uiState.continuousActive,
                        onEditPrompt = { showPromptSheet = true },
                        onContinuousModeChange = actions.onContinuousModeChange,
                        onAnalyze = { actions.onRunVlm(RCViewModel.VlmOp.ANALYZE, prompt) },
                        onDetect = { actions.onRunVlm(RCViewModel.VlmOp.DETECT, prompt) },
                        onPilot = { actions.onRunVlm(RCViewModel.VlmOp.PILOT, prompt) },
                        modifier = Modifier.padding(16.dp),
                    )
                }

                // Clearance so the FAB never covers the last card's controls.
                Spacer(Modifier.height(72.dp))
            }
        }
    }

    if (showPromptSheet) {
        PromptSheet(
            prompt = prompt,
            onPromptChange = { prompt = it },
            onDismiss = { showPromptSheet = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen chrome
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    connected: Boolean,
    armed: Boolean,
    isMockMode: Boolean,
    useOnDeviceVlm: Boolean,
    onToggleMockMode: () -> Unit,
    onUseOnDeviceVlmChange: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = { Text("Strix RC") },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        actions = {
            ConnectionStatusChip(connected = connected, armed = armed, isMockMode = isMockMode)
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Run on-device (offline)") },
                        trailingIcon = {
                            Checkbox(checked = useOnDeviceVlm, onCheckedChange = null)
                        },
                        onClick = { onUseOnDeviceVlmChange(!useOnDeviceVlm) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mock_mode)) },
                        trailingIcon = { Checkbox(checked = isMockMode, onCheckedChange = null) },
                        onClick = onToggleMockMode,
                    )
                    if (connected) {
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            leadingIcon = {
                                Icon(Icons.Default.LinkOff, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onDisconnect()
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                    )
                }
            }
        },
    )
}

/**
 * The one always-reachable action, staged by connection state: Connect (disconnected) → Arm
 * (connected) → Disarm (armed, error-colored). Disconnect stays in reach as the FAB's quiet sibling
 * while connected.
 */
@Composable
private fun ConnectionFab(
    connected: Boolean,
    armed: Boolean,
    isMockMode: Boolean,
    onConnect: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
) {
    when {
        !connected ->
            ExtendedFloatingActionButton(
                onClick = onConnect,
                icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                text = {
                    Text(if (isMockMode) stringResource(R.string.connect_mock) else "Connect")
                },
            )
        !armed ->
            ExtendedFloatingActionButton(
                onClick = onArm,
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                text = { Text("Arm") },
            )
        else ->
            ExtendedFloatingActionButton(
                onClick = onDisarm,
                icon = { Icon(Icons.Default.Stop, contentDescription = null) },
                text = { Text("Disarm") },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
    }
}

/** Modal bottom sheet for editing the VLM prompt without it occupying the main flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptSheet(prompt: String, onPromptChange: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("VLM prompt", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                label = { Text("Prompt") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

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
private fun sectionCardColors() =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)

// ─────────────────────────────────────────────────────────────────────────────
// Derived status text (pure functions of UiState so previews render them too)
// ─────────────────────────────────────────────────────────────────────────────

private fun backendReady(s: RCViewModel.UiState): Boolean =
    if (s.useOnDeviceVlm) s.onDeviceModelAvailable else s.cloudBaseUrl.isNotBlank()

private fun backendStatusText(s: RCViewModel.UiState): String =
    when {
        s.useOnDeviceVlm && s.onDeviceWarming -> "On-device · loading model…"
        s.useOnDeviceVlm && s.onDeviceReady -> "On-device · ready · ${s.onDeviceModelName}"
        s.useOnDeviceVlm && s.onDeviceModelAvailable -> "On-device · ${s.onDeviceModelName}"
        s.useOnDeviceVlm -> "On-device · no model — download one in Settings"
        s.cloudBaseUrl.isBlank() -> "Remote server · not configured — see Settings"
        else ->
            "Remote · ${s.cloudModel} @ ${
                s.cloudBaseUrl.removePrefix("https://").removePrefix("http://")
            }"
    }

private fun geometryStatusText(s: RCViewModel.UiState): String =
    s.corridors?.let { c ->
        c.bands.joinToString(separator = "  ", prefix = "Depth · ") { "${(it * 100).toInt()}" } +
            "\n(L→R % blocked · 0 = open · 100 = at bumper)"
    }
        ?: s.depthModelName?.let { "Depth · ${it} · waiting for frames…" }
        ?: ("Depth · no model – reflex layer off. " +
            "Run scripts/download-depth-model.sh, then Re-scan in Settings.")

private fun rangeStatusText(s: RCViewModel.UiState): String? =
    s.distances?.let { d ->
        fun fmt(v: Int?) = v?.let { "${it}mm" } ?: "–"
        "Range · L ${fmt(d.frontLeftMm)} · C ${fmt(d.centerMm)} · R ${fmt(d.frontRightMm)}"
    }

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Disconnected", showBackground = true)
@Composable
private fun MainScreenPreviewDisconnected() {
    StrixTheme {
        MainScreen(
            uiState = RCViewModel.UiState(cameraPermissionGranted = true),
            mockState = MockMotorController.MockState(),
            previewView = null,
            cameras = emptyList(),
            selectedCameraId = null,
            snackbarHostState = SnackbarHostState(),
            actions = MainScreenActions(),
        )
    }
}

@Preview(name = "Armed with sensor data", showBackground = true)
@Composable
private fun MainScreenPreviewArmed() {
    StrixTheme {
        MainScreen(
            uiState =
                RCViewModel.UiState(
                    isConnected = true,
                    isArmed = true,
                    cameraPermissionGranted = true,
                    throttle = intArrayOf(30, 0),
                    lastDriveCommand = DriveCommand(DriveAction.FORWARD),
                    depthModelName = "midas-v2_1-small.tflite",
                    corridors = CorridorReport(bands = listOf(0.15f, 0.3f, 0.7f, 0.4f, 0.2f)),
                    distances =
                        DistanceReport(centerMm = 431, frontLeftMm = 822, frontRightMm = 760),
                    onDeviceModelAvailable = true,
                    onDeviceReady = true,
                    onDeviceModelName = "gemma-3n-E2B-it-int4.task",
                    vlmOutput = "Pilot: forward (slow)\nReason: lane clear",
                ),
            mockState = MockMotorController.MockState(),
            previewView = null,
            cameras =
                listOf(
                    CameraOption("0", "Back Camera"),
                    CameraOption("usb:/dev/bus/usb/001/002", "USB: Arducam-1080P-HDR"),
                ),
            selectedCameraId = "0",
            snackbarHostState = SnackbarHostState(),
            actions = MainScreenActions(),
        )
    }
}

@Preview(name = "Mock mode", showBackground = true)
@Composable
private fun MainScreenPreviewMock() {
    StrixTheme {
        MainScreen(
            uiState =
                RCViewModel.UiState(
                    isConnected = true,
                    isMockMode = true,
                    cameraPermissionGranted = true,
                ),
            mockState =
                MockMotorController.MockState(
                    connected = true,
                    armed = true,
                    throttle = intArrayOf(40, -20),
                    lastCommand = "T1:40 T2:-20",
                ),
            previewView = null,
            cameras = emptyList(),
            selectedCameraId = null,
            snackbarHostState = SnackbarHostState(),
            actions = MainScreenActions(),
        )
    }
}
