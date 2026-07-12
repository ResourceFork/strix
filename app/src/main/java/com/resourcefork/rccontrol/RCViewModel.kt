package com.resourcefork.rccontrol

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds all observable state for the RC-car controller UI and mediates between
 * the Compose layer and the [MotorController] / [VlmClient].
 */
class RCViewModel(application: Application) : AndroidViewModel(application) {

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    data class UiState(
        val isConnected: Boolean = false,
        val isArmed: Boolean = false,
        val throttle: IntArray = intArrayOf(0, 0, 0),   // channels 1-3
        val ledColor: Color = Color.Black,
        val vlmOutput: String = "",
        val vlmRunning: Boolean = false,
        val cameraPermissionGranted: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // -------------------------------------------------------------------------
    // Motor controller
    // -------------------------------------------------------------------------

    val motorController = MotorController(application)

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /** Connect to the Arduino.  Must be called after USB permission is granted. */
    fun connect() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = motorController.connect()
            _uiState.update { it.copy(isConnected = ok, errorMessage = if (ok) null else "USB connection failed") }
        }
    }

    fun disconnect() {
        motorController.disarm()
        motorController.disconnect()
        _uiState.update { it.copy(isConnected = false, isArmed = false) }
    }

    // -------------------------------------------------------------------------
    // Arm / disarm
    // -------------------------------------------------------------------------

    fun arm() {
        viewModelScope.launch(Dispatchers.IO) {
            motorController.arm()
            _uiState.update { it.copy(isArmed = true) }
        }
    }

    fun disarm() {
        viewModelScope.launch(Dispatchers.IO) {
            motorController.disarm()
            _uiState.update { it.copy(isArmed = false) }
        }
    }

    // -------------------------------------------------------------------------
    // Driving – differential drive via joystick input
    // -------------------------------------------------------------------------

    /**
     * Called by the virtual joystick with normalised axes (-1f … 1f).
     * Mixes into left / right motor channels and sends to the controller.
     */
    fun onJoystickInput(xAxis: Float, yAxis: Float) {
        val throttle = (yAxis * 100).toInt().coerceIn(-100, 100)
        val steering = (xAxis * 100).toInt().coerceIn(-100, 100)
        viewModelScope.launch(Dispatchers.IO) {
            motorController.drive(throttle, steering)
        }
        val left  = (throttle + steering).coerceIn(-100, 100)
        val right = (throttle - steering).coerceIn(-100, 100)
        _uiState.update { s ->
            s.copy(throttle = intArrayOf(left, right, s.throttle[2]))
        }
    }

    /**
     * Sets an individual channel throttle directly (e.g. channel 3 for an extra servo).
     */
    fun setChannelThrottle(channel: Int, value: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            motorController.setThrottle(channel, value)
        }
        _uiState.update { s ->
            val t = s.throttle.copyOf()
            t[channel - 1] = value.coerceIn(-100, 100)
            s.copy(throttle = t)
        }
    }

    // -------------------------------------------------------------------------
    // LED
    // -------------------------------------------------------------------------

    fun setLedColor(color: Color) {
        val r = (color.red   * 255).toInt()
        val g = (color.green * 255).toInt()
        val b = (color.blue  * 255).toInt()
        viewModelScope.launch(Dispatchers.IO) {
            motorController.setColor(r, g, b)
        }
        _uiState.update { it.copy(ledColor = color) }
    }

    // -------------------------------------------------------------------------
    // VLM
    // -------------------------------------------------------------------------

    /**
     * Sends [frameJpeg] to the VLM with the given [prompt].
     * Streaming tokens are appended to [UiState.vlmOutput] in real-time.
     */
    fun analyzeFrame(
        client: VlmClient,
        frameJpeg: ByteArray,
        prompt: String,
    ) {
        if (_uiState.value.vlmRunning) return
        _uiState.update { it.copy(vlmRunning = true, vlmOutput = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.analyzeFrame(frameJpeg, prompt) { token ->
                    _uiState.update { it.copy(vlmOutput = it.vlmOutput + token) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "VLM error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(vlmRunning = false) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Camera permission
    // -------------------------------------------------------------------------

    fun onCameraPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(cameraPermissionGranted = granted) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        motorController.disarm()
        motorController.disconnect()
    }
}
