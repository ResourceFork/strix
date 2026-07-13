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
 * the Compose layer and the [IMotorController] / [VlmClient].
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
        val isMockMode: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // -------------------------------------------------------------------------
    // Motor controllers
    // -------------------------------------------------------------------------

    private val realController = MotorController(application)

    /** Mock controller – always instantiated so the UI can observe [MockMotorController.mockState]. */
    val mockController = MockMotorController()

    private val activeController: IMotorController
        get() = if (_uiState.value.isMockMode) mockController else realController

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /** Connect to the Arduino.  Must be called after USB permission is granted. */
    fun connect() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = activeController.connect()
            _uiState.update { it.copy(isConnected = ok, errorMessage = if (ok) null else "USB connection failed") }
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            activeController.disarm()
            activeController.disconnect()
            _uiState.update { it.copy(isConnected = false, isArmed = false) }
        }
    }

    // -------------------------------------------------------------------------
    // Arm / disarm
    // -------------------------------------------------------------------------

    fun arm() {
        viewModelScope.launch(Dispatchers.IO) {
            activeController.arm()
            _uiState.update { it.copy(isArmed = true) }
        }
    }

    fun disarm() {
        viewModelScope.launch(Dispatchers.IO) {
            activeController.disarm()
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
            activeController.drive(throttle, steering)
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
        if (channel !in 1..3) return
        val clamped = value.coerceIn(-100, 100)
        viewModelScope.launch(Dispatchers.IO) {
            activeController.setThrottle(channel, clamped)
        }
        _uiState.update { s ->
            val t = s.throttle.copyOf()
            t[channel - 1] = clamped
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
            activeController.setColor(r, g, b)
        }
        _uiState.update { it.copy(ledColor = color) }
    }

    // -------------------------------------------------------------------------
    // Mock mode
    // -------------------------------------------------------------------------

    /**
     * Toggles between the real USB controller and the [MockMotorController].
     * If currently connected, the active controller is disarmed and disconnected
     * before the mode switches.
     */
    fun toggleMockMode() {
        val current = _uiState.value
        val newMode = !current.isMockMode
        viewModelScope.launch(Dispatchers.IO) {
            if (current.isConnected) {
                activeController.disarm()
                activeController.disconnect()
            }
            _uiState.update { it.copy(isMockMode = newMode, isConnected = false, isArmed = false) }
        }
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
        activeController.disarm()
        activeController.disconnect()
        // Always clean up the real controller as well in case mock mode was active.
        if (_uiState.value.isMockMode) {
            realController.disarm()
            realController.disconnect()
        }
    }
}
