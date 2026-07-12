package com.resourcefork.rccontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Three RGB sliders that let the user pick an arbitrary [Color] for the LED.
 * Reports the selected color via [onColorChange] on every slider move.
 *
 * The swatch shows a live preview of the currently selected color.
 */
@Composable
fun LedColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "LED",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Color swatch
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            // Hex label
            Text(
                text  = "#%02X%02X%02X".format(
                    (color.red   * 255).toInt(),
                    (color.green * 255).toInt(),
                    (color.blue  * 255).toInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }

        ColorSliderRow(
            label    = "R",
            value    = color.red,
            trackStart = Color(0xFF1A1A1A),
            trackEnd   = Color(1f, 0f, 0f, 1f),
            onValueChange = { v -> onColorChange(color.copy(red = v)) },
        )
        ColorSliderRow(
            label    = "G",
            value    = color.green,
            trackStart = Color(0xFF1A1A1A),
            trackEnd   = Color(0f, 1f, 0f, 1f),
            onValueChange = { v -> onColorChange(color.copy(green = v)) },
        )
        ColorSliderRow(
            label    = "B",
            value    = color.blue,
            trackStart = Color(0xFF1A1A1A),
            trackEnd   = Color(0f, 0f, 1f, 1f),
            onValueChange = { v -> onColorChange(color.copy(blue = v)) },
        )
    }
}

@Composable
private fun ColorSliderRow(
    label: String,
    value: Float,
    trackStart: Color,
    trackEnd: Color,
    onValueChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style    = MaterialTheme.typography.labelSmall,
            color    = trackEnd,
            modifier = Modifier.padding(end = 4.dp),
        )
        // Gradient track background
        Box(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .align(Alignment.Center),
            ) {
                val gradient = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(trackStart, trackEnd),
                )
                drawRect(brush = gradient)
            }
            Slider(
                value          = value,
                onValueChange  = onValueChange,
                valueRange     = 0f..1f,
                modifier       = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text  = "%3d".format((value * 255).toInt()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
