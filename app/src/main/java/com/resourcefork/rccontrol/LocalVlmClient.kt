package com.resourcefork.rccontrol

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.IOException

/**
 * On-device / offline VLM backend using MediaPipe's LLM Inference API (LiteRT).
 *
 * Loads a multimodal `.task` model (e.g. Gemma 3n) from [modelPath] and runs vision + text
 * inference entirely on the device — no network, no API key. The heavyweight [LlmInference] engine
 * is created lazily on first use and reused across calls; call [close] when finished (the ViewModel
 * owns this lifecycle).
 *
 * Unlike the cloud [VlmClient], MediaPipe returns the full response in one shot
 * (`generateResponse()`), so callbacks fire once the model finishes rather than token-by-token.
 */
class LocalVlmClient(
    private val context: Context,
    val modelPath: String,
    private val maxTokens: Int = 1024,
    private val maxImages: Int = 1,
    private val topK: Int = 10,
    private val temperature: Float = 0.4f,
) : VisionAnalyzer, AutoCloseable {

    // Lazy so the multi-GB model only loads on first actual use; reused afterwards.
    // Prefer the GPU backend: without it the text decoder runs on CPU, which is several
    // times slower per token. Falls back to the default backend if GPU init fails.
    //
    // Emulators are excluded from the GPU path outright: with no vendor GPU driver, the
    // native engine SIGSEGVs (killing the process) rather than throwing something this
    // try/catch could handle.
    private val engineLazy = lazy {
        if (DeviceInfo.isEmulator) {
            Log.w(TAG, "Emulator detected – using default backend (GPU would crash natively)")
            LlmInference.createFromOptions(context, engineOptions(null))
        } else {
            try {
                LlmInference.createFromOptions(context, engineOptions(LlmInference.Backend.GPU))
            } catch (e: Exception) {
                Log.w(TAG, "GPU backend unavailable, falling back to default backend", e)
                LlmInference.createFromOptions(context, engineOptions(null))
            }
        }
    }

    private fun engineOptions(backend: LlmInference.Backend?): LlmInference.LlmInferenceOptions {
        val builder =
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setMaxNumImages(maxImages)
        if (backend != null) builder.setPreferredBackend(backend)
        return builder.build()
    }

    /** True once the (multi-GB) engine has been loaded into memory. */
    val isReady: Boolean
        get() = engineLazy.isInitialized()

    /**
     * Forces the engine to load now so the first real inference isn't blocked by a multi-GB model
     * load. Call from a background thread / coroutine.
     */
    @Throws(IOException::class)
    fun prepare() {
        try {
            engineLazy.value
        } catch (e: Exception) {
            throw IOException("Failed to load on-device model: ${e.message}", e)
        }
    }

    @Throws(IOException::class)
    override fun analyzeFrame(
        imageBytes: ByteArray,
        prompt: String,
        onToken: (String) -> Unit,
    ): String {
        val effectivePrompt = prompt.ifBlank { "Describe what you see. What obstacles are ahead?" }
        val text = runInference(effectivePrompt, imageBytes)
        if (text.isNotEmpty()) onToken(text)
        return text
    }

    @Throws(IOException::class)
    override fun detectObjects(
        imageBytes: ByteArray,
        userPrompt: String,
        onDetection: (Detection) -> Unit,
    ): List<Detection> {
        val text = runInference(DetectionParsing.buildPrompt(userPrompt), imageBytes)
        val streamer = DetectionParsing.Streamer()
        val results = mutableListOf<Detection>()
        for (objJson in streamer.feed(text)) {
            val detection = DetectionParsing.parse(objJson) ?: continue
            results.add(detection)
            onDetection(detection)
        }
        if (results.isEmpty()) {
            Log.w(TAG, "detectObjects: nothing parsed. Raw model output: ${text.take(500)}")
        }
        return results
    }

    @Throws(IOException::class)
    private fun runInference(prompt: String, imageBytes: ByteArray): String {
        val bitmap =
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw IOException("Could not decode camera frame")
        val sessionOptions =
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(topK)
                .setTemperature(temperature)
                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                .build()
        try {
            LlmInferenceSession.createFromOptions(engineLazy.value, sessionOptions).use { session ->
                session.addQueryChunk(prompt)
                session.addImage(BitmapImageBuilder(bitmap).build())
                return session.generateResponse() ?: ""
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("On-device inference failed: ${e.message}", e)
        }
    }

    override fun close() {
        if (engineLazy.isInitialized()) {
            try {
                engineLazy.value.close()
            } catch (_: Exception) {
                // best-effort cleanup
            }
        }
    }

    private companion object {
        const val TAG = "LocalVlmClient"
    }
}
