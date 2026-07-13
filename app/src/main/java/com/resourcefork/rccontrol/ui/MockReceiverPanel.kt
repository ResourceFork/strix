package com.resourcefork.rccontrol.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.resourcefork.rccontrol.MockMotorController
import kotlin.math.abs

/**
 * Visual "virtual device" panel that mirrors what would be sent to the Arduino.
 *
 * Shows:
 * - A pulsing indicator confirming mock mode is active.
 * - Armed / Disarmed status badge.
 * - Throttle bars for channels 1–3 (left motor, right motor, auxiliary).
 * - RGB LED colour swatch.
 * - The raw command string of the most-recent operation.
 */
@Composable
fun MockReceiverPanel(
    mockController: MockMotorController,
    modifier: Modifier = Modifier,
) {
    val state by mockController.mockState.collectAsState()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Header ───────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulsingDot()
            Text(
                "Mock Receiver",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            ArmedBadge(armed = state.armed)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── Motor channels ───────────────────────────────────────────────────
        ThrottleBar(label = "L (Ch 1)", value = state.throttle[0])
        ThrottleBar(label = "R (Ch 2)", value = state.throttle[1])
        ThrottleBar(label = "Aux (Ch 3)", value = state.throttle[2])

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── LED colour ───────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "LED",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val ledColor = Color(state.ledR / 255f, state.ledG / 255f, state.ledB / 255f)
            val animatedLed by animateColorAsState(targetValue = ledColor, label = "led")
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(animatedLed),
            )
            Text(
                "#%02X%02X%02X".format(state.ledR, state.ledG, state.ledB),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        // ── Last command ─────────────────────────────────────────────────────
        if (state.lastCommand.isNotEmpty()) {
            Text(
                "→ ${state.lastCommand}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Slowly pulsing green dot indicating active mock mode. */
@Composable
private fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(0xFF4CAF50).copy(alpha = alpha)),
    )
}

/** Badge showing "ARMED" (amber) or "DISARMED" (grey). */
@Composable
private fun ArmedBadge(armed: Boolean) {
    val bgColor = if (armed) Color(0xFFFF6F00) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (armed) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val label = if (armed) "ARMED" else "DISARMED"

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}

/**
 * Horizontal bar representing a throttle value in -100…100.
 * The bar is centred: positive values grow right (green), negative values
 * grow left (red).
 */
@Composable
private fun ThrottleBar(label: String, value: Int) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.width(64.dp),
        )

        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            // Background track
            drawRect(color = surfaceVariant)

            val fraction = value / 100f            // -1f..1f
            val halfWidth = size.width / 2f
            val barWidth = halfWidth * abs(fraction)

            if (barWidth > 0f) {
                val barColor = if (fraction >= 0f) Color(0xFF4CAF50) else Color(0xFFE53935)
                val startX = if (fraction >= 0f) halfWidth else halfWidth - barWidth
                drawRect(
                    color    = barColor,
                    topLeft  = Offset(startX, 0f),
                    size     = Size(barWidth, size.height),
                )
            }

            // Centre tick – use outlineVariant for contrast against the track
            drawLine(
                color       = outlineVariant,
                start       = Offset(halfWidth, 0f),
                end         = Offset(halfWidth, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }

        Text(
            "%+4d%%".format(value),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}
