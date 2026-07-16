package com.resourcefork.rccontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the wire-protocol parsing logic in [MotorController.Status].
 * These run on the JVM without needing an Android device.
 */
class MotorControllerStatusTest {

    @Test
    fun `parse valid armed status`() {
        val status = MotorController.Status.parse("OK:1:50:25:0")
        assertNotNull(status)
        assertTrue(status!!.armed)
        assertEquals(50, status.throttle[0])
        assertEquals(25, status.throttle[1])
        assertEquals(0,  status.throttle[2])
    }

    @Test
    fun `parse valid disarmed status`() {
        val status = MotorController.Status.parse("OK:0:0:0:0")
        assertNotNull(status)
        assertFalse(status!!.armed)
        assertEquals(0, status.throttle[0])
        assertEquals(0, status.throttle[1])
        assertEquals(0, status.throttle[2])
    }

    @Test
    fun `parse with negative throttle values`() {
        val status = MotorController.Status.parse("OK:1:-100:-50:75")
        assertNotNull(status)
        assertTrue(status!!.armed)
        assertEquals(-100, status.throttle[0])
        assertEquals(-50,  status.throttle[1])
        assertEquals(75,   status.throttle[2])
    }

    @Test
    fun `parse returns null for unknown prefix`() {
        assertNull(MotorController.Status.parse("ERR:NOT_ARMED"))
        assertNull(MotorController.Status.parse("ARMED"))
        assertNull(MotorController.Status.parse(""))
    }

    @Test
    fun `parse returns null for malformed OK line`() {
        assertNull(MotorController.Status.parse("OK:1:50"))         // too few fields
        assertNull(MotorController.Status.parse("OK:1:abc:25:0"))   // non-numeric
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}
