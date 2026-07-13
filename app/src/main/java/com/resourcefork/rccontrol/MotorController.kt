package com.resourcefork.rccontrol

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Clean API for communicating with the multi-channel Arduino Nano controller
 * over USB serial (CH340 clone, 115200 baud).
 *
 * Wire protocol (newline-terminated ASCII):
 *   A:1     – arm (required before throttle commands take effect)
 *   A:0     – disarm (all channels neutral, LED off)
 *   T<ch>:<v> – set channel ch (1–3) to v (-100…100)
 *   C:r,g,b  – set RGB LED (each 0–255)
 *   ?        – ping; reply is "OK:<armed>:<t1>:<t2>:<t3>"
 *
 * All public methods are safe to call from any thread.
 */
class MotorController(private val context: Context) {

    companion object {
        private const val BAUD_RATE = 115200
        private const val WRITE_TIMEOUT_MS = 200
        private const val READ_TIMEOUT_MS = 500
        private const val READ_BUFFER_SIZE = 128
    }

    @Volatile private var port: UsbSerialPort? = null
    private val lock = ReentrantLock()

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    /**
     * Opens the first available USB serial device.
     * Must be called after USB permission has been granted.
     *
     * @return true if the port was opened successfully.
     */
    fun connect(): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) return false

        val driver = drivers.first()
        val connection = usbManager.openDevice(driver.device) ?: return false

        val newPort = driver.ports.firstOrNull() ?: run {
            connection.close()
            return false
        }

        return try {
            newPort.open(connection)
            newPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            lock.withLock { port = newPort }
            true
        } catch (e: IOException) {
            try { connection.close() } catch (_: Exception) {}
            false
        }
    }

    /** Closes the serial port and releases the USB connection. */
    fun disconnect() {
        lock.withLock {
            try { port?.close() } catch (_: Exception) {}
            port = null
        }
    }

    /** @return true if the serial port is currently open. */
    val isConnected: Boolean get() = port != null

    // -------------------------------------------------------------------------
    // Arm / disarm
    // -------------------------------------------------------------------------

    /**
     * Arms the controller.  Throttle commands are ignored until armed.
     * Sends "A:1\n".
     */
    fun arm() = sendCommand("A:1")

    /**
     * Disarms the controller.  All servo channels return to neutral and the
     * LED is turned off.  Sends "A:0\n".
     */
    fun disarm() = sendCommand("A:0")

    // -------------------------------------------------------------------------
    // Motor / servo control
    // -------------------------------------------------------------------------

    /**
     * Sets a servo / ESC channel to the given throttle value.
     *
     * @param channel 1, 2, or 3  (D9, D10, D11 on the Nano).
     * @param value   -100 (full reverse / left) … 0 (neutral) … 100 (full forward / right).
     */
    fun setThrottle(channel: Int, value: Int) {
        require(channel in 1..3) { "channel must be 1–3, got $channel" }
        sendCommand("T$channel:${value.coerceIn(-100, 100)}")
    }

    /**
     * Convenience helper for tank-style differential drive.
     * Mixes [throttle] (-100…100) and [steering] (-100…100) into left / right
     * motor values and sends them to channels 1 and 2 respectively.
     *
     * Positive throttle = forward, positive steering = turn right.
     */
    fun drive(throttle: Int, steering: Int) {
        val t = throttle.coerceIn(-100, 100)
        val s = steering.coerceIn(-100, 100)
        val left  = (t + s).coerceIn(-100, 100)
        val right = (t - s).coerceIn(-100, 100)
        setThrottle(1, left)
        setThrottle(2, right)
    }

    // -------------------------------------------------------------------------
    // RGB LED
    // -------------------------------------------------------------------------

    /**
     * Sets the RGB LED color.  Values outside 0–255 are clamped.
     * Sends "C:r,g,b\n".
     */
    fun setColor(r: Int, g: Int, b: Int) {
        sendCommand("C:${r.coerceIn(0, 255)},${g.coerceIn(0, 255)},${b.coerceIn(0, 255)}")
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /**
     * Sends a ping and blocks until a response is received (or times out).
     *
     * @return Parsed [Status] on success, or null on timeout / parse error.
     */
    fun ping(): Status? {
        sendCommand("?")
        val raw = readLine() ?: return null
        return Status.parse(raw)
    }

    /**
     * Snapshot of the controller's reported state, as returned by ping().
     *
     * @param armed     true if the controller has been armed.
     * @param throttle  per-channel throttle values (index 0 = channel 1).
     */
    data class Status(
        val armed: Boolean,
        val throttle: IntArray,
    ) {
        companion object {
            /** Parses "OK:<armed>:<t1>:<t2>:<t3>" */
            fun parse(line: String): Status? {
                if (!line.startsWith("OK:")) return null
                val parts = line.substring(3).split(":")
                if (parts.size < 4) return null
                return try {
                    Status(
                        armed    = parts[0] == "1",
                        throttle = intArrayOf(parts[1].toInt(), parts[2].toInt(), parts[3].toInt()),
                    )
                } catch (_: NumberFormatException) {
                    null
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun sendCommand(command: String) {
        lock.withLock {
            try {
                port?.write("$command\n".toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
            } catch (_: Exception) {
                // Swallow; callers can detect loss of connection via ping() or isConnected.
            }
        }
    }

    /** Reads one newline-terminated line from the serial port, or returns null on timeout. */
    private fun readLine(): String? {
        val buf = ByteArray(READ_BUFFER_SIZE)
        val sb  = StringBuilder()
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        lock.withLock {
            val p = port ?: return null
            while (System.currentTimeMillis() < deadline) {
                try {
                    val n = p.read(buf, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1))
                    if (n > 0) {
                        val chunk = String(buf, 0, n, Charsets.US_ASCII)
                        sb.append(chunk)
                        if (sb.contains('\n')) return sb.toString().substringBefore('\n').trim()
                    }
                } catch (_: Exception) {
                    return null
                }
            }
        }
        return null
    }
}
