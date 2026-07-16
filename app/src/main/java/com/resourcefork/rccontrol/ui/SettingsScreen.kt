package com.resourcefork.rccontrol.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.resourcefork.rccontrol.DownloadableModel
import com.resourcefork.rccontrol.ModelCatalog

/**
 * Full-screen settings, reached via the top-bar menu.
 *
 * Hosts everything that isn't needed during normal driving/analysis:
 * - Remote VLM server configuration (URL / model / API key — all persisted).
 * - On-device model management: scan status, re-scan, and in-app model download.
 *
 * The on-device/remote toggle itself lives in the top-bar menu as a checkbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDeviceModelAvailable: Boolean,
    onDeviceModelName: String?,
    onDeviceWarming: Boolean,
    onDeviceReady: Boolean,
    depthModelName: String?,
    downloadInProgress: Boolean,
    downloadProgress: Float?,
    downloadingModelName: String?,
    hfToken: String,
    cloudBaseUrl: String,
    cloudModel: String,
    apiKey: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRescanModel: () -> Unit,
    onHfTokenChange: (String) -> Unit,
    onCloudBaseUrlChange: (String) -> Unit,
    onCloudModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onDownloadModel: (DownloadableModel, String) -> Unit,
    onDownloadDepthModel: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Remote VLM server ─────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Remote VLM server", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Any OpenAI-compatible endpoint: OpenAI, Gemini (openai-compat), or a " +
                            "self-hosted Ollama / llama.cpp / vLLM server.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    OutlinedTextField(
                        value = cloudBaseUrl,
                        onValueChange = onCloudBaseUrlChange,
                        label = { Text("Server URL (e.g. http://100.x.y.z:11434/v1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = cloudModel,
                        onValueChange = onCloudModelChange,
                        label = { Text("Model (e.g. qwen3-vl:8b or gpt-4o)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text("API Key (optional for local servers)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── On-device model ───────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("On-device model", style = MaterialTheme.typography.titleSmall)

                    val statusText =
                        when {
                            onDeviceWarming -> "Loading model into memory…"
                            onDeviceReady -> "Model ready · $onDeviceModelName"
                            onDeviceModelAvailable -> "Model found · $onDeviceModelName"
                            else ->
                                "No model on this device yet — download one below, or push a " +
                                    ".task file via the desktop script and re-scan."
                        }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f),
                        )
                        if (onDeviceWarming) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(start = 8.dp).size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        TextButton(
                            onClick = onRescanModel,
                            enabled = !onDeviceWarming && !downloadInProgress,
                        ) {
                            Text("Re-scan")
                        }
                    }

                    HorizontalDivider()

                    // Downloading a second model is fine: the most recently modified
                    // .task file on the device wins after the next re-scan.
                    ModelDownloadPanel(
                        downloadInProgress = downloadInProgress,
                        downloadProgress = downloadProgress,
                        downloadingModelName = downloadingModelName,
                        hfToken = hfToken,
                        onHfTokenChange = onHfTokenChange,
                        onDownloadModel = onDownloadModel,
                    )
                }
            }

            // ── Obstacle depth (geometry reflex layer) ────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Obstacle depth", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "A small depth model that runs continuously alongside the camera. It " +
                            "shades the three drive corridors on the preview and stops the car " +
                            "when the path ahead is blocked — independent of the VLM. Optional " +
                            "but strongly recommended for autonomous driving.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    val depthModel = ModelCatalog.depthModel
                    val depthStatus =
                        if (depthModelName != null) "Installed · $depthModelName"
                        else "Not installed — the reflex layer is off until you add it."
                    Text(
                        depthStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )

                    // No token / license: fetched from a public GitHub release. Shares the same
                    // one-at-a-time download state as the VLM downloader above.
                    val downloadingDepth =
                        downloadInProgress && downloadingModelName == depthModel.name
                    if (downloadingDepth) {
                        if (downloadProgress != null) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            "Downloading depth model…" +
                                (downloadProgress?.let { " ${(it * 100).toInt()}%" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    } else {
                        Button(onClick = onDownloadDepthModel, enabled = !downloadInProgress) {
                            val verb = if (depthModelName != null) "Re-download" else "Download"
                            Text("$verb depth model (${depthModel.sizeMb} MB)")
                        }
                    }
                }
            }

            Box(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/** Lets the user download a VLM model straight into the app's storage (token-gated for Gemma). */
@Composable
private fun ModelDownloadPanel(
    downloadInProgress: Boolean,
    downloadProgress: Float?,
    downloadingModelName: String?,
    hfToken: String,
    onHfTokenChange: (String) -> Unit,
    onDownloadModel: (DownloadableModel, String) -> Unit,
) {
    var selected by remember { mutableStateOf(ModelCatalog.models.first()) }
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        val uri = Uri.parse(url)
        try {
            // Custom Tab: in-app browser that returns cleanly to Strix when finished.
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } catch (_: Exception) {
            // Fall back to a normal browser if no Custom Tabs provider is available.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                // No browser/handler available on the device.
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Download a model to this device", style = MaterialTheme.typography.labelLarge)

        Box {
            OutlinedButton(onClick = { menuExpanded = true }, enabled = !downloadInProgress) {
                Text(selected.name)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                ModelCatalog.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text("${model.name} · ${model.sizeMb} MB") },
                        onClick = {
                            selected = model
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        Text(
            selected.description + if (selected.gated) " · gated: needs a HF token" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        // Gated Gemma models need a one-time license acceptance + a read token, both on HF.
        // These open the relevant pages in an in-app browser tab.
        if (selected.gated) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { openUrl("https://huggingface.co/${selected.repo}") },
                    enabled = !downloadInProgress,
                ) {
                    Text("Accept license ↗")
                }
                TextButton(
                    // Preconfigured fine-grained token creation page. IMPORTANT: gated access is
                    // a separate checkbox there — see the hint below the token field.
                    onClick = {
                        openUrl(
                            "https://huggingface.co/settings/tokens/new" +
                                "?tokenType=fineGrained&globalPermissions=repo.content.read"
                        )
                    },
                    enabled = !downloadInProgress,
                ) {
                    Text("Create a token ↗")
                }
            }
        }

        // Token is held in the ViewModel and persisted, so it survives app restarts.
        OutlinedTextField(
            value = hfToken,
            onValueChange = onHfTokenChange,
            label = { Text("Hugging Face token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !downloadInProgress,
            modifier = Modifier.fillMaxWidth(),
        )
        if (selected.gated) {
            Text(
                "Fine-grained tokens must have \u2611 \"Read access to contents of all public " +
                    "gated repos\" checked, or use a classic Read token. Account access alone " +
                    "isn't enough.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        if (downloadInProgress) {
            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "Downloading ${downloadingModelName ?: "model"}…" +
                    (downloadProgress?.let { " ${(it * 100).toInt()}%" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        } else {
            Button(onClick = { onDownloadModel(selected, hfToken) }) {
                Text("Download (${selected.sizeMb} MB)")
            }
        }
    }
}
