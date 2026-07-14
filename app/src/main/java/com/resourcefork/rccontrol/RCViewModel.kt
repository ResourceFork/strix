package com.resourcefork.rccontrol

import android.app.Application
import android.content.Context
import android.util.Log
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
 * Holds all observable state for the RC-car controller UI and mediates between the Compose layer
 * and the [IMotorController] / [VlmClient].
 */
class RCViewModel(application: Application) : AndroidViewModel(application) {

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    data class UiState(
        val isConnected: Boolean = false,
        val isArmed: Boolean = false,
        val throttle: IntArray = intArrayOf(0, 0, 0), // channels 1-3
        val ledColor: Color = Color.Black,
        val vlmOutput: String = "",
        val vlmRunning: Boolean = false,
        val detections: List<Detection> = emptyList(),
        val useOnDeviceVlm: Boolean = true,
        val onDeviceModelAvailable: Boolean = false,
        val onDeviceModelName: String? = null,
        val onDeviceWarming: Boolean = false,
        val onDeviceReady: Boolean = false,
        val downloadInProgress: Boolean = false,
        val downloadProgress: Float? = null, // 0f..1f, or null when total size unknown
        val downloadingModelName: String? = null,
        val hfToken: String = "",
        val cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL,
        val cloudModel: String = DEFAULT_CLOUD_MODEL,
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

    /**
     * Mock controller – always instantiated so the UI can observe [MockMotorController.mockState].
     */
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

    /** Small persistent store for the (remembered) Hugging Face token. */
    private val prefs = application.getSharedPreferences("strix_prefs", Context.MODE_PRIVATE)

    init {
        // Single background worker to process commands one at a time.
        // Using CONFLATED channel means if the controller is slow, we only
        // process the most recent drive/LED command and skip stale ones.
        viewModelScope.launch(Dispatchers.IO) {
            commandChannel.receiveAsFlow().collect { cmd ->
                when (cmd) {
                    is Command.Drive -> activeController.drive(cmd.throttle, cmd.steering)
                    is Command.SetThrottle -> activeController.setThrottle(cmd.channel, cmd.value)
                    is Command.SetColor -> activeController.setColor(cmd.r, cmd.g, cmd.b)
                    is Command.Arm -> activeController.arm()
                    is Command.Disarm -> activeController.disarm()
                }
            }
        }

        // Restore the remembered Hugging Face token so the download field is pre-filled.
        // Sanitize on the way in: earlier versions may have persisted pasted whitespace.
        val storedToken =
            prefs.getString(KEY_HF_TOKEN, "").orEmpty().filterNot { it.isWhitespace() }
        _uiState.update {
            it.copy(
                hfToken = storedToken,
                cloudBaseUrl =
                    prefs.getString(KEY_CLOUD_BASE_URL, DEFAULT_CLOUD_BASE_URL).orEmpty(),
                cloudModel = prefs.getString(KEY_CLOUD_MODEL, DEFAULT_CLOUD_MODEL).orEmpty(),
            )
        }

        // Detect whether an on-device VLM model has already been pushed to the device.
        refreshModelAvailability()
    }

    /**
     * Persists the Hugging Face token so it survives app restarts. Whitespace is stripped because
     * pasted tokens frequently carry a trailing newline/space, which corrupts the Authorization
     * header and reads as "invalid token" (HF tokens never contain whitespace).
     */
    fun setHfToken(token: String) {
        val clean = token.filterNot { it.isWhitespace() }
        prefs.edit().putString(KEY_HF_TOKEN, clean).apply()
        _uiState.update { it.copy(hfToken = clean) }
    }

    /** Persists the remote VLM server base URL (e.g. http://100.x.y.z:11434/v1 for Ollama). */
    fun setCloudBaseUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_CLOUD_BASE_URL, clean).apply()
        _uiState.update { it.copy(cloudBaseUrl = clean) }
    }

    /** Persists the remote VLM model name (e.g. qwen3-vl:8b for Ollama, gpt-4o for OpenAI). */
    fun setCloudModel(model: String) {
        val clean = model.trim()
        prefs.edit().putString(KEY_CLOUD_MODEL, clean).apply()
        _uiState.update { it.copy(cloudModel = clean) }
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /** Connect to the Arduino. Must be called after USB permission is granted. */
    fun connect() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = activeController.connect()
            _uiState.update {
                it.copy(isConnected = ok, errorMessage = if (ok) null else "USB connection failed")
            }
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
     * Called by the virtual joystick with normalised axes (-1f … 1f). Mixes into left / right motor
     * channels and sends to the controller.
     */
    fun onJoystickInput(xAxis: Float, yAxis: Float) {
        val throttle = (yAxis * 100).toInt().coerceIn(-100, 100)
        val steering = (xAxis * 100).toInt().coerceIn(-100, 100)
        commandChannel.trySend(Command.Drive(throttle, steering))

        val left = (throttle + steering).coerceIn(-100, 100)
        val right = (throttle - steering).coerceIn(-100, 100)
        _uiState.update { s -> s.copy(throttle = intArrayOf(left, right, s.throttle[2])) }
    }

    /** Sets an individual channel throttle directly (e.g. channel 3 for an extra servo). */
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
        val r = (color.red * 255).toInt()
        val g = (color.green * 255).toInt()
        val b = (color.blue * 255).toInt()
        commandChannel.trySend(Command.SetColor(r, g, b))

        _uiState.update { it.copy(ledColor = color) }
    }

    // -------------------------------------------------------------------------
    // Mock mode
    // -------------------------------------------------------------------------

    /**
     * Toggles between the real USB controller and the [MockMotorController]. If currently
     * connected, the active controller is disarmed and disconnected before the mode switches.
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

    /** Persistent on-device engine (heavyweight – created lazily, reused, closed in onCleared). */
    private var localClient: LocalVlmClient? = null

    /** Switches between the on-device (offline) and cloud VLM backends. */
    fun setUseOnDeviceVlm(enabled: Boolean) {
        _uiState.update { it.copy(useOnDeviceVlm = enabled) }
        refreshModelAvailability()
    }

    /**
     * Re-checks whether an on-device model file is present on the device, records its name, and
     * (when on-device mode is active) kicks off a background warm-up so the first inference is
     * fast. Safe to call repeatedly, e.g. from a "Re-scan" button after adb-pushing a model.
     */
    fun refreshModelAvailability() {
        val path = LocalModelLocator.resolve(getApplication())
        _uiState.update {
            it.copy(
                onDeviceModelAvailable = path != null,
                onDeviceModelName = path?.substringAfterLast('/'),
                onDeviceReady = if (path == null) false else it.onDeviceReady,
            )
        }
        if (path != null && _uiState.value.useOnDeviceVlm) {
            warmUpOnDeviceModel(path)
        }
    }

    /**
     * Loads the on-device engine into memory ahead of time on a background thread. Idempotent: does
     * nothing if the engine for [path] is already loaded or a warm-up is in flight.
     */
    private fun warmUpOnDeviceModel(path: String) {
        val current = localClient
        if (current != null && current.modelPath == path && current.isReady) {
            _uiState.update { it.copy(onDeviceReady = true) }
            return
        }
        if (_uiState.value.onDeviceWarming) return
        _uiState.update { it.copy(onDeviceWarming = true, onDeviceReady = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client =
                    current?.takeIf { it.modelPath == path }
                        ?: run {
                            current?.close()
                            LocalVlmClient(getApplication(), path).also { localClient = it }
                        }
                client.prepare()
                _uiState.update { it.copy(onDeviceReady = true) }
            } catch (e: Exception) {
                Log.e(TAG, "On-device model load failed", e)
                _uiState.update { it.copy(errorMessage = "Model load failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(onDeviceWarming = false) }
            }
        }
    }

    /**
     * User-initiated re-scan for a model file (e.g. after an adb push). Unlike the silent
     * [refreshModelAvailability] used on init/toggle, this surfaces a message when nothing is found
     * so the "Re-scan" button always gives feedback.
     */
    fun rescanForModel() {
        refreshModelAvailability()
        val s = _uiState.value
        if (!s.onDeviceModelAvailable && !s.downloadInProgress) {
            _uiState.update {
                it.copy(
                    errorMessage =
                        "No model found in the app's storage yet. Download one below, or push a " +
                            ".task file and re-scan."
                )
            }
        }
    }

    /**
     * Downloads [model] directly into the app's own model directory — no adb, no desktop. For gated
     * Gemma models you must accept the license once on huggingface.co and pass a read [hfToken]. On
     * success, availability is refreshed and warm-up starts automatically.
     */
    fun downloadModel(model: DownloadableModel, hfToken: String) {
        if (_uiState.value.downloadInProgress) return
        _uiState.update {
            it.copy(
                downloadInProgress = true,
                downloadProgress = 0f,
                downloadingModelName = model.name,
                errorMessage = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ModelDownloader(getApplication()).download(model, hfToken.ifBlank { null }) {
                    read,
                    total ->
                    val p = if (total > 0L) (read.toFloat() / total).coerceIn(0f, 1f) else null
                    _uiState.update { it.copy(downloadProgress = p) }
                }
                refreshModelAvailability()
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed (${model.id})", e)
                _uiState.update { it.copy(errorMessage = "Download failed: ${e.message}") }
            } finally {
                _uiState.update {
                    it.copy(
                        downloadInProgress = false,
                        downloadProgress = null,
                        downloadingModelName = null,
                    )
                }
            }
        }
    }

    /**
     * Picks the analyzer for the current mode. Returns null (and surfaces an error) when the
     * on-device model is missing or a cloud API key is required but absent. Called from a
     * background coroutine because building the on-device engine is expensive.
     */
    private fun resolveAnalyzer(useOnDevice: Boolean, apiKey: String): VisionAnalyzer? {
        if (useOnDevice) {
            val path = LocalModelLocator.resolve(getApplication())
            if (path == null) {
                _uiState.update {
                    it.copy(
                        errorMessage =
                            "No on-device model found. Run scripts/download-vlm-model.sh and push " +
                                "the .task file to the device."
                    )
                }
                return null
            }
            val existing = localClient
            if (existing != null && existing.modelPath == path) return existing
            existing?.close()
            return LocalVlmClient(getApplication(), path).also { localClient = it }
        }

        // Remote server mode. The API key is optional: local servers such as Ollama or
        // llama.cpp don't require one (an auth-checking reverse proxy will, though).
        val s = _uiState.value
        val baseUrl = s.cloudBaseUrl.ifBlank { DEFAULT_CLOUD_BASE_URL }
        val model = s.cloudModel.ifBlank { DEFAULT_CLOUD_MODEL }
        return VlmClient(apiKey = apiKey.trim(), baseUrl = baseUrl, model = model)
    }

    /**
     * Sends [frameJpeg] to the selected VLM backend (on-device when [useOnDevice], else cloud with
     * [apiKey]) and appends the response text to [UiState.vlmOutput].
     */
    fun analyzeFrame(useOnDevice: Boolean, apiKey: String, frameJpeg: ByteArray, prompt: String) {
        if (_uiState.value.vlmRunning) return
        _uiState.update { it.copy(vlmRunning = true, vlmOutput = "", detections = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val analyzer = resolveAnalyzer(useOnDevice, apiKey) ?: return@launch
                // Use a local string builder to avoid repeated StateFlow updates
                // causing quadratic string allocation and UI lag.
                val fullText = StringBuilder()
                analyzer.analyzeFrame(frameJpeg, prompt) { token ->
                    fullText.append(token)
                    _uiState.update { it.copy(vlmOutput = fullText.toString()) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "VLM analyzeFrame failed", e)
                _uiState.update { it.copy(errorMessage = "VLM error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(vlmRunning = false) }
            }
        }
    }

    /**
     * Sends [frameJpeg] to the selected VLM backend for object localization and streams the
     * resulting [Detection]s into [UiState.detections] so the camera overlay draws annotated boxes.
     * A short text summary of the detected labels is also mirrored into [UiState.vlmOutput].
     */
    fun detectObjects(useOnDevice: Boolean, apiKey: String, frameJpeg: ByteArray, prompt: String) {
        if (_uiState.value.vlmRunning) return
        _uiState.update { it.copy(vlmRunning = true, vlmOutput = "", detections = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val analyzer = resolveAnalyzer(useOnDevice, apiKey) ?: return@launch
                val collected = mutableListOf<Detection>()
                analyzer.detectObjects(frameJpeg, prompt) { detection ->
                    collected.add(detection)
                    val snapshot = collected.toList()
                    _uiState.update {
                        it.copy(
                            detections = snapshot,
                            vlmOutput =
                                snapshot.joinToString("\n") { d ->
                                    val pct =
                                        d.confidence?.let { c -> " ${(c * 100).toInt()}%" } ?: ""
                                    "• ${d.label}$pct"
                                },
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "VLM detectObjects failed", e)
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
        localClient?.close()
    }

    private companion object {
        const val KEY_HF_TOKEN = "hf_token"
        const val KEY_CLOUD_BASE_URL = "cloud_base_url"
        const val KEY_CLOUD_MODEL = "cloud_model"
        const val DEFAULT_CLOUD_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_CLOUD_MODEL = "gpt-4o"
        const val TAG = "RCViewModel"
    }
}
