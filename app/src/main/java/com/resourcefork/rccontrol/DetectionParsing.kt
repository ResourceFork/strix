package com.resourcefork.rccontrol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared logic for turning a VLM's textual JSON output into [Detection]s.
 *
 * Used by both the cloud [VlmClient] and the on-device [LocalVlmClient] so the detection prompt and
 * the parsing/normalization rules stay identical across backends.
 */
internal object DetectionParsing {

    private val json = Json { ignoreUnknownKeys = true }

    /** Builds the instruction that asks a VLM to return a JSON array of boxes. */
    fun buildPrompt(userPrompt: String): String {
        val focus =
            if (userPrompt.isNotBlank()) " Focus on things relevant to: $userPrompt." else ""
        // Output length is capped deliberately: on-device decode time scales linearly with
        // the number of generated tokens, so fewer/terser detections = faster responses.
        return "Detect and localize the prominent objects in this image.$focus " +
            "Respond with ONLY a JSON array and nothing else – no prose, no markdown code fences. " +
            "Each array element must be an object of the form " +
            "{\"label\": string, \"box\": [ymin, xmin, ymax, xmax], \"confidence\": number}. " +
            "All box coordinates must be normalized floats between 0 and 1 with the top-left as origin. " +
            "Return at most 6 objects, ordered by prominence. Keep labels to one or two words."
    }

    /** Parses a single detection object emitted by [Streamer]; returns null if malformed. */
    fun parse(objJson: String): Detection? =
        try {
            val dto = json.decodeFromString(DetectionDto.serializer(), objJson)
            val box = dto.box
            val label = dto.label
            if (label.isNullOrBlank() || box == null || box.size < 4) {
                null
            } else {
                // Some models emit 0..1000 (Gemini style) instead of 0..1. Auto-scale.
                val scale = if (box.take(4).any { it > 1.5f }) 1000f else 1f
                val ymin = box[0] / scale
                val xmin = box[1] / scale
                val ymax = box[2] / scale
                val xmax = box[3] / scale
                Detection(
                    label = label,
                    left = minOf(xmin, xmax).coerceIn(0f, 1f),
                    top = minOf(ymin, ymax).coerceIn(0f, 1f),
                    right = maxOf(xmin, xmax).coerceIn(0f, 1f),
                    bottom = maxOf(ymin, ymax).coerceIn(0f, 1f),
                    confidence = dto.confidence?.coerceIn(0f, 1f),
                )
            }
        } catch (_: Exception) {
            null
        }

    @Serializable
    private data class DetectionDto(
        val label: String? = null,
        val box: List<Float>? = null,
        val confidence: Float? = null,
    )

    /**
     * Accumulates streamed (or whole) text and emits each complete top-level JSON object (`{ ...
     * }`) as soon as its closing brace arrives. Elements of a bare JSON array are emitted one at a
     * time; surrounding brackets, commas, and any stray markdown fences are ignored since only
     * brace depth is tracked.
     */
    class Streamer {
        private val buf = StringBuilder()
        private var pos = 0
        private var depth = 0
        private var inString = false
        private var escaped = false
        private var objStart = -1

        fun feed(chunk: String): List<String> {
            buf.append(chunk)
            val completed = mutableListOf<String>()
            while (pos < buf.length) {
                val c = buf[pos]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> {
                            if (depth == 0) objStart = pos
                            depth++
                        }
                        '}' -> {
                            if (depth > 0) {
                                depth--
                                if (depth == 0 && objStart >= 0) {
                                    completed.add(buf.substring(objStart, pos + 1))
                                    objStart = -1
                                }
                            }
                        }
                    }
                }
                pos++
            }
            return completed
        }
    }
}
