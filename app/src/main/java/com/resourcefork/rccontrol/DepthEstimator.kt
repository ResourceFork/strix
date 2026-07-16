package com.resourcefork.rccontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * Per-corridor obstacle proximity derived from a monocular depth map – the geometry half of the
 * two-layer navigation architecture (the VLM is the semantics half).
 *
 * Scores are 0..1 *nearness*: 0 = open space, 1 = something filling the corridor right at the
 * bumper. Three corridors match the steering vocabulary's resolution (left / center / right).
 */
data class CorridorReport(
    val left: Float,
    val center: Float,
    val right: Float,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    /** True while the report is recent enough to act on (camera or estimator may have stalled). */
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - timestampMs <= FRESH_MS

    /** True when the center corridor reads as blocked \u2013 the reflex-veto trigger. */
    val centerBlocked: Boolean
        get() = center >= BLOCK_THRESHOLD

    companion object {
        /** Reports older than this are ignored (a stale "clear" must never authorize motion). */
        const val FRESH_MS = 1500L

        /** Center nearness at/above this vetoes forward motion. Tune on the bench. */
        const val BLOCK_THRESHOLD = 0.65f
    }
}

/**
 * Finds a monocular depth model (`.tflite`) pushed or downloaded to the device. Same search
 * locations and freshest-file-wins rule as [LocalModelLocator] (which handles the `.task` VLMs).
 */
object DepthModelLocator {
    fun resolve(context: Context): String? {
        for (dir in LocalModelLocator.candidateDirs(context)) {
            val model =
                dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".tflite") }
                    ?.maxByOrNull { it.lastModified() }
            if (model != null) return model.absolutePath
        }
        return null
    }
}

/**
 * Runs a MiDaS-style monocular depth model (LiteRT / TFLite) on a JPEG camera frame and reduces
 * the depth map to a [CorridorReport].
 *
 * Model contract (MiDaS v2.1 small, `model_opt.tflite` from the isl-org/MiDaS GitHub release):
 * - Input: `[1, H, W, 3]` float32 RGB, ImageNet mean/std normalized (H = W = 256).
 * - Output: `[1, H, W]` float32 *inverse relative* depth – larger = closer, unitless and only
 *   consistent within a single frame, hence the per-frame normalization below.
 *
 * Corridor scoring: only the lower half of the map is considered (the ground plane immediately
 * ahead of the bumper – the upper half is walls and ceiling that don't block a floor robot). The
 * map is split into vertical thirds, each scored by its 90th-percentile inverse depth, min-max
 * normalized across the whole frame.
 *
 * Known limitation of per-frame normalization: in a completely open scene the normalization
 * amplifies noise, so scores are most trustworthy when *something* is in view. The veto threshold
 * in [CorridorReport.BLOCK_THRESHOLD] is deliberately conservative for this reason.
 *
 * Not thread-safe – call from a single worker (frames are dropped upstream while a run is in
 * flight).
 */
class DepthEstimator(val modelPath: String) : Closeable {

    private val interpreter: Interpreter =
        Interpreter(File(modelPath), Interpreter.Options().setNumThreads(2))

    private val inputHeight: Int
    private val inputWidth: Int
    private val outputHeight: Int
    private val outputWidth: Int
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer

    init {
        val inShape = interpreter.getInputTensor(0).shape() // [1, H, W, 3]
        inputHeight = inShape[1]
        inputWidth = inShape[2]
        val outShape = interpreter.getOutputTensor(0).shape() // [1, H, W] or [1, H, W, 1]
        outputHeight = outShape[1]
        outputWidth = outShape[2]
        inputBuffer =
            ByteBuffer.allocateDirect(inputHeight * inputWidth * 3 * 4)
                .order(ByteOrder.nativeOrder())
        outputBuffer =
            ByteBuffer.allocateDirect(outShape.fold(1) { a, b -> a * b } * 4)
                .order(ByteOrder.nativeOrder())
        Log.i(TAG, "Depth model loaded: $modelPath (in ${inputWidth}x$inputHeight)")
    }

    /** Runs one frame through the model; null if the JPEG can't be decoded. Blocking. */
    fun estimateCorridors(jpeg: ByteArray): CorridorReport? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        fillInputBuffer(scaled)
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        return scoreCorridors()
    }

    override fun close() {
        interpreter.close()
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** ImageNet normalization used by MiDaS: x' = (x/255 - mean) / std, RGB order. */
    private fun fillInputBuffer(bitmap: Bitmap) {
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        inputBuffer.rewind()
        for (p in pixels) {
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            inputBuffer.putFloat((r - MEAN_R) / STD_R)
            inputBuffer.putFloat((g - MEAN_G) / STD_G)
            inputBuffer.putFloat((b - MEAN_B) / STD_B)
        }
    }

    private fun scoreCorridors(): CorridorReport? {
        outputBuffer.rewind()
        val depth = FloatArray(outputHeight * outputWidth)
        outputBuffer.asFloatBuffer().get(depth)

        // Per-frame min/max for normalization (inverse depth is unitless across frames).
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in depth) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = max - min
        if (range <= 0f || !range.isFinite()) return null

        // Lower half only: the ground plane ahead of the bumper.
        val rowStart = outputHeight / 2
        val third = outputWidth / 3

        fun corridorScore(colStart: Int, colEnd: Int): Float {
            val values = FloatArray((outputHeight - rowStart) * (colEnd - colStart))
            var i = 0
            for (row in rowStart until outputHeight) {
                val rowOffset = row * outputWidth
                for (col in colStart until colEnd) {
                    values[i++] = depth[rowOffset + col]
                }
            }
            values.sort()
            // p90 of inverse depth = "how close is the nearest substantial thing here".
            val p90 = values[(values.size * 9) / 10]
            return ((p90 - min) / range).coerceIn(0f, 1f)
        }

        return CorridorReport(
            left = corridorScore(0, third),
            center = corridorScore(third, 2 * third),
            right = corridorScore(2 * third, outputWidth),
        )
    }

    private companion object {
        const val TAG = "DepthEstimator"

        // ImageNet statistics, per the MiDaS model card.
        const val MEAN_R = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_B = 0.406f
        const val STD_R = 0.229f
        const val STD_G = 0.224f
        const val STD_B = 0.225f
    }
}
