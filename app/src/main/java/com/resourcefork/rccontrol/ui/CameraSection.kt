package com.resourcefork.rccontrol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.resourcefork.rccontrol.CameraOption
import com.resourcefork.rccontrol.Detection
import com.resourcefork.rccontrol.DownloadableModel
import com.resourcefork.rccontrol.ModelCatalog
import com.resourcefork.rccontrol.R

/**
 * Camera preview + VLM section.
 *
 * Shows:
 * 1. A camera selector dropdown (front / back / wide / external, etc.).
 * 2. A live [PreviewView] from CameraX with an annotated bounding-box overlay.
 * 3. API-key and prompt fields.
 * 4. "Analyze Frame" (streamed text description) and "Detect Objects" (streamed bounding boxes)
 *    buttons.
 * 5. A scrollable area where the streamed VLM response appears.
 */
@Composable
fun CameraSection(
    previewView: PreviewView?,
    vlmOutput: String,
    vlmRunning: Boolean,
    cameraPermissionGranted: Boolean,
    cameras: List<CameraOption>,
    selectedCameraId: String?,
    detections: List<Detection>,
    useOnDevice: Boolean,
    onDeviceModelAvailable: Boolean,
    onDeviceModelName: String?,
    onDeviceWarming: Boolean,
    onDeviceReady: Boolean,
    downloadInProgress: Boolean,
    downloadProgress: Float?,
    downloadingModelName: String?,
    hfToken: String,
    onCameraPermissionResult: (Boolean) -> Unit,
    onSelectCamera: (String) -> Unit,
    onToggleSource: (Boolean) -> Unit,
    onRescanModel: () -> Unit,
    onHfTokenChange: (String) -> Unit,
    onDownloadModel: (DownloadableModel, String) -> Unit,
    onAnalyze: (apiKey: String, prompt: String) -> Unit,
    onDetect: (apiKey: String, prompt: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onCameraPermissionResult(granted)
        }

    // API keys must not be persisted in saved-instance-state or backups.
    var apiKey by remember { mutableStateOf("") }
    var prompt by rememberSaveable {
        mutableStateOf(
            "Describe what you see. What obstacles are ahead? Answer in at most 3 short sentences."
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.camera_section),
                style = MaterialTheme.typography.titleSmall,
            )
        }

        if (!cameraPermissionGranted) {
            val alreadyGranted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED

            if (alreadyGranted) {
                onCameraPermissionResult(true)
            } else {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Camera Permission")
                }
            }
        } else {
            // Camera selector
            if (cameras.isNotEmpty()) {
                CameraSelectorDropdown(
                    cameras = cameras,
                    selectedCameraId = selectedCameraId,
                    onSelectCamera = onSelectCamera,
                )
            }

            // Live camera preview with annotated bounding-box overlay
            Box(modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp))) {
                if (previewView != null) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                }
                if (detections.isNotEmpty()) {
                    DetectionOverlay(detections = detections, modifier = Modifier.fillMaxSize())
                }
            }

            // Inference source: on-device (offline) vs cloud API
            val modelSuffix = onDeviceModelName?.let { " · $it" } ?: ""
            val statusText =
                when {
                    !useOnDevice -> "Using cloud VLM API (needs key + network)"
                    onDeviceWarming -> "Loading model into memory…"
                    onDeviceReady -> "Model ready$modelSuffix"
                    onDeviceModelAvailable -> "Model found$modelSuffix"
                    else -> "No model on device. Run download-vlm-model.sh, then push it."
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Run on-device (offline)", style = MaterialTheme.typography.labelLarge)
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (useOnDevice && onDeviceWarming) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Switch(checked = useOnDevice, onCheckedChange = onToggleSource)
            }

            if (useOnDevice) {
                // Download a model straight to the device — no adb, no desktop step.
                if (!onDeviceModelAvailable && !onDeviceWarming) {
                    ModelDownloadPanel(
                        downloadInProgress = downloadInProgress,
                        downloadProgress = downloadProgress,
                        downloadingModelName = downloadingModelName,
                        hfToken = hfToken,
                        onHfTokenChange = onHfTokenChange,
                        onDownloadModel = onDownloadModel,
                    )
                }

                // Re-scan (e.g. after adb-pushing a model instead of downloading in-app).
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onRescanModel,
                        enabled = !onDeviceWarming && !downloadInProgress,
                    ) {
                        Text(if (onDeviceModelAvailable) "Re-scan" else "Re-scan for model")
                    }
                }
            }

            // VLM configuration fields – API key only needed for the cloud backend
            if (!useOnDevice) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("VLM API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val canRun =
                    !vlmRunning &&
                        (if (useOnDevice) onDeviceModelAvailable else apiKey.isNotBlank())
                Button(onClick = { onAnalyze(apiKey, prompt) }, enabled = canRun) {
                    Text("Analyze Frame")
                }
                Button(onClick = { onDetect(apiKey, prompt) }, enabled = canRun) {
                    Text("Detect Objects")
                }
                if (vlmRunning) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
                }
            }

            // VLM text stream output
            if (vlmOutput.isNotBlank() || vlmRunning) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 60.dp, max = 200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                ) {
                    Text(
                        text = vlmOutput,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/** Dropdown that lists the device cameras and reports the chosen one via [onSelectCamera]. */
@Composable
private fun CameraSelectorDropdown(
    cameras: List<CameraOption>,
    selectedCameraId: String?,
    onSelectCamera: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel =
        cameras.firstOrNull { it.id == selectedCameraId }?.label ?: cameras.first().label

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Videocam, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(selectedLabel)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            cameras.forEach { cam ->
                DropdownMenuItem(
                    text = { Text(cam.label) },
                    onClick = {
                        onSelectCamera(cam.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Lets the user download a VLM model straight into the app's storage (token-gated for Gemma). */
@Composable
private fun ModelDownloadPanel(
    downloadInProgress: Boolean,
    downloadProgress: Float?,
    downloadingModelName: String?,
    hfToken: String,
    onHfTokenChange: (String) -> Unit,
    onDownloadModel: (DownloadableModel, String) -> Unit,
) {
    var selected by remember { mutableStateOf(ModelCatalog.models.first()) }
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        val uri = Uri.parse(url)
        try {
            // Custom Tab: in-app browser that returns cleanly to Strix when finished.
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } catch (_: Exception) {
            // Fall back to a normal browser if no Custom Tabs provider is available.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                // No browser/handler available on the device.
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Download a model to this device", style = MaterialTheme.typography.labelLarge)

        Box {
            OutlinedButton(onClick = { menuExpanded = true }, enabled = !downloadInProgress) {
                Text(selected.name)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                ModelCatalog.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text("${model.name} · ${model.sizeMb} MB") },
                        onClick = {
                            selected = model
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        Text(
            selected.description + if (selected.gated) " · gated: needs a HF token" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        // Gated Gemma models need a one-time license acceptance + a read token, both on HF.
        // These open the relevant pages in the device browser (like the desktop script's `open`).
        if (selected.gated) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { openUrl("https://huggingface.co/${selected.repo}") },
                    enabled = !downloadInProgress,
                ) {
                    Text("Accept license ↗")
                }
                TextButton(
                    // Preconfigured fine-grained token creation page. IMPORTANT: gated access is
                    // a separate checkbox there — see the hint below the token field.
                    onClick = {
                        openUrl(
                            "https://huggingface.co/settings/tokens/new" +
                                "?tokenType=fineGrained&globalPermissions=repo.content.read"
                        )
                    },
                    enabled = !downloadInProgress,
                ) {
                    Text("Create a token ↗")
                }
            }
        }

        // Token is held in the ViewModel and persisted, so it survives app restarts.
        OutlinedTextField(
            value = hfToken,
            onValueChange = onHfTokenChange,
            label = { Text("Hugging Face token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !downloadInProgress,
            modifier = Modifier.fillMaxWidth(),
        )
        if (selected.gated) {
            Text(
                "Fine-grained tokens must have \u2611 \"Read access to contents of all public " +
                    "gated repos\" checked, or use a classic Read token. Account access alone " +
                    "isn't enough.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        if (downloadInProgress) {
            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "Downloading ${downloadingModelName ?: "model"}…" +
                    (downloadProgress?.let { " ${(it * 100).toInt()}%" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        } else {
            Button(onClick = { onDownloadModel(selected, hfToken) }) {
                Text("Download (${selected.sizeMb} MB)")
            }
        }
    }
}

/**
 * Draws the streamed [detections] as normalized bounding boxes with labels over the camera preview.
 * Coordinates are scaled from [0,1] to the overlay size.
 *
 * Note: boxes are mapped across the full overlay rectangle. If the preview is cropped (the default
 * FILL_CENTER scale type), there can be a small offset versus the exact on-screen pixels; alignment
 * is close enough for guidance.
 */
@Composable
private fun DetectionOverlay(detections: List<Detection>, modifier: Modifier = Modifier) {
    val boxColor = MaterialTheme.colorScheme.primary
    val labelTextColor = MaterialTheme.colorScheme.onPrimary
    val labelBgArgb = boxColor.toArgb()
    val labelTextArgb = labelTextColor.toArgb()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 3.dp.toPx()
        val padPx = 4.dp.toPx()
        val textPx = 11.sp.toPx()

        detections.forEach { d ->
            val left = d.left * w
            val top = d.top * h
            val right = d.right * w
            val bottom = d.bottom * h
            val boxW = (right - left).coerceAtLeast(0f)
            val boxH = (bottom - top).coerceAtLeast(0f)

            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                style = Stroke(width = strokePx),
            )

            val label = d.label + (d.confidence?.let { " ${(it * 100).toInt()}%" } ?: "")
            drawIntoCanvas { canvas ->
                val textPaint =
                    android.graphics.Paint().apply {
                        color = labelTextArgb
                        textSize = textPx
                        isAntiAlias = true
                    }
                val bgPaint =
                    android.graphics.Paint().apply {
                        color = labelBgArgb
                        isAntiAlias = true
                    }
                val textWidth = textPaint.measureText(label)
                val bgTop = (top - textPx - padPx).coerceAtLeast(0f)
                canvas.nativeCanvas.drawRect(
                    left,
                    bgTop,
                    left + textWidth + padPx * 2,
                    bgTop + textPx + padPx,
                    bgPaint,
                )
                canvas.nativeCanvas.drawText(label, left + padPx, bgTop + textPx, textPaint)
            }
        }
    }
}
