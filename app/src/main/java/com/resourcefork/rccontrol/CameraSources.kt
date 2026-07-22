package com.resourcefork.rccontrol

import android.view.View

/**
 * Merges the two camera backends into a single selectable set:
 * - [builtin]: phone cameras via CameraX ([CameraFrameProvider])
 * - [usb]: OTG/UVC webcams via libuvc ([UsbCameraFrameProvider])
 *
 * The UI sees one camera list, one selected id, and one preview view; frames from whichever
 * source is active flow to the same VLM/depth pipeline. Only one source streams at a time:
 * selecting a USB camera unbinds the built-in camera, and selecting a built-in camera closes
 * the USB one.
 *
 * On devices whose HAL exposes UVC devices to Camera2 (rare), the same physical camera could
 * appear once per backend; picking either entry works.
 */
class CameraSources(
    private val builtin: CameraFrameProvider,
    private val usb: UsbCameraFrameProvider,
) {
    init {
        // If the USB camera goes away (unplugged, permission denied), return to the phone camera.
        usb.onInactive = { builtin.rebind() }
    }

    /** Built-in cameras first, then any attached USB cameras. Observable from Compose. */
    val availableCameras: List<CameraOption>
        get() = builtin.availableCameras + usb.availableCameras

    /** Id of the active camera across both backends. Observable from Compose. */
    val selectedCameraId: String?
        get() = usb.selectedCameraId ?: builtin.selectedCameraId

    /** Preview view of the active source (PreviewView or UVC surface view). */
    val previewView: View
        get() = if (usb.selectedCameraId != null) usb.previewView else builtin.previewView

    /** Latest JPEG frame from the active source (for VLM capture). */
    val lastFrame: ByteArray?
        get() = if (usb.selectedCameraId != null) usb.lastFrame else builtin.lastFrame

    /** Switches the active camera to [cameraId] (a value from [availableCameras]). */
    fun select(cameraId: String) {
        if (cameraId.startsWith(UsbCameraFrameProvider.ID_PREFIX)) {
            builtin.unbind()
            usb.open(cameraId)
        } else {
            usb.close()
            if (cameraId == builtin.selectedCameraId) builtin.rebind()
            else builtin.selectCamera(cameraId)
        }
    }

    fun stop() {
        usb.stop()
        builtin.stop()
    }
}
