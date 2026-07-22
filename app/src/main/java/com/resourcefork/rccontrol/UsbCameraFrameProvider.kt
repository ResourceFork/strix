package com.resourcefork.rccontrol

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.SurfaceHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.UVCCamera
import com.serenegiant.widget.AspectRatioSurfaceView
import java.io.ByteArrayOutputStream

/**
 * Frame source for USB (UVC) cameras – webcams like the Arducam plugged in over an OTG hub.
 *
 * Phones without the external-camera HAL (most of them, including the Razr) never surface UVC
 * devices through Camera2/CameraX, so this provider drives them directly over libusb/libuvc via
 * the UVCAndroid library. It mirrors [CameraFrameProvider]'s surface: an observable list of
 * [CameraOption]s, a preview [android.view.View], and sampled upright JPEG frames published to
 * [lastFrame] / [onFrame] for the VLM + depth pipeline.
 *
 * Ids are prefixed with [ID_PREFIX] so they can never collide with Camera2 ids when merged into
 * one dropdown (see [CameraSources]).
 *
 * Lifecycle: [start] once, then [open]/[close] as the user selects cameras, [stop] on teardown.
 * The USB permission dialog is handled inside the library when [open] selects a device.
 */
class UsbCameraFrameProvider(
    context: Context,
    /** Called on a library thread with each sampled JPEG. */
    private val onFrame: (ByteArray) -> Unit,
    private val targetFps: Int = SAMPLE_FPS,
) {
    /** Live preview surface for the UI while a USB camera is active. */
    val previewView: AspectRatioSurfaceView = AspectRatioSurfaceView(context)

    /** USB cameras currently attached. Observable from Compose. */
    var availableCameras: List<CameraOption> by mutableStateOf(emptyList())
        private set

    /**
     * Id (with [ID_PREFIX]) of the USB camera the user selected, or null when no USB camera is
     * active. Set optimistically at [open] so the UI switches immediately; cleared on [close],
     * permission denial, or device detach. Observable from Compose.
     */
    var selectedCameraId: String? by mutableStateOf(null)
        private set

    /** The most recent JPEG frame from the USB camera, or null before the first frame. */
    @Volatile
    var lastFrame: ByteArray? = null
        private set

    /** Invoked when the active camera stops being usable (denied / detached / cancelled). */
    var onInactive: (() -> Unit)? = null

    private var cameraHelper: ICameraHelper? = null
    private val devicesById = LinkedHashMap<String, UsbDevice>()
    private var previewSize: com.serenegiant.usb.Size? = null
    private var lastFrameMs = 0L

    init {
        previewView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (selectedCameraId != null) {
                        cameraHelper?.addSurface(holder.surface, false)
                    }
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {}

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    cameraHelper?.removeSurface(holder.surface)
                }
            }
        )
    }

    fun start() {
        if (cameraHelper != null) return
        cameraHelper = CameraHelper().also { it.setStateCallback(stateCallback) }
    }

    /**
     * Opens the USB camera with the given [cameraId] (a value from [availableCameras]). Triggers
     * the system USB permission dialog on first use of a device.
     */
    fun open(cameraId: String) {
        val device = devicesById[cameraId] ?: return
        selectedCameraId = cameraId
        cameraHelper?.selectDevice(device)
    }

    /** Closes the active USB camera (if any). The device stays listed for re-selection. */
    fun close() {
        if (selectedCameraId == null) return
        selectedCameraId = null
        cameraHelper?.closeCamera()
    }

    /** Releases the camera service connection. The instance cannot be restarted after this. */
    fun stop() {
        cameraHelper?.release()
        cameraHelper = null
    }

    // -------------------------------------------------------------------------
    // UVC state callback – attach/detach drive enumeration, open drives streaming
    // -------------------------------------------------------------------------

    private val stateCallback =
        object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                if (!isVideoClass(device)) return
                val id = idFor(device)
                Log.i(TAG, "USB camera attached: $id (${device.productName})")
                devicesById[id] = device
                publishDeviceList()
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                // USB permission granted and device opened – start the actual camera.
                cameraHelper?.openCamera()
            }

            override fun onCameraOpen(device: UsbDevice) {
                val helper = cameraHelper ?: return
                helper.startPreview()
                previewSize = helper.previewSize
                previewSize?.let { previewView.setAspectRatio(it.width, it.height) }
                previewView.holder.surface?.let { helper.addSurface(it, false) }
                helper.setFrameCallback(
                    { buffer -> processFrame(buffer) },
                    UVCCamera.PIXEL_FORMAT_NV21,
                )
            }

            override fun onCameraClose(device: UsbDevice) {
                cameraHelper?.removeSurface(previewView.holder.surface)
            }

            override fun onDeviceClose(device: UsbDevice) {}

            override fun onDetach(device: UsbDevice) {
                val id = idFor(device)
                Log.i(TAG, "USB camera detached: $id")
                devicesById.remove(id)
                publishDeviceList()
                if (id == selectedCameraId) {
                    selectedCameraId = null
                    onInactive?.invoke()
                }
            }

            override fun onCancel(device: UsbDevice) {
                // User denied the USB permission dialog.
                if (idFor(device) == selectedCameraId) {
                    selectedCameraId = null
                    onInactive?.invoke()
                }
            }
        }

    private fun publishDeviceList() {
        availableCameras =
            devicesById.map { (id, device) ->
                CameraOption(id = id, label = "USB: ${device.productName ?: "Camera"}")
            }
    }

    // -------------------------------------------------------------------------
    // Frame processing – NV21 buffer -> sampled JPEG
    // -------------------------------------------------------------------------

    private fun processFrame(buffer: java.nio.ByteBuffer) {
        val size = previewSize ?: return
        val nowMs = System.currentTimeMillis()
        val intervalMs = 1000L / targetFps.coerceAtLeast(1)
        if (nowMs - lastFrameMs < intervalMs) return
        lastFrameMs = nowMs

        val nv21 = ByteArray(buffer.remaining())
        buffer.get(nv21)
        val jpeg = nv21ToJpeg(nv21, size.width, size.height) ?: return
        lastFrame = jpeg
        onFrame(jpeg)
    }

    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray? {
        return try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, width, height), 85, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "NV21->JPEG conversion failed", e)
            null
        }
    }

    private fun isVideoClass(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    companion object {
        private const val TAG = "UsbCameraFrameProvider"

        /** Prefix that keeps USB camera ids disjoint from Camera2 ids in the merged dropdown. */
        const val ID_PREFIX = "usb:"

        /** Same VLM sampling rate as [CameraFrameProvider.SAMPLE_FPS]. */
        private const val SAMPLE_FPS = 4

        private fun idFor(device: UsbDevice): String = ID_PREFIX + device.deviceName
    }
}
