package com.resourcefork.rccontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A software-only implementation of [IMotorController] for development and
 * testing without physical hardware.
 *
 * All commands are captured and reflected in [mockState] so the UI can render
 * a visual "virtual device" showing exactly what would be sent to the Arduino.
 */
class MockMotorController : IMotorController {

    // -------------------------------------------------------------------------
    // Observed mock state
    // -------------------------------------------------------------------------

    /**
     * Snapshot of the mock controller's current state.
     *
     * @param connected     Whether the mock is "connected".
     * @param armed         Whether the mock has been armed.
     * @param throttle      Per-channel throttle values (index 0 = channel 1).
     * @param ledR/G/B      Current LED color components (0–255).
     * @param lastCommand   Human-readable description of the last command sent.
     */
    data class MockState(
        val connected: Boolean = false,
        val armed: Boolean = false,
        val throttle: IntArray = intArrayOf(0, 0, 0),
        val ledR: Int = 0,
        val ledG: Int = 0,
        val ledB: Int = 0,
        val lastCommand: String = "",
    ) {
        // Manual equals/hashCode because IntArray uses reference equality by default.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MockState) return false
            return connected == other.connected &&
                armed == other.armed &&
                throttle.contentEquals(other.throttle) &&
                ledR == other.ledR &&
                ledG == other.ledG &&
                ledB == other.ledB &&
                lastCommand == other.lastCommand
        }

        override fun hashCode(): Int {
            var result = connected.hashCode()
            result = 31 * result + armed.hashCode()
            result = 31 * result + throttle.contentHashCode()
            result = 31 * result + ledR
            result = 31 * result + ledG
            result = 31 * result + ledB
            result = 31 * result + lastCommand.hashCode()
            return result
        }
    }

    private val _mockState = MutableStateFlow(MockState())

    /** Observable state emitted whenever a command is processed. */
    val mockState: StateFlow<MockState> = _mockState.asStateFlow()

    // -------------------------------------------------------------------------
    // IMotorController implementation
    // -------------------------------------------------------------------------

    override val isConnected: Boolean
        get() = _mockState.value.connected

    override fun connect(): Boolean {
        _mockState.value = _mockState.value.copy(connected = true, lastCommand = "connect()")
        return true
    }

    override fun disconnect() {
        _mockState.value = MockState()
    }

    override fun arm() {
        if (!_mockState.value.connected) return
        _mockState.value = _mockState.value.copy(armed = true, lastCommand = "A:1")
    }

    override fun disarm() {
        if (!_mockState.value.connected) return
        _mockState.value = _mockState.value.copy(
            armed = false,
            throttle = intArrayOf(0, 0, 0),
            ledR = 0,
            ledG = 0,
            ledB = 0,
            lastCommand = "A:0",
        )
    }

    override fun setThrottle(channel: Int, value: Int) {
        require(channel in 1..3) { "channel must be 1–3, got $channel" }
        if (!_mockState.value.connected) return
        val clamped = value.coerceIn(-100, 100)
        val t = _mockState.value.throttle.copyOf()
        t[channel - 1] = clamped
        _mockState.value = _mockState.value.copy(throttle = t, lastCommand = "T$channel:$clamped")
    }

    override fun drive(throttle: Int, steering: Int) {
        if (!_mockState.value.connected) return
        val t = throttle.coerceIn(-100, 100)
        val s = steering.coerceIn(-100, 100)
        val left  = (t + s).coerceIn(-100, 100)
        val right = (t - s).coerceIn(-100, 100)
        val newThrottle = intArrayOf(left, right, _mockState.value.throttle[2])
        _mockState.value = _mockState.value.copy(
            throttle = newThrottle,
            lastCommand = "T1:$left T2:$right",
        )
    }

    override fun setColor(r: Int, g: Int, b: Int) {
        if (!_mockState.value.connected) return
        val clampedR = r.coerceIn(0, 255)
        val clampedG = g.coerceIn(0, 255)
        val clampedB = b.coerceIn(0, 255)
        _mockState.value = _mockState.value.copy(
            ledR = clampedR,
            ledG = clampedG,
            ledB = clampedB,
            lastCommand = "C:$clampedR,$clampedG,$clampedB",
        )
    }

    override fun ping(): ControllerStatus? {
        val s = _mockState.value
        if (!s.connected) return null
        return ControllerStatus(armed = s.armed, throttle = s.throttle.copyOf())
    }
}
