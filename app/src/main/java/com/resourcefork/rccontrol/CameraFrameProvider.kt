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
    private var cameraProvider: ProcessCameraProvider? = null

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

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

    /** Unbinds the camera and shuts down the analysis executor. */
    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
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
     * Correctly handles rowStride and pixelStride padding to produce a valid NV21 buffer.
     */
    private fun yuvToJpeg(proxy: ImageProxy): ByteArray? {
        if (proxy.format != ImageFormat.YUV_420_888) return null

        val width  = proxy.width
        val height = proxy.height

        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]

        val yRowStride    = yPlane.rowStride
        val uvRowStride   = uPlane.rowStride
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

        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            width,
            height,
            null,
        )
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
        return out.toByteArray()
    }
}
