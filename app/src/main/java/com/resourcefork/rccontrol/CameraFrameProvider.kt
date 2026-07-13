package com.resourcefork.rccontrol

import android.content.Context
import android.graphics.ImageFormat
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
 * Manages a CameraX session: exposes a [PreviewView] for live display and delivers compressed JPEG
 * frames via [onFrame] at approximately [targetFps].
 *
 * Multiple physical cameras (front / back / wide / tele / external) are enumerated into
 * [availableCameras]; the active one can be changed at runtime with [selectCamera], which rebinds
 * the preview + analysis use cases.
 *
 * Lifecycle is tied to the provided [LifecycleOwner] – no manual start/stop required.
 */
class CameraFrameProvider(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    /** Called on the analysis executor thread with each captured JPEG. */
    private val onFrame: (ByteArray) -> Unit,
    private val targetFps: Int = 1,
) {
    val previewView: PreviewView = PreviewView(context)

    /** The most recent JPEG frame, or null if no frame has been captured yet. */
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

    // Use cases are created once and reused across rebinds.
    private val preview: Preview by lazy {
        Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
    }
    private val analysis: ImageAnalysis by lazy {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
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

    private fun bindTo(provider: ProcessCameraProvider, cameraId: String) {
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selectorForId(cameraId), preview, analysis)
            selectedCameraId = cameraId
        } catch (_: Exception) {
            // Binding can fail if a camera is unavailable (e.g. in use by another app).
            // Leave the previous selection in place.
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

            val jpeg = yuvToJpeg(proxy)
            if (jpeg != null) {
                lastFrame = jpeg
                onFrame(jpeg)
            }
        } finally {
            proxy.close()
        }
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
}
