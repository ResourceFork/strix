package com.resourcefork.rccontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * Camera preview + VLM text stream section.
 *
 * Shows:
 * 1. A live [PreviewView] from CameraX.
 * 2. API-key and prompt fields (collapsed by default).
 * 3. An "Analyze" button that triggers one VLM call.
 * 4. A scrollable area where the streamed VLM response appears.
 */
@Composable
fun CameraSection(
    previewView: PreviewView?,
    vlmOutput: String,
    vlmRunning: Boolean,
    cameraPermissionGranted: Boolean,
    onCameraPermissionResult: (Boolean) -> Unit,
    onAnalyze: (apiKey: String, prompt: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onCameraPermissionResult(granted)
    }

    var apiKey by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("Describe what you see. What obstacles are ahead?") }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Camera / VLM", style = MaterialTheme.typography.titleSmall)
        }

        if (!cameraPermissionGranted) {
            val alreadyGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

            if (alreadyGranted) {
                onCameraPermissionResult(true)
            } else {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Camera Permission")
                }
            }
        } else {
            // Live camera preview
            if (previewView != null) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }

            // VLM configuration fields
            OutlinedTextField(
                value         = apiKey,
                onValueChange = { apiKey = it },
                label         = { Text("VLM API Key") },
                singleLine    = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier      = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value         = prompt,
                onValueChange = { prompt = it },
                label         = { Text("Prompt") },
                modifier      = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick  = { onAnalyze(apiKey, prompt) },
                    enabled  = !vlmRunning && apiKey.isNotBlank(),
                ) {
                    Text("Analyze Frame")
                }
                if (vlmRunning) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
                }
            }

            // VLM text stream output
            if (vlmOutput.isNotBlank() || vlmRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                ) {
                    Text(
                        text     = vlmOutput,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}
