package com.resourcefork.rccontrol

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    // Motor controllers & Worker
    // -------------------------------------------------------------------------

    private val realController = MotorController(application)

    /** Mock controller – always instantiated so the UI can observe [MockMotorController.mockState]. */
    val mockController = MockMotorController()

    private val activeController: IMotorController
        get() = if (_uiState.value.isMockMode) mockController else realController

    private sealed class Command {
        data class Drive(val throttle: Int, val steering: Int) : Command()
        data class SetThrottle(val channel: Int, val value: Int) : Command()
        data class SetColor(val r: Int, val g: Int, val b: Int) : Command()
        object Arm : Command()
        object Disarm : Command()
    }

    private val commandChannel = Channel<Command>(Channel.CONFLATED)

    init {
        // Single background worker to process commands one at a time.
        // Using CONFLATED channel means if the controller is slow, we only
        // process the most recent drive/LED command and skip stale ones.
        viewModelScope.launch(Dispatchers.IO) {
            commandChannel.receiveAsFlow().collect { cmd ->
                when (cmd) {
                    is Command.Drive       -> activeController.drive(cmd.throttle, cmd.steering)
                    is Command.SetThrottle -> activeController.setThrottle(cmd.channel, cmd.value)
                    is Command.SetColor    -> activeController.setColor(cmd.r, cmd.g, cmd.b)
                    is Command.Arm         -> activeController.arm()
                    is Command.Disarm      -> activeController.disarm()
                }
            }
        }
    }

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
            commandChannel.send(Command.Disarm)
            activeController.disconnect()
            _uiState.update { it.copy(isConnected = false, isArmed = false) }
        }
    }

    // -------------------------------------------------------------------------
    // Arm / disarm
    // -------------------------------------------------------------------------

    fun arm() {
        commandChannel.trySend(Command.Arm)
        _uiState.update { it.copy(isArmed = true) }
    }

    fun disarm() {
        commandChannel.trySend(Command.Disarm)
        _uiState.update { it.copy(isArmed = false) }
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
        commandChannel.trySend(Command.Drive(throttle, steering))

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
        commandChannel.trySend(Command.SetThrottle(channel, clamped))

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
        commandChannel.trySend(Command.SetColor(r, g, b))

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
                // Use a local string builder to avoid repeated StateFlow updates
                // causing quadratic string allocation and UI lag.
                val fullText = StringBuilder()
                client.analyzeFrame(frameJpeg, prompt) { token ->
                    fullText.append(token)
                    _uiState.update { it.copy(vlmOutput = fullText.toString()) }
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
        commandChannel.close()
        realController.disarm()
        realController.disconnect()
    }
}
