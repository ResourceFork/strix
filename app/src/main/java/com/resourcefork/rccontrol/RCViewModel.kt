package com.resourcefork.rccontrol

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
        val throttle: IntArray = intArrayOf(0, 0), // channel 1 (thrust), channel 2 (steer)
        val vlmOutput: String = "",
        val vlmRunning: Boolean = false,
        val detections: List<Detection> = emptyList(),
        val lastDriveCommand: DriveCommand? = null,
        val continuousMode: Boolean = false, // UI toggle: loop the next VLM op until turned off
        val continuousActive: Boolean = false, // a continuous loop is currently running
        val corridors: CorridorReport? = null, // latest depth reflex-layer reading
        val depthModelName: String? = null, // null = no depth model, reflex layer inactive
        val reflexDriveEnabled: Boolean = false, // depth-only reactive autopilot on/off
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
        val cloudApiKey: String = "",
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

        object Arm : Command()

        object Disarm : Command()
    }

    private val commandChannel = Channel<Command>(Channel.CONFLATED)

    /** Small persistent store for the (remembered) Hugging Face token. */
    private val prefs = application.getSharedPreferences("strix_prefs", Context.MODE_PRIVATE)

    init {
        // Single background worker to process commands one at a time.
        // Using CONFLATED channel means if the controller is slow, we only
        // process the most recent drive command and skip stale ones.
        viewModelScope.launch(Dispatchers.IO) {
            commandChannel.receiveAsFlow().collect { cmd ->
                when (cmd) {
                    is Command.Drive -> activeController.drive(cmd.throttle, cmd.steering)
                    is Command.Arm -> activeController.arm()
                    is Command.Disarm -> activeController.disarm()
                }
            }
        }

        // Restore remembered settings (tokens/keys sanitized: earlier versions may have
        // persisted pasted whitespace). The on-device/remote toggle must be restored BEFORE
        // refreshModelAvailability(), which uses it to decide whether to warm the model up.
        val storedToken =
            prefs.getString(KEY_HF_TOKEN, "").orEmpty().filterNot { it.isWhitespace() }
        val storedApiKey =
            prefs.getString(KEY_CLOUD_API_KEY, "").orEmpty().filterNot { it.isWhitespace() }
        _uiState.update {
            it.copy(
                hfToken = storedToken,
                cloudApiKey = storedApiKey,
                cloudBaseUrl =
                    prefs.getString(KEY_CLOUD_BASE_URL, DEFAULT_CLOUD_BASE_URL).orEmpty(),
                cloudModel = prefs.getString(KEY_CLOUD_MODEL, DEFAULT_CLOUD_MODEL).orEmpty(),
                useOnDeviceVlm = prefs.getBoolean(KEY_USE_ON_DEVICE_VLM, true),
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

    /**
     * Persists the remote VLM API key / bearer token so it survives app restarts. Whitespace is
     * stripped (keys never contain it; pasted trailing newlines corrupt the Authorization header).
     * Stored in app-private SharedPreferences.
     */
    fun setCloudApiKey(key: String) {
        val clean = key.filterNot { it.isWhitespace() }
        prefs.edit().putString(KEY_CLOUD_API_KEY, clean).apply()
        _uiState.update { it.copy(cloudApiKey = clean) }
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /** Connect to the Arduino. Must be called after USB permission is granted. */
    fun connect() {
        val controller = activeController
        viewModelScope.launch(Dispatchers.IO) {
            val ok = controller.connect()
            _uiState.update {
                it.copy(isConnected = ok, errorMessage = if (ok) null else "Connection failed")
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
    // Driving – thrust + steering via joystick input
    // -------------------------------------------------------------------------

    /**
     * Called by the virtual joystick with normalised axes (-1f … 1f). Y maps to thrust (channel 1,
     * drive ESC) and X to the steering servo (channel 2). No mixing – the chassis has separate
     * powered drive and steering.
     */
    fun onJoystickInput(xAxis: Float, yAxis: Float) {
        val throttle = (yAxis * 100).toInt().coerceIn(-100, 100)
        val steering = (xAxis * 100).toInt().coerceIn(-100, 100)
        commandChannel.trySend(Command.Drive(throttle, steering))

        _uiState.update { s -> s.copy(throttle = intArrayOf(throttle, steering)) }
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
        val controllerToDisconnect = if (current.isMockMode) mockController else realController
        viewModelScope.launch(Dispatchers.IO) {
            if (current.isConnected) {
                controllerToDisconnect.disarm()
                controllerToDisconnect.disconnect()
            }
            _uiState.update { it.copy(isMockMode = newMode, isConnected = false, isArmed = false) }
        }
    }

    // -------------------------------------------------------------------------
    // VLM
    // -------------------------------------------------------------------------

    /** Persistent on-device engine (heavyweight – created lazily, reused, closed in onCleared). */
    private var localClient: LocalVlmClient? = null

    /** Switches between the on-device (offline) and cloud VLM backends. Remembered across runs. */
    fun setUseOnDeviceVlm(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_ON_DEVICE_VLM, enabled).apply()
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
        val depthPath = DepthModelLocator.resolve(getApplication())
        depthModelPath = depthPath
        _uiState.update {
            it.copy(
                onDeviceModelAvailable = path != null,
                onDeviceModelName = path?.substringAfterLast('/'),
                onDeviceReady = if (path == null) false else it.onDeviceReady,
                depthModelName = depthPath?.substringAfterLast('/'),
                corridors = if (depthPath == null) null else it.corridors,
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
        if (DeviceInfo.isEmulator) {
            // The vision pipeline needs a real GPU/OpenCL driver, which emulators lack.
            // Skip the multi-GB load; an explicit inference attempt still fails gracefully.
            Log.i(TAG, "Emulator detected – skipping on-device model warm-up")
            return
        }
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
    private fun runAnalyze(analyzer: VisionAnalyzer, frameJpeg: ByteArray, prompt: String) {
        // Accumulate locally and throttle UI pushes: a fast server streams 40-80
        // tokens/sec, and a state update per token recomposes the whole screen at
        // that rate, visibly janking the UI. ~12 updates/sec looks identical.
        val fullText = StringBuilder()
        var lastUiPushMs = 0L
        analyzer.analyzeFrame(frameJpeg, prompt) { token ->
            fullText.append(token)
            val now = System.currentTimeMillis()
            if (now - lastUiPushMs >= 80) {
                lastUiPushMs = now
                _uiState.update { it.copy(vlmOutput = fullText.toString()) }
            }
        }
        // Final push so the complete response is always shown.
        _uiState.update { it.copy(vlmOutput = fullText.toString()) }
    }

    /**
     * Sends [frameJpeg] to the selected VLM backend for object localization and streams the
     * resulting [Detection]s into [UiState.detections] so the camera overlay draws annotated boxes.
     * A short text summary of the detected labels is also mirrored into [UiState.vlmOutput].
     */
    private fun runDetect(analyzer: VisionAnalyzer, frameJpeg: ByteArray, prompt: String) {
        val collected = mutableListOf<Detection>()
        val results =
            analyzer.detectObjects(frameJpeg, prompt) { detection ->
                collected.add(detection)
                val snapshot = collected.toList()
                _uiState.update {
                    it.copy(
                        detections = snapshot,
                        vlmOutput =
                            snapshot.joinToString("\n") { d ->
                                val pct = d.confidence?.let { c -> " ${(c * 100).toInt()}%" } ?: ""
                                "• ${d.label}$pct"
                            },
                    )
                }
            }
        Log.i(
            TAG,
            "detectObjects completed: ${results.size} detections" +
                (results.firstOrNull()?.let {
                    " (first: ${it.label} l=${it.left} t=${it.top} r=${it.right} b=${it.bottom})"
                } ?: ""),
        )
        if (results.isEmpty()) {
            _uiState.update {
                it.copy(vlmOutput = "No objects returned (see logcat for raw output).")
            }
        }
    }

    // -------------------------------------------------------------------------
    // VLM dispatch – single-shot or continuous
    // -------------------------------------------------------------------------

    /** VLM operations that can run single-shot or in a continuous capture→infer→display loop. */
    enum class VlmOp {
        ANALYZE,
        DETECT,
        PILOT,
    }

    /** In-flight continuous loop, or null when none is running. */
    private var continuousJob: Job? = null

    /** Runs [op] once against a single captured frame. */
    fun runVlm(
        op: VlmOp,
        useOnDevice: Boolean,
        apiKey: String,
        frameJpeg: ByteArray,
        prompt: String,
    ) {
        if (_uiState.value.vlmRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            runVlmOnce(op, useOnDevice, apiKey, frameJpeg, prompt)
        }
    }

    /** UI toggle for continuous mode. Turning it off stops any loop in progress. */
    fun setContinuousMode(enabled: Boolean) {
        _uiState.update { it.copy(continuousMode = enabled) }
        if (!enabled) {
            continuousJob?.cancel()
            continuousJob = null
        }
    }

    /**
     * Starts a capture→infer→display loop for [op]: grab the freshest frame from [frameProvider],
     * run one VLM round-trip, show the result, pause briefly, repeat. Runs until continuous mode is
     * toggled off (a blocking inference in flight finishes first) or no frame is available.
     *
     * With [VlmOp.PILOT] this *is* the autopilot loop – and its fail-safe: whenever the loop ends,
     * for any reason, a STOP is dispatched so the car is never left driving on a stale command.
     */
    fun startContinuous(
        op: VlmOp,
        useOnDevice: Boolean,
        apiKey: String,
        frameProvider: () -> ByteArray?,
        prompt: String,
    ) {
        if (continuousJob?.isActive == true || _uiState.value.vlmRunning) return
        continuousJob =
            viewModelScope.launch(Dispatchers.IO) {
                _uiState.update { it.copy(continuousActive = true) }
                try {
                    while (isActive && _uiState.value.continuousMode) {
                        val frame = frameProvider()
                        if (frame == null) {
                            _uiState.update { it.copy(errorMessage = "No camera frame available") }
                            break
                        }
                        runVlmOnce(op, useOnDevice, apiKey, frame, prompt)
                        delay(CONTINUOUS_PAUSE_MS)
                    }
                } finally {
                    if (op == VlmOp.PILOT) dispatchDriveCommand(DriveCommand(DriveAction.STOP))
                    _uiState.update { it.copy(continuousActive = false) }
                }
            }
    }

    /**
     * One VLM round-trip: resolve the backend, run [op], surface errors. Blocking – call on the IO
     * dispatcher. Shared by single-shot buttons and the continuous loop so behavior is identical in
     * both.
     */
    private fun runVlmOnce(
        op: VlmOp,
        useOnDevice: Boolean,
        apiKey: String,
        frameJpeg: ByteArray,
        prompt: String,
    ) {
        _uiState.update { it.copy(vlmRunning = true, vlmOutput = "", detections = emptyList()) }
        try {
            val analyzer = resolveAnalyzer(useOnDevice, apiKey) ?: return
            when (op) {
                VlmOp.ANALYZE -> runAnalyze(analyzer, frameJpeg, prompt)
                VlmOp.DETECT -> runDetect(analyzer, frameJpeg, prompt)
                VlmOp.PILOT -> runPilot(analyzer, frameJpeg, prompt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "VLM $op failed", e)
            _uiState.update { it.copy(errorMessage = "VLM error: ${e.message}") }
        } finally {
            _uiState.update { it.copy(vlmRunning = false) }
        }
    }

    // -------------------------------------------------------------------------
    // Depth reflex layer (geometry) – see docs/scene-understanding-plan.md, Level 2
    // -------------------------------------------------------------------------

    /** Path of the resolved depth model, or null when none is on the device. */
    @Volatile private var depthModelPath: String? = null

    /** Persistent depth engine (lazily created for [depthModelPath], closed in onCleared). */
    private var depthEstimator: DepthEstimator? = null

    /** Drops camera frames while a depth inference is already in flight. */
    private val depthBusy = AtomicBoolean(false)

    /**
     * Fed every sampled camera frame (~4fps) by [CameraFrameProvider.onFrame]. Runs the depth model
     * when one is available and publishes the [CorridorReport] to [UiState.corridors]. No-op (and
     * free) when no depth model is on the device.
     */
    fun onCameraFrame(frameJpeg: ByteArray) {
        val path = depthModelPath ?: return
        if (!depthBusy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val estimator =
                    depthEstimator?.takeIf { it.modelPath == path }
                        ?: run {
                            depthEstimator?.close()
                            DepthEstimator(path).also { depthEstimator = it }
                        }
                val report = estimator.estimateCorridors(frameJpeg)
                if (report != null) {
                    _uiState.update { it.copy(corridors = report) }
                    // Depth-only reactive driver: turn each fresh reading into a drive command.
                    // The decision is always recorded (visible in the drive pad); actuation is
                    // gated on connected+armed inside dispatchDriveCommand, so this is safe to
                    // watch against the mock receiver before touching real hardware.
                    if (_uiState.value.reflexDriveEnabled) {
                        val command = ReflexPilot.decide(report)
                        _uiState.update { it.copy(lastDriveCommand = command) }
                        dispatchDriveCommand(command)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Depth estimation failed – disabling reflex layer", e)
                depthModelPath = null
                _uiState.update { it.copy(depthModelName = null, corridors = null) }
            } finally {
                depthBusy.set(false)
            }
        }
    }

    /**
     * Enables/disables the depth-only reactive autopilot ([ReflexPilot]). While on, each depth
     * frame drives the car directly (gated on connected+armed). Turning it off issues an immediate
     * STOP so the car doesn't coast on the last reactive command.
     */
    fun setReflexDrive(enabled: Boolean) {
        _uiState.update { it.copy(reflexDriveEnabled = enabled) }
        if (!enabled) dispatchDriveCommand(DriveCommand(DriveAction.STOP))
    }

    /**
     * Ground-truth geometry line injected into the pilot prompt, or null when the reflex layer has
     * no fresh reading.
     */
    private fun geometryHint(): String? {
        val c = _uiState.value.corridors?.takeIf { it.isFresh() } ?: return null
        return "A depth sensor reports obstacle proximity (trust it over visual guessing): " +
            "left ${(c.left * 100).toInt()}%, center ${(c.center * 100).toInt()}%, " +
            "right ${(c.right * 100).toInt()}% " +
            "(100% = touching the car, under 40% = open space)."
    }

    // -------------------------------------------------------------------------
    // VLM pilot – one perception→decision→actuation step
    // -------------------------------------------------------------------------

    /** Watchdog that returns the car to neutral after a drive step; cancelled by the next step. */
    private var driveStopJob: Job? = null

    /**
     * Manually triggers a single [DriveAction] step, e.g. from the on-screen drive pad. Runs
     * through exactly the same dispatch path as VLM pilot decisions – including the auto-stop
     * watchdog – so the pad can be used to tune [DriveCommand.toDriveVector] mappings by feel.
     */
    fun driveAction(action: DriveAction, speed: DriveSpeed = DriveSpeed.SLOW) {
        val command = DriveCommand(action, speed)
        _uiState.update { it.copy(lastDriveCommand = command) }
        dispatchDriveCommand(command)
    }

    /**
     * One pilot step: sends [frameJpeg] to the VLM with the [DriveCommandParsing] prompt, parses
     * the returned JSON into a [DriveCommand], and hands it to [executePilotCommand] (which gates
     * actuation on connected+armed and applies the auto-stop watchdog). The decision is always
     * surfaced in [UiState.vlmOutput] and [UiState.lastDriveCommand], even when the motors stay
     * idle, so the loop can be exercised safely in mock mode or with the car on a bench.
     */
    private fun runPilot(analyzer: VisionAnalyzer, frameJpeg: ByteArray, goal: String) {
        // Reuse the detection streamer to find the first complete top-level JSON
        // object in the token stream (it also strips markdown fences and prose).
        val streamer = DetectionParsing.Streamer()
        var command: DriveCommand? = null
        val rawText = StringBuilder()
        val prompt = DriveCommandParsing.buildPrompt(goal, geometryHint())
        analyzer.analyzeFrame(frameJpeg, prompt) { token ->
            rawText.append(token)
            if (command == null) {
                command =
                    streamer.feed(token).firstNotNullOfOrNull { DriveCommandParsing.parse(it) }
            }
        }
        val cmd = command
        if (cmd == null) {
            Log.w(TAG, "pilot: no valid command. Raw output: ${rawText.take(500)}")
            _uiState.update { it.copy(vlmOutput = "Pilot returned no valid command (see logcat).") }
        } else {
            executePilotCommand(cmd)
        }
    }

    /**
     * Surfaces a parsed pilot [DriveCommand] in the UI, then actuates it via
     * [dispatchDriveCommand]. The decision text is always shown, even when the motors stay idle, so
     * the loop can be exercised safely in mock mode or with the car on a bench.
     */
    private fun executePilotCommand(command: DriveCommand) {
        // Full decision record (action, speed, reason, scene) – the primary debugging trail for
        // every questionable move the pilot makes.
        Log.i(TAG, "Pilot decision: $command")
        val s = _uiState.value
        val summary = buildString {
            command.scene?.let { scene ->
                appendLine("Scene L: ${scene.left.ifBlank { "?" }}")
                appendLine("      C: ${scene.center.ifBlank { "?" }}")
                appendLine("      R: ${scene.right.ifBlank { "?" }}")
            }
            append("Pilot: ${command.action.name.lowercase()} (${command.speed.name.lowercase()})")
            if (command.reason.isNotBlank()) append("\nReason: ${command.reason}")
        }
        val canActuate = s.isConnected && s.isArmed
        _uiState.update {
            it.copy(
                lastDriveCommand = command,
                vlmOutput =
                    if (canActuate) summary
                    else "$summary\n(motors idle – connect and arm to actuate)",
            )
        }
        dispatchDriveCommand(command)
    }

    /**
     * Sends [command] to the motors, bounded by the [DRIVE_STEP_DURATION_MS] watchdog so a single
     * decision can never drive the car indefinitely. No-op unless connected **and armed**.
     *
     * Reflex veto: when the depth layer has a fresh reading showing the center corridor blocked,
     * commands that keep substantial forward motion (FORWARD, VEER_*) are replaced with STOP –
     * regardless of what the VLM (or drive pad) asked for. Full-lock turns and everything in
     * reverse stay allowed so the car can still escape a blocked position. The joystick bypasses
     * this entirely (direct human control).
     */
    private fun dispatchDriveCommand(command: DriveCommand) {
        val s = _uiState.value
        if (!s.isConnected || !s.isArmed) return

        var effective = command
        val corridors = s.corridors
        val intoBlockage =
            command.action in REFLEX_VETOED_ACTIONS &&
                corridors != null &&
                corridors.isFresh() &&
                corridors.centerBlocked
        if (intoBlockage) {
            val pct = ((corridors?.center ?: 0f) * 100).toInt()
            Log.w(TAG, "Reflex veto: center corridor $pct% blocked – ${command.action} → STOP")
            _uiState.update {
                it.copy(
                    vlmOutput =
                        it.vlmOutput + "\nREFLEX VETO: obstacle ahead (depth $pct%) – stopped."
                )
            }
            effective = DriveCommand(DriveAction.STOP)
        }
        val vector = effective.toDriveVector()

        driveStopJob?.cancel()
        commandChannel.trySend(Command.Drive(vector.throttle, vector.steering))
        _uiState.update { st -> st.copy(throttle = intArrayOf(vector.throttle, vector.steering)) }

        if (effective.action != DriveAction.STOP) {
            driveStopJob =
                viewModelScope.launch(Dispatchers.IO) {
                    delay(DRIVE_STEP_DURATION_MS)
                    commandChannel.trySend(Command.Drive(0, 0))
                    _uiState.update { st -> st.copy(throttle = intArrayOf(0, 0)) }
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
        depthEstimator?.close()
    }

    private companion object {
        const val KEY_HF_TOKEN = "hf_token"
        const val KEY_CLOUD_BASE_URL = "cloud_base_url"
        const val KEY_CLOUD_MODEL = "cloud_model"
        const val KEY_CLOUD_API_KEY = "cloud_api_key"
        const val KEY_USE_ON_DEVICE_VLM = "use_on_device_vlm"
        const val DEFAULT_CLOUD_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_CLOUD_MODEL = "gpt-4o"
        const val TAG = "RCViewModel"

        /** How long a single drive step (pilot or pad) may run the motors before auto-stop. */
        const val DRIVE_STEP_DURATION_MS = 1500L

        /**
         * Breather between continuous-loop iterations (UI settle + avoid hammering the backend).
         */
        const val CONTINUOUS_PAUSE_MS = 250L

        /** Actions the depth reflex layer may veto: those keeping substantial forward motion. */
        private val REFLEX_VETOED_ACTIONS =
            setOf(DriveAction.FORWARD, DriveAction.VEER_LEFT, DriveAction.VEER_RIGHT)
    }
}
