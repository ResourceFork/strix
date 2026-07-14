package com.resourcefork.rccontrol

import android.content.Context
import java.io.File

/**
 * Finds an on-device VLM model file (`.task` / `.litertlm`) that has been placed on the device.
 *
 * Search order:
 * 1. The app's external files dir: `<externalFilesDir>/models/`
 *    (`/sdcard/Android/data/com.resourcefork.rccontrol/files/models/`).
 * 2. `/data/local/tmp/llm/` – where `adb push` places dev models per the MediaPipe guide.
 *
 * The first `.task`/`.litertlm` file found wins.
 */
object LocalModelLocator {
    private val EXTENSIONS = listOf(".task", ".litertlm")

    fun candidateDirs(context: Context): List<File> =
        listOf(File(context.getExternalFilesDir(null), "models"), File("/data/local/tmp/llm"))

    /**
     * Absolute path of the model file to use, or null if none is present. When multiple model files
     * exist, the most recently modified one wins — so a freshly downloaded/pushed model takes
     * effect without needing to delete the old one.
     */
    fun resolve(context: Context): String? {
        for (dir in candidateDirs(context)) {
            val model =
                dir.listFiles()
                    ?.filter { file ->
                        file.isFile && EXTENSIONS.any { ext -> file.name.endsWith(ext) }
                    }
                    ?.maxByOrNull { it.lastModified() }
            if (model != null) return model.absolutePath
        }
        return null
    }
}
