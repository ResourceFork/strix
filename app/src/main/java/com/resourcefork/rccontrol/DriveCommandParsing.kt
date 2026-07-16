package com.resourcefork.rccontrol

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared logic for turning a VLM's textual JSON output into a [DriveCommand].
 *
 * The actuation-side counterpart of [DetectionParsing]:
 * frame → VLM → `{"action", "speed", "reason"}` JSON → [DriveCommand] → [IMotorController.drive].
 * Backend-agnostic: works with any [VisionAnalyzer] since it only deals in prompt/response text.
 */
internal object DriveCommandParsing {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Builds the instruction that asks a VLM to pick the car's next move toward [goal].
     * [geometryHint] optionally injects the depth reflex layer's corridor readings as ground
     * truth the model should trust over its own visual distance judgment.
     *
     * The JSON shape puts "scene" *first* deliberately: generation is autoregressive, so the
     * model must produce its corridor-by-corridor reading of the frame before it commits to an
     * action (describe-then-decide), which measurably improves the choice – and gives us a log
     * of what it thought it saw.
     */
    fun buildPrompt(goal: String, geometryHint: String? = null): String {
        val mission = goal.ifBlank { "Explore carefully. Avoid hitting obstacles." }
        val geometry = geometryHint?.let { "$it " } ?: ""
        return "You are piloting a small RC car with a powered drive motor and a steering " +
            "servo (it steers like a real car and cannot rotate in place). This image is the " +
            "car's forward-facing camera view. Mission: $mission " +
            geometry +
            "First describe what you see, then decide the single best next move. " +
            "Respond with ONLY one JSON object and nothing else – no prose, no markdown code " +
            "fences – of the form " +
            "{\"scene\": {\"left\": string, \"center\": string, \"right\": string}, " +
            "\"action\": string, \"speed\": string, \"reason\": string}. " +
            "\"scene\" describes each vertical third of the view in at most 6 words: what is " +
            "there and how close it is (e.g. \"clear floor\", \"chair, very close\"). " +
            "\"action\" must be exactly one of: \"forward\", \"veer_left\", \"veer_right\", " +
            "\"turn_left\", \"turn_right\", \"reverse\", \"reverse_veer_left\", " +
            "\"reverse_veer_right\", \"reverse_turn_left\", \"reverse_turn_right\", \"stop\". " +
            "veer means gentle steering, turn means full steering lock. " +
            "\"speed\" must be one of: \"slow\", \"normal\", \"fast\". " +
            "\"reason\" must be under 15 words. " +
            "If the way ahead is blocked, an obstacle is very close, or you are unsure, " +
            "choose \"stop\"."
    }

    /**
     * Parses one JSON object (as emitted by [DetectionParsing.Streamer]); returns null if it is
     * not a valid command. Unknown actions fail closed (null → no motion) rather than guessing.
     */
    fun parse(objJson: String): DriveCommand? =
        try {
            val dto = json.decodeFromString(DriveCommandDto.serializer(), objJson)
            val action = dto.action?.let { parseAction(it) }
            if (action == null) {
                Log.w(TAG, "Drive command missing/unknown action: ${objJson.take(300)}")
                null
            } else {
                DriveCommand(
                    action = action,
                    speed = parseSpeed(dto.speed),
                    reason = dto.reason.orEmpty().trim(),
                    scene =
                        dto.scene?.let {
                            SceneReport(
                                left = it.left.orEmpty().trim(),
                                center = it.center.orEmpty().trim(),
                                right = it.right.orEmpty().trim(),
                            )
                        },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Drive command failed to parse: ${objJson.take(300)} (${e.message})")
            null
        }

    /** Lenient action matching: models sometimes emit "Turn Left", "turn-left", "left", … */
    private fun parseAction(raw: String): DriveAction? =
        when (normalize(raw)) {
            "forward",
            "ahead",
            "straight",
            "go",
            "go_forward",
            "move_forward" -> DriveAction.FORWARD
            "reverse",
            "back",
            "backward",
            "backwards",
            "back_up",
            "go_back" -> DriveAction.REVERSE
            "reverse_veer_left",
            "back_veer_left",
            // Bare "reverse left" is ambiguous between veer/turn – default to the gentler veer.
            "reverse_left",
            "back_left",
            "backward_left" -> DriveAction.REVERSE_VEER_LEFT
            "reverse_veer_right",
            "back_veer_right",
            "reverse_right",
            "back_right",
            "backward_right" -> DriveAction.REVERSE_VEER_RIGHT
            "reverse_turn_left",
            "back_turn_left",
            "reverse_hard_left" -> DriveAction.REVERSE_TURN_LEFT
            "reverse_turn_right",
            "back_turn_right",
            "reverse_hard_right" -> DriveAction.REVERSE_TURN_RIGHT
            "veer_left",
            "bear_left",
            "slight_left",
            "drift_left" -> DriveAction.VEER_LEFT
            "veer_right",
            "bear_right",
            "slight_right",
            "drift_right" -> DriveAction.VEER_RIGHT
            "turn_left",
            "left",
            "hard_left",
            "sharp_left",
            "rotate_left",
            "spin_left" -> DriveAction.TURN_LEFT
            "turn_right",
            "right",
            "hard_right",
            "sharp_right",
            "rotate_right",
            "spin_right" -> DriveAction.TURN_RIGHT
            "stop",
            "halt",
            "brake",
            "wait",
            "none" -> DriveAction.STOP
            else -> null
        }

    /** Unknown or missing speeds default to SLOW – the fail-safe choice on real hardware. */
    private fun parseSpeed(raw: String?): DriveSpeed =
        when (raw?.let { normalize(it) }) {
            "fast",
            "high",
            "quick" -> DriveSpeed.FAST
            "normal",
            "medium",
            "moderate" -> DriveSpeed.NORMAL
            else -> DriveSpeed.SLOW
        }

    private fun normalize(s: String): String =
        s.trim().lowercase().replace(' ', '_').replace('-', '_')

    private const val TAG = "DriveCommandParsing"

    @Serializable
    private data class SceneDto(
        val left: String? = null,
        val center: String? = null,
        val right: String? = null,
    )

    @Serializable
    private data class DriveCommandDto(
        val scene: SceneDto? = null,
        val action: String? = null,
        val speed: String? = null,
        val reason: String? = null,
    )
}
