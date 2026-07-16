package com.resourcefork.rccontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** A camera that can be selected in the UI. [id] is the stable Camera2 camera id. */
data class CameraOption(val id: String, val label: String)

/**
 * Manages a CameraX session with two decoupled streams:
 * - [previewView]: a live full-rate (~30fps) preview surface for on-screen display.
 * - An [ImageAnalysis] stream sampled at ~[targetFps] whose frames are rotated upright and
 *   published as [lastFrame] (the JPEG handed to the VLM). Keeping VLM frames upright matters:
 *   sensor-orientation input scrambles a model's notion of left/right/ahead.
 *
 * Multiple physical cameras (front / back / wide / tele / external) are enumerated into
 * [availableCameras]; the active one can be changed at runtime with [selectCamera].
 *
 * Lifecycle is tied to the provided [LifecycleOwner] – no manual start/stop required.
 */
class CameraFrameProvider(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    /** Called on the analysis executor thread with each captured JPEG. */
    private val onFrame: (ByteArray) -> Unit,
    private val targetFps: Int = SAMPLE_FPS,
) {
    /**
     * Live camera preview surface for the UI. COMPATIBLE mode (TextureView) is required here: the
     * default PERFORMANCE mode uses a SurfaceView, which ignores the scroll offset and
     * rounded-corner clipping of the Compose container it sits in and renders black/misplaced.
     */
    val previewView: PreviewView =
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

    /** The most recent upright JPEG frame, or null if no frame has been captured yet. */
    @Volatile
    var lastFrame: ByteArray? = null
        private set

    /** Cameras discovered on this device. Observable from Compose. */
    var availableCameras: List<CameraOption> by mutableStateOf(emptyList())
        private set

    /**
     * Camera2 id of the currently bound camera, or null before binding. Observable from Compose.
     */
    var selectedCameraId: String? by mutableStateOf(null)
        private set

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastFrameMs = 0L
    private var cameraProvider: ProcessCameraProvider? = null

    // Created at start() and reused across camera switches.
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    private fun createUseCases() {
        preview =
            Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        analysis =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(
                    // Resolution of the frames handed to the VLM (the preview is unaffected).
                    // Falls back to the nearest supported size if this one isn't available.
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                ANALYSIS_RESOLUTION,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            )
                        )
                        .build()
                )
                .build()
                .also { ia -> ia.setAnalyzer(analysisExecutor) { proxy -> processFrame(proxy) } }
    }

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val provider = future.get()
                cameraProvider = provider

                availableCameras = enumerateCameras(provider)

                // Prefer the default back camera; otherwise fall back to the first camera found.
                val initialId = defaultCameraId(provider) ?: availableCameras.firstOrNull()?.id
                if (initialId != null) {
                    createUseCases()
                    bindTo(provider, initialId)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * Switches the active camera to [cameraId] (a value from [availableCameras]). No-op if the id
     * is unknown or already selected. Rebinds on the main thread.
     */
    fun selectCamera(cameraId: String) {
        if (cameraId == selectedCameraId) return
        if (availableCameras.none { it.id == cameraId }) return
        val provider = cameraProvider ?: return
        ContextCompat.getMainExecutor(context).execute { bindTo(provider, cameraId) }
    }

    /** Unbinds the camera and shuts down the analysis executor. */
    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    // -------------------------------------------------------------------------
    // Camera enumeration & binding
    // -------------------------------------------------------------------------

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun enumerateCameras(provider: ProcessCameraProvider): List<CameraOption> {
        val backCount = intArrayOf(0)
        val frontCount = intArrayOf(0)
        return provider.availableCameraInfos.mapNotNull { info ->
            val id =
                try {
                    Camera2CameraInfo.from(info).cameraId
                } catch (_: Exception) {
                    return@mapNotNull null
                }
            CameraOption(id = id, label = labelFor(info, id, backCount, frontCount))
        }
    }

    private fun labelFor(
        info: CameraInfo,
        id: String,
        backCount: IntArray,
        frontCount: IntArray,
    ): String {
        return when (info.lensFacing) {
            CameraSelector.LENS_FACING_BACK -> {
                backCount[0]++
                if (backCount[0] > 1) "Back Camera $id" else "Back Camera"
            }
            CameraSelector.LENS_FACING_FRONT -> {
                frontCount[0]++
                if (frontCount[0] > 1) "Front Camera $id" else "Front Camera"
            }
            CameraSelector.LENS_FACING_EXTERNAL -> "External Camera $id"
            else -> "Camera $id"
        }
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun defaultCameraId(provider: ProcessCameraProvider): String? {
        val backInfo =
            provider.availableCameraInfos.firstOrNull {
                it.lensFacing == CameraSelector.LENS_FACING_BACK
            } ?: return null
        return try {
            Camera2CameraInfo.from(backInfo).cameraId
        } catch (_: Exception) {
            null
        }
    }

    /** @return true if binding succeeded; false leaves any previous selection in place. */
    private fun bindTo(provider: ProcessCameraProvider, cameraId: String): Boolean {
        val previewUseCase = preview ?: return false
        val analysisUseCase = analysis ?: return false
        return try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                selectorForId(cameraId),
                previewUseCase,
                analysisUseCase,
            )
            selectedCameraId = cameraId
            true
        } catch (e: Exception) {
            // Binding can fail if a camera is unavailable (e.g. in use by another app).
            Log.w(TAG, "Camera bind failed for id=$cameraId", e)
            false
        }
    }

    /** A [CameraSelector] that resolves to the single camera with the given Camera2 [cameraId]. */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun selectorForId(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter(
                CameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }.toMutableList()
                }
            )
            .build()

    // -------------------------------------------------------------------------
    // Frame processing
    // -------------------------------------------------------------------------

    private fun processFrame(proxy: ImageProxy) {
        try {
            val nowMs = System.currentTimeMillis()
            val intervalMs = 1000L / targetFps.coerceAtLeast(1)
            if (nowMs - lastFrameMs < intervalMs) return
            lastFrameMs = nowMs

            val jpeg = yuvToJpeg(proxy) ?: return

            // Rotate the VLM frame upright: analysis frames arrive in sensor orientation,
            // which would scramble the model's notion of left/right/ahead.
            val rotation = proxy.imageInfo.rotationDegrees
            val uprightJpeg = if (rotation != 0) rotateJpeg(jpeg, rotation) ?: jpeg else jpeg

            lastFrame = uprightJpeg
            onFrame(uprightJpeg)
        } finally {
            proxy.close()
        }
    }

    /** Re-encodes [jpeg] rotated by [degrees]; null if the bytes can't be decoded. */
    private fun rotateJpeg(jpeg: ByteArray, degrees: Int): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    /**
     * Converts a YUV_420_888 [ImageProxy] to a JPEG [ByteArray]. Correctly handles rowStride and
     * pixelStride padding to produce a valid NV21 buffer.
     */
    private fun yuvToJpeg(proxy: ImageProxy): ByteArray? {
        if (proxy.format != ImageFormat.YUV_420_888) return null

        val width = proxy.width
        val height = proxy.height

        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        // Copy Y plane row by row, stripping any row padding
        val yBuf = yPlane.buffer
        for (row in 0 until height) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, row * width, width)
        }

        // Build interleaved VU plane for NV21, respecting pixel and row strides
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        var uvIndex = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uvOffset = row * uvRowStride + col * uvPixelStride
                vBuf.position(uvOffset)
                nv21[uvIndex++] = vBuf.get()
                uBuf.position(uvOffset)
                nv21[uvIndex++] = uBuf.get()
            }
        }

        val yuvImage =
            android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "CameraFrameProvider"

        /**
         * Rate at which analysis frames are sampled into [lastFrame] for the VLM. Independent of
         * the preview, which runs at the camera's native rate (~30fps). 4fps keeps the VLM's frame
         * at most ~250ms stale while costing only a few background JPEG conversions/sec.
         */
        const val SAMPLE_FPS = 4

        /**
         * Resolution of frames captured for the VLM. 640x480 balances scene understanding against
         * cost: for token-scaled cloud models (Qwen VL) it is roughly 400 visual tokens; on-device
         * Gemma 3n resizes to its fixed encoder input anyway.
         */
        val ANALYSIS_RESOLUTION = Size(640, 480)
    }
}
