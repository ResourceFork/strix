package com.resourcefork.rccontrol

data class ControllerStatus(val armed: Boolean, val throttle: IntArray)

/**
 * Abstraction over the motor controller, allowing either a real USB-serial [MotorController] or a
 * [MockMotorController] to be swapped in at runtime.
 */
interface IMotorController {

    /** true if the controller is currently connected / ready to receive commands. */
    val isConnected: Boolean

    /**
     * Opens the connection (USB port for real hardware; instant for mock).
     *
     * @return true on success.
     */
    fun connect(): Boolean

    /** Closes the connection and releases any held resources. */
    fun disconnect()

    /** Arms the controller — required before throttle commands take effect. */
    fun arm()

    /** Disarms the controller — all channels go to neutral. */
    fun disarm()

    /**
     * Sets a servo / ESC channel to [value].
     *
     * @param channel 1 (drive ESC) or 2 (steering servo).
     * @param value -100…100.
     */
    fun setThrottle(channel: Int, value: Int)

    /**
     * Car-style drive helper for a steer + powered-drive chassis: sends [throttle] (thrust, -100
     * full reverse … 100 full forward) to channel 1 (drive ESC) and [steering] (-100 full left …
     * 100 full right) to channel 2 (steering servo).
     */
    fun drive(throttle: Int, steering: Int)

    /** Pings the controller and returns its current status, or null on timeout / not connected. */
    fun ping(): ControllerStatus?
}
