package com.resourcefork.rccontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resourcefork.rccontrol.DriveAction
import com.resourcefork.rccontrol.DriveCommand
import com.resourcefork.rccontrol.DriveSpeed

/**
 * A D-pad of the declarative [DriveAction] vocabulary for a steer + powered-drive chassis. Steering
 * sharpness increases outward from the center column (veer = gentle steering, turn = full lock),
 * and the reverse row mirrors the forward row with the same terminology:
 * ```
 *   ↰ turn L    ↖ veer L    ↑ forward    ↗ veer R    ↱ turn R
 *                             ■ stop
 *   ↲ rev turn L  ↙ rev veer L  ↓ reverse  ↘ rev veer R  ↳ rev turn R
 * ```
 *
 * Each press dispatches one time-limited drive step through the same path the VLM pilot uses, so
 * the pad doubles as a test harness for tuning [DriveCommand.toDriveVector] mappings by feel.
 */
@Composable
fun DrivePad(
    enabled: Boolean,
    lastCommand: DriveCommand?,
    reflexDriveEnabled: Boolean,
    reflexDriveAvailable: Boolean,
    onReflexDriveChange: (Boolean) -> Unit,
    onAction: (DriveAction, DriveSpeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    var speed by rememberSaveable { mutableStateOf(DriveSpeed.SLOW) }
    // Manual pad controls yield to the reactive driver while it's on.
    val manualEnabled = enabled && !reflexDriveEnabled

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Depth-only reactive autopilot: the geometry layer drives directly, no VLM.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = reflexDriveEnabled,
                onCheckedChange = onReflexDriveChange,
                enabled = reflexDriveAvailable,
            )
            Text(
                when {
                    !reflexDriveAvailable -> "Reflex drive \u2013 needs a depth model"
                    reflexDriveEnabled -> "Reflex drive ON \u2013 steering from depth"
                    else -> "Reflex drive (depth-only, no VLM)"
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // Readout: two fixed single lines (direction, then analysis) so varying reason lengths
        // can never wrap and reflow the pad while the reflex driver streams commands.
        Text(
            when {
                reflexDriveEnabled ->
                    lastCommand?.let {
                        "Auto: ${it.action.name.lowercase()} (${it.speed.name.lowercase()})"
                    } ?: "Waiting for depth\u2026"
                enabled ->
                    lastCommand?.let {
                        "Last: ${it.action.name.lowercase()} (${it.speed.name.lowercase()})"
                    } ?: "Tap a direction for one drive step"
                else -> "Connect and arm to enable"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (reflexDriveEnabled) {
            // Always present while the reflex driver is on (blank when there's no reason yet),
            // so the line count – and therefore the layout height – stays constant.
            Text(
                lastCommand?.reason.orEmpty().ifBlank { " " },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Speed bucket selector – the same vocabulary the VLM pilot chooses from.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DriveSpeed.entries.forEach { s ->
                FilterChip(
                    selected = speed == s,
                    onClick = { speed = s },
                    label = { Text(s.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }

        // Row 1 – forward family, sharpest steering outermost.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(
                icon = Icons.Default.TurnLeft,
                contentDescription = "Turn left (full lock)",
                enabled = manualEnabled,
            ) {
                onAction(DriveAction.TURN_LEFT, speed)
            }
            PadButton(
                icon = Icons.Default.TurnSlightLeft,
                contentDescription = "Veer left",
                enabled = manualEnabled,
            ) {
                onAction(DriveAction.VEER_LEFT, speed)
            }
            PadButton(
                icon = Icons.Default.ArrowUpward,
                contentDescription = "Drive forward",
                enabled = manualEnabled,
            ) {
                onAction(DriveAction.FORWARD, speed)
            }
            PadButton(
                icon = Icons.Default.TurnSlightRight,
                contentDescription = "Veer right",
                enabled = manualEnabled,
            ) {
                onAction(DriveAction.VEER_RIGHT, speed)
            }
            PadButton(
                icon = Icons.Default.TurnRight,
                contentDescription = "Turn right (full lock)",
                enabled = manualEnabled,
            ) {
                onAction(DriveAction.TURN_RIGHT, speed)
            }
        }
        // Row 2 – stop on its own row.
        Row {
            PadButton(
                icon = Icons.Default.Stop,
                contentDescription = "Stop",
                enabled = manualEnabled,
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                onAction(DriveAction.STOP, speed)
            }
        }
        // Row 3 – reverse family, mirroring the forward row (icons flipped vertically so the
        // arrows trace the backward path).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(
                icon = Icons.Default.TurnLeft,
                contentDescription = "Reverse turn left (full lock)",
                enabled = manualEnabled,
                flipVertically = true,
            ) {
                onAction(DriveAction.REVERSE_TURN_LEFT, speed)
            }
            PadButton(
                icon = Icons.Default.TurnSlightLeft,
                contentDescription = "Reverse veer left",
                enabled = manualEnabled,
                flipVertically = true,
            ) {
                onAction(DriveAction.REVERSE_VEER_LEFT, speed)
            }
            PadButton(
                icon = Icons.Default.ArrowDownward,
                contentDescription = "Reverse",
                enabled = manualEnabled,
            ) {
                onAction(DriveAction.REVERSE, speed)
            }
            PadButton(
                icon = Icons.Default.TurnSlightRight,
                contentDescription = "Reverse veer right",
                enabled = manualEnabled,
                flipVertically = true,
            ) {
                onAction(DriveAction.REVERSE_VEER_RIGHT, speed)
            }
            PadButton(
                icon = Icons.Default.TurnRight,
                contentDescription = "Reverse turn right (full lock)",
                enabled = manualEnabled,
                flipVertically = true,
            ) {
                onAction(DriveAction.REVERSE_TURN_RIGHT, speed)
            }
        }
    }
}

/**
 * The icon (and whether it should be flipped vertically) representing each [DriveAction]. Shared by
 * the drive pad and the camera preview's action badge so the two always agree. The reverse family
 * reuses the forward icons, mirrored vertically, exactly like the pad rows.
 */
fun driveActionIcon(action: DriveAction): Pair<ImageVector, Boolean> =
    when (action) {
        DriveAction.FORWARD -> Icons.Default.ArrowUpward to false
        DriveAction.VEER_LEFT -> Icons.Default.TurnSlightLeft to false
        DriveAction.VEER_RIGHT -> Icons.Default.TurnSlightRight to false
        DriveAction.TURN_LEFT -> Icons.Default.TurnLeft to false
        DriveAction.TURN_RIGHT -> Icons.Default.TurnRight to false
        DriveAction.REVERSE -> Icons.Default.ArrowDownward to false
        DriveAction.REVERSE_VEER_LEFT -> Icons.Default.TurnSlightLeft to true
        DriveAction.REVERSE_VEER_RIGHT -> Icons.Default.TurnSlightRight to true
        DriveAction.REVERSE_TURN_LEFT -> Icons.Default.TurnLeft to true
        DriveAction.REVERSE_TURN_RIGHT -> Icons.Default.TurnRight to true
        DriveAction.STOP -> Icons.Default.Stop to false
    }

@Composable
private fun PadButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    flipVertically: Boolean = false,
    colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(),
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = if (flipVertically) Modifier.graphicsLayer { scaleY = -1f } else Modifier,
        )
    }
}
