package com.resourcefork.rccontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.resourcefork.rccontrol.CameraOption
import com.resourcefork.rccontrol.CorridorReport
import com.resourcefork.rccontrol.Detection
import com.resourcefork.rccontrol.DistanceReport
import com.resourcefork.rccontrol.DriveAction
import com.resourcefork.rccontrol.DriveCommand
import com.resourcefork.rccontrol.ObstacleField
import com.resourcefork.rccontrol.R

/**
 * Camera + VLM section, kept lean for at-the-car use. Configuration (backend selection, server
 * settings, model downloads) lives in [SettingsScreen] behind the top-bar menu.
 *
 * Shows:
 * 1. A camera selector dropdown (front / back / wide / external, etc.).
 * 2. A live full-rate camera preview with a bounding-box overlay.
 * 3. A one-line backend status (which engine will run, and whether it's ready).
 * 4. The prompt field and the "Analyze Frame" / "Detect Objects" / "Pilot Step" buttons.
 * 5. A scrollable area where the streamed VLM response appears.
 */
@Composable
fun CameraSection(
    previewView: View?,
    vlmOutput: String,
    vlmRunning: Boolean,
    cameraPermissionGranted: Boolean,
    cameras: List<CameraOption>,
    selectedCameraId: String?,
    detections: List<Detection>,
    corridors: CorridorReport?,
    distances: DistanceReport?,
    driveCommand: DriveCommand?,
    backendStatus: String,
    backendReady: Boolean,
    geometryStatus: String?,
    rangeStatus: String?,
    continuousMode: Boolean,
    continuousActive: Boolean,
    onContinuousModeChange: (Boolean) -> Unit,
    onCameraPermissionResult: (Boolean) -> Unit,
    onSelectCamera: (String) -> Unit,
    onAnalyze: (prompt: String) -> Unit,
    onDetect: (prompt: String) -> Unit,
    onPilot: (goal: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onCameraPermissionResult(granted)
        }

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

            // Live camera preview (full rate) with the detection overlay on top.
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
            ) {
                if (previewView != null) {
                    // key(): the view instance changes when the user switches between the
                    // built-in (PreviewView) and USB (SurfaceView) sources; AndroidView must be
                    // recreated for the new instance rather than reusing the old factory result.
                    key(previewView) {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    }
                }
                // Depth corridors underneath so detection boxes stay readable on top.
                if (corridors != null) {
                    CorridorOverlay(corridors = corridors, modifier = Modifier.fillMaxSize())
                }
                if (detections.isNotEmpty()) {
                    DetectionOverlay(detections = detections, modifier = Modifier.fillMaxSize())
                }
                // "Doing" layer: what action is currently commanded, on top of what it sees.
                if (driveCommand != null) {
                    ActionBadge(
                        command = driveCommand,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    )
                }
                // Measured readouts, placed to mirror the sensors' physical positions on the
                // bumper: each pill sits directly above the obstacle-bar segment its sensor
                // anchors (left/center/right), tying the number to its place in the heatmap.
                if (distances != null) {
                    fun anchorBias(anchor: Int): Float =
                        ((anchor + 0.5f) / ObstacleField.SEGMENT_COUNT) * 2f - 1f
                    SensorReadout(
                        formatMm(distances.frontLeftMm),
                        Modifier.align(BiasAlignment(anchorBias(ObstacleField.ANCHOR_LEFT), 1f))
                            .padding(bottom = 40.dp),
                    )
                    SensorReadout(
                        formatMm(distances.centerMm),
                        Modifier.align(BiasAlignment(anchorBias(ObstacleField.ANCHOR_CENTER), 1f))
                            .padding(bottom = 40.dp),
                    )
                    SensorReadout(
                        formatMm(distances.frontRightMm),
                        Modifier.align(BiasAlignment(anchorBias(ObstacleField.ANCHOR_RIGHT), 1f))
                            .padding(bottom = 40.dp),
                    )
                }
                // The fused picture: all four forward sources (camera depth bands + ToF +
                // corner ultrasonics) aggregated into one segmented strip along the bottom.
                val obstacleField =
                    remember(corridors, distances) { ObstacleField.fuse(corridors, distances) }
                if (obstacleField != null) {
                    ObstacleBar(
                        field = obstacleField,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            // One-line backend status; full configuration lives in Settings (top-bar menu).
            Text(
                backendStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            // Depth reflex-layer readout (only when a depth model is producing reports).
            if (geometryStatus != null) {
                Text(
                    geometryStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            // Measured ranges from the Arduino's sensors (ToF + ultrasonics), when connected.
            if (rangeStatus != null) {
                Text(
                    rangeStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
            )

            // Continuous mode: the next Analyze/Detect/Pilot press loops capture→infer→display
            // until this is toggled off.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = continuousMode, onCheckedChange = onContinuousModeChange)
                Text(
                    if (continuousActive) "Continuous – running (toggle off to stop)"
                    else "Continuous – repeat the next action until stopped",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val canRun = !vlmRunning && backendReady && !continuousActive
                Button(onClick = { onAnalyze(prompt) }, enabled = canRun) { Text("Analyze Frame") }
                Button(onClick = { onDetect(prompt) }, enabled = canRun) { Text("Detect Objects") }
                Button(onClick = { onPilot(prompt) }, enabled = canRun) { Text("Pilot Step") }
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

/**
 * Shades the depth-reflex bands over the slice of the camera preview that
 * [com.resourcefork.rccontrol.DepthEstimator] actually scores – between
 * [CorridorReport.SCORE_WINDOW_TOP] and [CorridorReport.SCORE_WINDOW_BOTTOM], the ground plane
 * ahead of the bumper, excluding the always-near patch of floor at the bumper itself. Band tint
 * tracks nearness (green = open → red = blocked) with the score printed at the bottom of each band;
 * the center band gains a red outline once the *camera layer alone* crosses the veto threshold.
 * This is a camera-layer diagnostic: the veto itself reads the fused
 * [com.resourcefork.rccontrol.ObstacleField] (camera + measured sensors), whose aggregate is
 * rendered by [ObstacleBar].
 *
 * This overlay is passive: it renders whatever the always-on depth layer last reported and needs no
 * button – it appears as soon as a depth model on the device starts producing reports.
 */
@Composable
private fun CorridorOverlay(corridors: CorridorReport, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val top = h * CorridorReport.SCORE_WINDOW_TOP
        val bottom = h * CorridorReport.SCORE_WINDOW_BOTTOM
        val bandH = bottom - top
        val scores = corridors.bands
        val bandW = w / scores.size
        val centerIndex = scores.size / 2
        val strokePx = 2.dp.toPx()
        val textPx = 11.sp.toPx()
        val padPx = 4.dp.toPx()
        scores.forEachIndexed { i, score ->
            val x = i * bandW
            drawRect(
                color = nearnessColor(score).copy(alpha = 0.30f),
                topLeft = Offset(x, top),
                size = Size(bandW, bandH),
            )
            // Veto warning: outline the center band once it reads as blocked.
            if (i == centerIndex && score >= CorridorReport.BLOCK_THRESHOLD) {
                drawRect(
                    color = Color.Red,
                    topLeft = Offset(x + strokePx / 2, top + strokePx / 2),
                    size = Size(bandW - strokePx, bandH - strokePx),
                    style = Stroke(width = strokePx),
                )
            }
            // "% blocked": 0% = open, 100% = obstacle at the bumper. (Under the hood it's the
            // band's relative nearness rank within this frame, but "percent blocked" is the
            // reading that matters when driving.)
            val label = "${(score * 100).toInt()}%"
            drawIntoCanvas { canvas ->
                val paint =
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = textPx
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
                    }
                canvas.nativeCanvas.drawText(label, x + bandW / 2, bottom - padPx, paint)
            }
        }
    }
}

/** Green (open) → amber → red (blocked) tint for a 0..1 nearness score. */
private fun nearnessColor(score: Float): Color {
    val s = score.coerceIn(0f, 1f)
    val green = Color(0xFF2E7D32)
    val amber = Color(0xFFF9A825)
    val red = Color(0xFFC62828)
    return if (s < 0.5f) lerp(green, amber, s * 2f) else lerp(amber, red, (s - 0.5f) * 2f)
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

/**
 * Compact badge over the preview showing the current drive [command] as an icon + label – the
 * "doing" layer of the combined see / think / do view. Uses the same icon vocabulary as the drive
 * pad ([driveActionIcon]); STOP is tinted red so a halt reads at a glance.
 */
@Composable
private fun ActionBadge(command: DriveCommand, modifier: Modifier = Modifier) {
    val (icon, flip) = driveActionIcon(command.action)
    val tint = if (command.action == DriveAction.STOP) Color(0xFFE53935) else Color.White
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = command.action.name,
            tint = tint,
            modifier = (if (flip) Modifier.graphicsLayer { scaleY = -1f } else Modifier).size(18.dp),
        )
        Text(
            command.action.name.lowercase().replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/**
 * The fused obstacle strip: [ObstacleField.SEGMENT_COUNT] segments across the forward arc, left →
 * right, each tinted by the aggregate nearness of everything the car senses in that direction
 * (camera depth bands + ToF + corner ultrasonics, pessimistic max). Green = open, red = blocked;
 * this is the single picture the "should I advance?" decision reads.
 */
@Composable
private fun ObstacleBar(field: ObstacleField, modifier: Modifier = Modifier) {
    // One continuous strip: segments butt against each other with no gaps, and only the strip's
    // end caps are rounded (a single clip on the row; the segments themselves are rectangles).
    Row(
        modifier =
            modifier.fillMaxWidth().height(32.dp).padding(2.dp).clip(RoundedCornerShape(6.dp))
    ) {
        field.segments.forEach { nearness ->
            Box(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .background(nearnessColor(nearness).copy(alpha = 0.85f))
            )
        }
    }
}

/** One measured-distance pill over the preview ("43cm", "1.2m", or "–" for no reading). */
@Composable
private fun SensorReadout(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Millimeters -> compact human units with at most one decimal place: "43cm", "1.2m", "–". */
private fun formatMm(mm: Int?): String =
    when {
        mm == null -> "\u2013"
        mm < 1000 -> "${mm / 10}cm"
        else -> "%.1fm".format(mm / 1000f)
    }
