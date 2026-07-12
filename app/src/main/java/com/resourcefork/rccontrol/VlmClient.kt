package com.resourcefork.rccontrol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Sends a single JPEG frame to a VLM API endpoint and streams the response
 * back token-by-token via [onToken].
 *
 * Uses the OpenAI Chat Completions API format with streaming enabled, which is
 * supported by OpenAI (gpt-4o), Google Gemini (via the openai-compat endpoint),
 * Anthropic Claude (via their own endpoint), and local models via Ollama.
 *
 * Usage:
 * ```kotlin
 * val client = VlmClient(
 *     apiKey  = "sk-...",
 *     baseUrl = "https://api.openai.com/v1",
 *     model   = "gpt-4o",
 * )
 * client.analyzeFrame(jpegBytes, "What is ahead of me?") { token ->
 *     // called on the calling thread for each streamed token
 *     appendText(token)
 * }
 * ```
 */
class VlmClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o",
    connectTimeoutSec: Long = 10,
    readTimeoutSec: Long = 60,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends [imageBytes] (JPEG) + [prompt] to the VLM and delivers each
     * streamed text token to [onToken].  Runs **synchronously** – call from
     * a background thread / coroutine.
     *
     * @return the full concatenated response on success.
     * @throws IOException on network / HTTP errors.
     */
    @Throws(IOException::class)
    fun analyzeFrame(
        imageBytes: ByteArray,
        prompt: String = "Describe what you see. What obstacles are ahead?",
        onToken: (String) -> Unit = {},
    ): String {
        val b64 = Base64.getEncoder().encodeToString(imageBytes)
        val body = buildRequestJson(b64, prompt)
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("VLM HTTP ${response.code}: ${response.body?.string()}")
            }
            val source = response.body?.source()
                ?: throw IOException("VLM response body is null")
            return collectSseTokens(source, onToken)
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun buildRequestJson(imageB64: String, prompt: String): String = """
        {
          "model": "$model",
          "stream": true,
          "messages": [
            {
              "role": "user",
              "content": [
                {
                  "type": "image_url",
                  "image_url": { "url": "data:image/jpeg;base64,$imageB64" }
                },
                {
                  "type": "text",
                  "text": ${escapeJsonString(prompt)}
                }
              ]
            }
          ]
        }
    """.trimIndent()

    /**
     * Reads Server-Sent Events from [source], extracts the delta content from
     * each `data: {...}` line and calls [onToken].
     */
    private fun collectSseTokens(
        source: okio.BufferedSource,
        onToken: (String) -> Unit,
    ): String {
        val sb = StringBuilder()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data: ")) continue
            val payload = line.removePrefix("data: ").trim()
            if (payload == "[DONE]") break
            val token = extractDeltaContent(payload) ?: continue
            if (token.isNotEmpty()) {
                onToken(token)
                sb.append(token)
            }
        }
        return sb.toString()
    }

    /** Extracts `choices[0].delta.content` from an OpenAI SSE chunk JSON. */
    private fun extractDeltaContent(jsonStr: String): String? = try {
        val root    = json.parseToJsonElement(jsonStr).jsonObject
        val choices = root["choices"]
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?: return null
        val first   = choices.firstOrNull()?.jsonObject ?: return null
        first["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
