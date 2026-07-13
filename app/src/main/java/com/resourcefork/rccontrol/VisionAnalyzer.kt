package com.resourcefork.rccontrol

import java.io.IOException

/**
 * A backend that analyzes a single JPEG camera frame with a vision-language model.
 *
 * Two implementations exist:
 * - [VlmClient] – cloud, OpenAI-compatible streaming API (needs an API key + network).
 * - [LocalVlmClient] – fully on-device / offline via MediaPipe LiteRT (needs a model file).
 *
 * Both run **synchronously** and must be called from a background thread / coroutine.
 */
interface VisionAnalyzer {

    /** Returns a free-text description; [onToken] receives incremental text as it is produced. */
    @Throws(IOException::class)
    fun analyzeFrame(
        imageBytes: ByteArray,
        prompt: String = "Describe what you see. What obstacles are ahead?",
        onToken: (String) -> Unit = {},
    ): String

    /** Returns normalized bounding boxes; [onDetection] fires as each box becomes available. */
    @Throws(IOException::class)
    fun detectObjects(
        imageBytes: ByteArray,
        userPrompt: String = "",
        onDetection: (Detection) -> Unit = {},
    ): List<Detection>
}
