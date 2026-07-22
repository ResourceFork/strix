package com.resourcefork.rccontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A software-only implementation of [IMotorController] for development and testing without physical
 * hardware.
 *
 * All commands are captured and reflected in [mockState] so the UI can render a visual "virtual
 * device" showing exactly what would be sent to the Arduino.
 */
class MockMotorController : IMotorController {

    // -------------------------------------------------------------------------
    // Observed mock state
    // -------------------------------------------------------------------------

    /**
     * Snapshot of the mock controller's current state.
     *
     * @param connected Whether the mock is "connected".
     * @param armed Whether the mock has been armed.
     * @param throttle Per-channel throttle values (index 0 = channel 1, 1 = channel 2).
     * @param lastCommand Human-readable description of the last command sent.
     */
    data class MockState(
        val connected: Boolean = false,
        val armed: Boolean = false,
        val throttle: IntArray = intArrayOf(0, 0),
        val lastCommand: String = "",
    ) {
        // Manual equals/hashCode because IntArray uses reference equality by default.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MockState) return false
            return connected == other.connected &&
                armed == other.armed &&
                throttle.contentEquals(other.throttle) &&
                lastCommand == other.lastCommand
        }

        override fun hashCode(): Int {
            var result = connected.hashCode()
            result = 31 * result + armed.hashCode()
            result = 31 * result + throttle.contentHashCode()
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
        _mockState.value =
            _mockState.value.copy(armed = false, throttle = intArrayOf(0, 0), lastCommand = "A:0")
    }

    override fun setThrottle(channel: Int, value: Int) {
        require(channel in 1..2) { "channel must be 1–2, got $channel" }
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
        // Car-style chassis: channel 1 = drive ESC (thrust), channel 2 = steering servo.
        val newThrottle = intArrayOf(t, s)
        _mockState.value =
            _mockState.value.copy(throttle = newThrottle, lastCommand = "T1:$t T2:$s")
    }

    override fun ping(): ControllerStatus? {
        val s = _mockState.value
        if (!s.connected) return null
        return ControllerStatus(armed = s.armed, throttle = s.throttle.copyOf())
    }

    override fun readDistances(): DistanceReport? {
        if (!_mockState.value.connected) return null
        // Slowly-varying synthetic readings so the UI visibly updates in mock mode.
        val phase = (System.currentTimeMillis() / 400L).toDouble()
        fun wave(base: Int, amp: Int, offset: Double): Int =
            (base + amp * kotlin.math.sin(phase / 5.0 + offset)).toInt()
        return DistanceReport(
            centerMm = wave(400, 250, 0.0),
            frontLeftMm = wave(700, 400, 1.5),
            frontRightMm = wave(1000, 600, 3.0),
        )
    }
}
