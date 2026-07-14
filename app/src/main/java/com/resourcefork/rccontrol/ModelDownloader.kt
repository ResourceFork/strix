package com.resourcefork.rccontrol

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** A VLM model that can be downloaded on-device straight from Hugging Face. */
data class DownloadableModel(
    val id: String,
    val name: String,
    val description: String,
    val repo: String,
    val filename: String,
    val sizeMb: Int,
    val gated: Boolean,
)

/**
 * The on-device VLM models the app can fetch directly. Mirrors scripts/vlm-models.json so the
 * in-app downloader and the desktop script offer the same choices.
 */
object ModelCatalog {
    val models =
        listOf(
            DownloadableModel(
                id = "gemma-3n-e2b-it-int4",
                name = "Gemma 3n E2B IT (INT4, vision)",
                description = "Smaller & faster. ~3.0 GB",
                repo = "google/gemma-3n-E2B-it-litert-preview",
                filename = "gemma-3n-E2B-it-int4.task",
                sizeMb = 2992,
                gated = true,
            ),
            DownloadableModel(
                id = "gemma-3n-e4b-it-int4",
                name = "Gemma 3n E4B IT (INT4, vision)",
                description = "Higher quality, larger. ~4.2 GB",
                repo = "google/gemma-3n-E4B-it-litert-preview",
                filename = "gemma-3n-E4B-it-int4.task",
                sizeMb = 4208,
                gated = true,
            ),
        )
}

/**
 * Streams a model `.task` from Hugging Face into the app's own external files dir
 * (`<externalFilesDir>/models/`) — the same location [LocalModelLocator] scans. Downloading here
 * means no `adb push` and no desktop step; the model lands exactly where the app expects it.
 */
class ModelDownloader(private val context: Context) {

    private val http =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // multi-GB download; no read timeout
            .build()

    /**
     * Downloads [model] to `<externalFilesDir>/models/<filename>`, writing to a `.part` file first
     * and renaming on success. [onProgress] reports (bytesRead, totalBytes); totalBytes is -1 if
     * the server didn't send a length. Pass an [hfToken] for gated models.
     *
     * @throws IOException on network/HTTP errors or if the file can't be written.
     */
    @Throws(IOException::class)
    fun download(
        model: DownloadableModel,
        hfToken: String?,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create model directory: $dir")
        }
        val outFile = File(dir, model.filename)
        val partFile = File(dir, "${model.filename}.part")

        val url = "https://huggingface.co/${model.repo}/resolve/main/${model.filename}"
        val requestBuilder = Request.Builder().url(url)
        // Strip whitespace defensively: a pasted trailing newline/space in a header value either
        // makes OkHttp throw or makes HF reject the credential as malformed.
        val cleanToken = hfToken?.filterNot { it.isWhitespace() }
        if (!cleanToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $cleanToken")
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                // Surface the server's own message – it distinguishes a bad/insufficient token
                // ("Invalid credentials...") from a not-yet-accepted license ("...gated repo").
                val fullBody =
                    try {
                        response.body?.string()?.trim().orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                // Dump the complete error to logcat: the snackbar truncates and disappears, so
                // this is where the full server response can be read at leisure.
                //   adb logcat -s ModelDownloader
                Log.e(
                    TAG,
                    "Model download failed: HTTP ${response.code} ${response.message}\n" +
                        "URL: $url\n" +
                        "Response headers:\n${response.headers}\n" +
                        "Response body:\n${fullBody.ifEmpty { "(empty)" }}",
                )
                val serverMsg = fullBody.take(200)
                val hint =
                    when (response.code) {
                        401 ->
                            " Token was not accepted. Check it's a valid HF token with read " +
                                "access (fine-grained tokens need 'read gated repos' enabled)."
                        403 ->
                            if (serverLooksLikeCdn(fullBody)) {
                                " HF accepted the token but its download CDN refused this " +
                                    "network (corporate/VPN egress is often blocked). Try a " +
                                    "different network (e.g. hotspot), or download on a desktop " +
                                    "and push via the script's 'p' option."
                            } else {
                                " Access denied. Either the license isn't accepted yet ('Accept " +
                                    "license'), or the token lacks gated-repo permission: " +
                                    "fine-grained tokens need 'Read access to all public gated " +
                                    "repos' checked."
                            }
                        else -> ""
                    }
                val detail = if (serverMsg.isNotEmpty()) " Server said: $serverMsg" else ""
                throw IOException("HTTP ${response.code} while downloading model.$hint$detail")
            }
            val body = response.body ?: throw IOException("Empty response body")
            val total = body.contentLength()

            body.byteStream().use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var readTotal = 0L
                    var lastReported = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        // Throttle progress callbacks to ~every 4 MB to avoid UI churn.
                        if (readTotal - lastReported >= 4L * 1024 * 1024) {
                            lastReported = readTotal
                            onProgress(readTotal, total)
                        }
                    }
                    output.flush()
                    onProgress(readTotal, total)
                }
            }
        }

        if (outFile.exists() && !outFile.delete()) {
            throw IOException("Could not replace existing model file")
        }
        if (!partFile.renameTo(outFile)) {
            partFile.delete()
            throw IOException("Could not finalize downloaded model")
        }
        return outFile
    }

    /**
     * A 4xx with an S3/CloudFront XML body means HF's app-level auth already passed and the storage
     * CDN itself refused us — a network-level block, not a credential problem.
     */
    private fun serverLooksLikeCdn(body: String): Boolean =
        body.contains("<Error>") && body.contains("AccessDenied")

    private companion object {
        const val TAG = "ModelDownloader"
    }
}
