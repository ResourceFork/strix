package com.resourcefork.rccontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * A circular virtual joystick.
 *
 * Drags within the base circle move the thumb.  The thumb snaps back to the
 * centre when the finger is lifted.  Normalised axes are reported via [onInput]
 * continuously while dragging and with (0, 0) on release.
 *
 * @param onInput  Called with x in [-1, 1] (right positive) and
 *                 y in [-1, 1] (up positive, matching typical gamepad convention).
 */
@Composable
fun VirtualJoystick(
    modifier: Modifier = Modifier,
    baseSize: Dp = 200.dp,
    thumbSize: Dp = 64.dp,
    baseColor: Color = Color(0xFF263238),
    thumbColor: Color = Color(0xFF4FC3F7),
    onInput: (x: Float, y: Float) -> Unit,
) {
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }
    val halfBase = baseSize / 2
    val maxRadius = halfBase - (thumbSize / 2)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(baseSize)
            .background(baseColor, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd   = {
                        thumbOffset = Offset.Zero
                        onInput(0f, 0f)
                    },
                    onDragCancel = {
                        thumbOffset = Offset.Zero
                        onInput(0f, 0f)
                    },
                ) { change, dragAmount ->
                    change.consume()
                    val new = thumbOffset + dragAmount
                    val maxPx = maxRadius.toPx()
                    val dist  = sqrt(new.x * new.x + new.y * new.y)
                    thumbOffset = if (dist <= maxPx) new else new * (maxPx / dist)

                    val xNorm =  (thumbOffset.x / maxPx).coerceIn(-1f, 1f)
                    val yNorm = -(thumbOffset.y / maxPx).coerceIn(-1f, 1f) // invert Y (screen Y grows downward)
                    onInput(xNorm, yNorm)
                }
            },
    ) {
        // Cross-hair guide lines
        Canvas(modifier = Modifier.size(baseSize)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val lineColor = Color.White.copy(alpha = 0.12f)
            drawLine(lineColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.dp.toPx())
            drawLine(lineColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.dp.toPx())

            // Thumb
            val tx = cx + thumbOffset.x
            val ty = cy + thumbOffset.y
            drawCircle(color = thumbColor, radius = thumbSize.toPx() / 2, center = Offset(tx, ty))
            drawCircle(
                color  = Color.White.copy(alpha = 0.3f),
                radius = thumbSize.toPx() / 4,
                center = Offset(tx - thumbSize.toPx() / 8, ty - thumbSize.toPx() / 8),
            )
        }
    }
}
