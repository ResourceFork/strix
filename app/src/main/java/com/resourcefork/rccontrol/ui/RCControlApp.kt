package com.resourcefork.rccontrol.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.resourcefork.rccontrol.CameraSources
import com.resourcefork.rccontrol.ModelCatalog
import com.resourcefork.rccontrol.RCViewModel

/**
 * Root Compose UI for the Strix RC controller app – the *stateful* shell.
 *
 * All rendering lives in the stateless [MainScreen] (preview-friendly); this wrapper owns the
 * ViewModel, collects state, routes actions, shows errors as snackbars, and switches to
 * [SettingsScreen].
 */
@Composable
fun RCControlApp(viewModel: RCViewModel = viewModel(), cameraSources: CameraSources? = null) {
    val uiState by viewModel.uiState.collectAsState()
    val mockState by viewModel.mockController.mockState.collectAsState()
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

    // Routes a VLM op to single-shot or continuous execution based on the toggle. Continuous
    // mode gets a frame *provider* so every iteration uses the freshest camera frame rather
    // than the one captured at press time.
    val runVlm: (RCViewModel.VlmOp, String) -> Unit = { op, prompt ->
        if (uiState.continuousMode) {
            viewModel.startContinuous(
                op = op,
                useOnDevice = uiState.useOnDeviceVlm,
                apiKey = uiState.cloudApiKey,
                frameProvider = { cameraSources?.lastFrame },
                prompt = prompt,
            )
        } else {
            cameraSources?.lastFrame?.let { frame ->
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

    MainScreen(
        uiState = uiState,
        mockState = mockState,
        previewView = cameraSources?.previewView,
        cameras = cameraSources?.availableCameras ?: emptyList(),
        selectedCameraId = cameraSources?.selectedCameraId,
        snackbarHostState = snackbar,
        actions =
            MainScreenActions(
                onConnect = { viewModel.connect() },
                onDisconnect = { viewModel.disconnect() },
                onArm = { viewModel.arm() },
                onDisarm = { viewModel.disarm() },
                onToggleMockMode = { viewModel.toggleMockMode() },
                onJoystickInput = { x, y -> viewModel.onJoystickInput(x, y) },
                onDriveAction = { action, speed -> viewModel.driveAction(action, speed) },
                onReflexDriveChange = { viewModel.setReflexDrive(it) },
                onSelectCamera = { id -> cameraSources?.select(id) },
                onCameraPermissionResult = { viewModel.onCameraPermissionResult(it) },
                onContinuousModeChange = { viewModel.setContinuousMode(it) },
                onRunVlm = runVlm,
                onUseOnDeviceVlmChange = { viewModel.setUseOnDeviceVlm(it) },
                onOpenSettings = { showSettings = true },
            ),
    )
}
