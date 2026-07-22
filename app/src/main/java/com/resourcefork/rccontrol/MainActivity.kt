package com.resourcefork.rccontrol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.resourcefork.rccontrol.ui.RCControlApp
import com.resourcefork.rccontrol.ui.theme.StrixTheme

/**
 * Single-activity host for the Strix RC-car controller.
 *
 * Responsibilities:
 * - Handle the Android USB "may this app access this device?" permission flow.
 * - Host the Jetpack Compose UI via [RCControlApp].
 * - Manage the [CameraSources] (built-in CameraX + USB/UVC camera backends) lifecycle.
 *
 * After USB permission is granted, [RCViewModel.connect] is called which opens the serial port; the
 * ViewModel then exposes state to the Compose layer.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.resourcefork.rccontrol.USB_PERMISSION"
    }

    private val viewModel: RCViewModel by viewModels()
    private var cameraSources: CameraSources? = null

    // ──────────────────────────────────────────────────────────────────────────
    // USB permission broadcast receiver
    // ──────────────────────────────────────────────────────────────────────────

    private val usbPermissionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return

                synchronized(this) {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    if (granted && device != null) {
                        onUsbPermissionGranted()
                    } else {
                        onUsbPermissionDenied()
                    }
                }
            }
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Activity lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the USB permission receiver before requesting permission.
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }

        requestUsbPermissionIfNeeded()

        // Set up both camera backends behind one coordinator: built-in cameras via CameraX and
        // USB (UVC) webcams via libuvc. Each sampled frame feeds the depth reflex layer (no-op
        // without a depth model on the device); VLM inference stays manually triggered.
        val builtinCameras =
            CameraFrameProvider(
                    context = this,
                    lifecycleOwner = this,
                    onFrame = { jpeg -> viewModel.onCameraFrame(jpeg) },
                )
                .also { it.start() }
        val usbCameras =
            UsbCameraFrameProvider(
                    context = this,
                    onFrame = { jpeg -> viewModel.onCameraFrame(jpeg) },
                )
                .also { it.start() }
        cameraSources = CameraSources(builtin = builtinCameras, usb = usbCameras)

        // Compose UI
        setContent {
            StrixTheme { RCControlApp(viewModel = viewModel, cameraSources = cameraSources) }
        }
    }

    /** Also handles the case where the app is launched via USB_DEVICE_ATTACHED intent-filter. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            requestUsbPermissionIfNeeded()
        }
    }

    override fun onStop() {
        super.onStop()
        // Disarm and disconnect when the app is backgrounded so the car stops safely.
        viewModel.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        cameraSources?.stop()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // USB helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun requestUsbPermissionIfNeeded() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        // Only consider devices recognised by the USB-serial prober (e.g. CH340 Nano).
        val device =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()?.device
                ?: return // no known serial device attached

        if (usbManager.hasPermission(device)) {
            onUsbPermissionGranted()
            return
        }

        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        val permissionIntent =
            PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, permissionIntent)
        // The user sees the system "Allow app to access USB device?" dialog.
        // The result arrives in usbPermissionReceiver above.
    }

    private fun onUsbPermissionGranted() {
        viewModel.connect()
        // arm() is left as an explicit user action; the car will not move on plug-in.
    }

    private fun onUsbPermissionDenied() {
        // The ViewModel surfaces an error message to the Compose UI.
        // Nothing else to do here – the user can try again via the Connect button.
    }
}
