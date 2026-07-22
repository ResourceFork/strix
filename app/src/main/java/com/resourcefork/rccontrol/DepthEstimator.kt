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
 * Per-band obstacle proximity derived from a monocular depth map – the geometry half of the
 * two-layer navigation architecture (the VLM is the semantics half).
 *
 * Scores are 0..1 *nearness*: 0 = open space, 1 = something filling the band right at the bumper.
 * [bands] holds [BAND_COUNT] vertical bands ordered left → right: `[outer-left, inner-left, center,
 * inner-right, outer-right]`.
 *
 * Five narrow bands (instead of the original three wide thirds) keep the center band close to the
 * car's true driving lane, so an obstacle off to one side no longer reads as "blocked ahead" – the
 * main cause of the reflex driver bouncing left/right instead of driving forward.
 */
data class CorridorReport(
    val bands: List<Float>,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    init {
        require(bands.size == BAND_COUNT) { "expected $BAND_COUNT bands, got ${bands.size}" }
    }

    /** Nearness of the center band – the car's actual driving lane. */
    val center: Float
        get() = bands[bands.size / 2]

    /** True while the report is recent enough to act on (camera or estimator may have stalled). */
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs - timestampMs <= FRESH_MS

    /** True when the center band reads as blocked \u2013 the reflex-veto trigger. */
    val centerBlocked: Boolean
        get() = center >= BLOCK_THRESHOLD

    companion object {
        /** Number of vertical bands the frame is split into. Odd, so a true center lane exists. */
        const val BAND_COUNT = 5

        /** Reports older than this are ignored (a stale "clear" must never authorize motion). */
        const val FRESH_MS = 1500L

        /** Center nearness at/above this vetoes forward motion. Tune on the bench. */
        const val BLOCK_THRESHOLD = 0.65f

        /**
         * Vertical slice of the frame the scores describe, as fractions of image height. Rows above
         * [SCORE_WINDOW_TOP] are walls/ceiling that don't block a floor robot; rows below
         * [SCORE_WINDOW_BOTTOM] are the patch of floor immediately at the bumper (always near, no
         * navigational value). Within this window the estimator scores only the upper portion for
         * obstacles. Shared with the camera overlay so the shaded region matches what is scored.
         */
        const val SCORE_WINDOW_TOP = 0.50f
        const val SCORE_WINDOW_BOTTOM = 0.85f
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
 * Runs a MiDaS-style monocular depth model (LiteRT / TFLite) on a JPEG camera frame and reduces the
 * depth map to a [CorridorReport].
 *
 * Model contract (MiDaS v2.1 small, `model_opt.tflite` from the isl-org/MiDaS GitHub release):
 * - Input: `[1, H, W, 3]` float32 RGB, ImageNet mean/std normalized (H = W = 256).
 * - Output: `[1, H, W]` float32 *inverse relative* depth – larger = closer, unitless and only
 *   consistent within a single frame, hence the per-frame normalization below.
 *
 * Band scoring: only the window between [CorridorReport.SCORE_WINDOW_TOP] and
 * [CorridorReport.SCORE_WINDOW_BOTTOM] of the map height is considered – above it is walls and
 * ceiling that don't block a floor robot, below it is the always-near patch of floor at the bumper.
 * Two robust percentiles over that window set an open→near scale (`openRef`..`nearRef`); each of
 * [CorridorReport.BAND_COUNT] vertical bands is then scored by how near the *upper slice* of the
 * band reads on that scale. Scoring the upper slice (not the whole window) is what stops empty
 * floor from reading as blocked: open floor near the horizon is far and lands at ~0, while a
 * standing obstacle brings near depth into that region and reads high.
 *
 * Known limitations (monocular depth is inference, not measurement – this is why the fused
 * [ObstacleField] also folds in the measured ToF/ultrasonics):
 * - A near surface with no visible open floor for reference (e.g. a wall filling the frame) can
 *   collapse the open→near scale; the measured sensors are the reliable signal there.
 * - Textureless walls, glass, and very low light degrade the depth map itself. The veto threshold
 *   [CorridorReport.BLOCK_THRESHOLD] stays deliberately conservative for this.
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

        // Score only the window between the horizon and the bumper (see CorridorReport.SCORE_*):
        // above it is walls/ceiling, below it is the near floor at the bumper.
        val rowStart = (outputHeight * CorridorReport.SCORE_WINDOW_TOP).toInt()
        val rowEnd =
            (outputHeight * CorridorReport.SCORE_WINDOW_BOTTOM).toInt().coerceAtMost(outputHeight)
        if (rowEnd - rowStart < 2) return null

        // Open/near references from robust percentiles over the whole window (not raw min/max, so
        // a few noisy pixels can't define the scale): openRef ~ the farthest/most-open depth in
        // view, nearRef ~ the nearest. Scores are placed on this open→near scale.
        val windowVals = FloatArray((rowEnd - rowStart) * outputWidth)
        var wi = 0
        for (row in rowStart until rowEnd) {
            val ro = row * outputWidth
            for (col in 0 until outputWidth) windowVals[wi++] = depth[ro + col]
        }
        windowVals.sort()
        val openRef = windowVals[(windowVals.size * OPEN_PCTL) / 100]
        val nearRef = windowVals[(windowVals.size * NEAR_PCTL) / 100]
        val span = nearRef - openRef
        if (span <= 0f || !span.isFinite()) return null

        // Score the UPPER slice of the window. Open floor there is far (reads ~openRef → ~0); a
        // standing obstacle brings near depth into it (→ high). This is the fix for empty floor
        // reading 60%+ – the old code took p90 over the full window, which just sampled the
        // always-near floor at the bumper. Short obstacles that only intrude at the very bottom
        // are deliberately left to the measured ToF/ultrasonics.
        val obstacleRowEnd =
            (rowStart + ((rowEnd - rowStart) * OBSTACLE_ROWS_FRAC).toInt()).coerceAtLeast(
                rowStart + 1
            )

        fun bandScore(colStart: Int, colEnd: Int): Float {
            val values = FloatArray((obstacleRowEnd - rowStart) * (colEnd - colStart))
            var i = 0
            for (row in rowStart until obstacleRowEnd) {
                val rowOffset = row * outputWidth
                for (col in colStart until colEnd) {
                    values[i++] = depth[rowOffset + col]
                }
            }
            values.sort()
            // A high percentile = "the nearest substantial thing in this band's upper region",
            // resistant to single-pixel noise. Placed on the open→near scale.
            val near = values[(values.size * BAND_PCTL) / 100]
            return ((near - openRef) / span).coerceIn(0f, 1f)
        }

        val bandCount = CorridorReport.BAND_COUNT
        val bandWidth = outputWidth / bandCount
        return CorridorReport(
            bands =
                List(bandCount) { i ->
                    val colStart = i * bandWidth
                    // The last band absorbs any remainder columns.
                    val colEnd = if (i == bandCount - 1) outputWidth else (i + 1) * bandWidth
                    bandScore(colStart, colEnd)
                }
        )
    }

    private companion object {
        const val TAG = "DepthEstimator"

        // ---- Scoring tunables (bench-tune while watching the on-screen bands) ----
        /** Percentile over the whole window taken as the open/far reference (→ score 0). */
        const val OPEN_PCTL = 20
        /** Percentile over the whole window taken as the near reference (→ score 1). */
        const val NEAR_PCTL = 90
        /** Per-band percentile within the upper slice = "nearest substantial thing" there. */
        const val BAND_PCTL = 80
        /**
         * Fraction of the scoring window (from its top/horizon edge down) actually scored for
         * obstacles. Smaller = only tall/upright obstacles register and open floor reads lower;
         * larger = more sensitive but the near floor creeps back into the score.
         */
        const val OBSTACLE_ROWS_FRAC = 0.6f

        // ImageNet statistics, per the MiDaS model card.
        const val MEAN_R = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_B = 0.406f
        const val STD_R = 0.229f
        const val STD_G = 0.224f
        const val STD_B = 0.225f
    }
}
