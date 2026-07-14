package com.resourcefork.rccontrol

/**
 * A single object detection returned by the VLM.
 *
 * Coordinates are normalized to the range [0f, 1f] relative to the analyzed
 * frame, with (0, 0) at the top-left corner. This keeps them resolution- and
 * orientation-independent so the UI can scale them onto any preview size.
 */
data class Detection(
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Model confidence in [0f, 1f], or null if the model didn't provide one. */
    val confidence: Float? = null,
)
