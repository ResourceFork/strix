package com.resourcefork.rccontrol

/**
 * Abstraction over the motor controller, allowing either a real USB-serial
 * [MotorController] or a [MockMotorController] to be swapped in at runtime.
 */
interface IMotorController {

    /** true if the controller is currently connected / ready to receive commands. */
    val isConnected: Boolean

    /**
     * Opens the connection (USB port for real hardware; instant for mock).
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
     * @param channel 1–3.
     * @param value   -100…100.
     */
    fun setThrottle(channel: Int, value: Int)

    /**
     * Tank-drive helper: mixes [throttle] and [steering] into channel 1 (left)
     * and channel 2 (right).
     */
    fun drive(throttle: Int, steering: Int)

    /**
     * Sets the RGB LED colour.
     * @param r red   0–255.
     * @param g green 0–255.
     * @param b blue  0–255.
     */
    fun setColor(r: Int, g: Int, b: Int)

    /**
     * Pings the controller and returns its current status, or null on
     * timeout / not connected.
     */
    fun ping(): MotorController.Status?
}
