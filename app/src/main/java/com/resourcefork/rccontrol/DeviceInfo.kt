package com.resourcefork.rccontrol

import android.os.Build

/** Coarse device-environment checks. */
object DeviceInfo {
    /**
     * True when running on an Android emulator.
     *
     * On-device VLM inference is unsupported there: emulators lack vendor OpenCL/GPU drivers,
     * and MediaPipe's native engine can hard-crash the process (SIGSEGV, not a catchable Java
     * exception) when asked to initialize its GPU backend without one.
     */
    val isEmulator: Boolean by lazy {
        Build.HARDWARE in listOf("goldfish", "ranchu", "cutf_cvm") ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.PRODUCT.startsWith("sdk_gphone") ||
            Build.DEVICE.startsWith("emu64")
    }
}
