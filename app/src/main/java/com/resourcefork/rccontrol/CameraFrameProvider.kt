package com.resourcefork.rccontrol

import android.content.Context
import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Manages a CameraX session: exposes a [PreviewView] for live display and
 * delivers compressed JPEG frames via [onFrame] at approximately [targetFps].
 *
 * Lifecycle is tied to the provided [LifecycleOwner] – no manual
 * start/stop required.
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
    @Volatile var lastFrame: ByteArray? = null
        private set

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastFrameMs = 0L

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { ia ->
                    ia.setAnalyzer(analysisExecutor) { proxy -> processFrame(proxy) }
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(context))
    }

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
     * Converts a YUV_420_888 [ImageProxy] to a JPEG [ByteArray].
     * Uses the NV21-compatible byte layout for fast conversion with Android's
     * built-in YuvImage.
     */
    private fun yuvToJpeg(proxy: ImageProxy): ByteArray? {
        if (proxy.format != ImageFormat.YUV_420_888) return null

        val yPlane  = proxy.planes[0]
        val uPlane  = proxy.planes[1]
        val vPlane  = proxy.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val ySize = yBuf.remaining()
        val uSize = uBuf.remaining()
        val vSize = vBuf.remaining()

        // Build NV21: Y plane followed by interleaved VU bytes
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuf.get(nv21, 0, ySize)
        vBuf.get(nv21, ySize, vSize)
        uBuf.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            proxy.width,
            proxy.height,
            null,
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, proxy.width, proxy.height), 85, out)
        return out.toByteArray()
    }
}
