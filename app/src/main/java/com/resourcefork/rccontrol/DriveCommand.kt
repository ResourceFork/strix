package com.resourcefork.rccontrol

/**
 * The closed vocabulary of motion primitives a VLM may request.
 *
 * This is the "natural-language API" between perception and actuation: the model is prompted to
 * pick exactly one of these actions (plus a [DriveSpeed]) and the app – never the model – decides
 * what motor values each one maps to. The vocabulary is deliberately small and discrete: small
 * on-device models are far more reliable at choosing from an enum than at emitting
 * well-calibrated numbers.
 *
 * The chassis is car-style (a powered drive motor plus a steering servo), so the reverse family
 * mirrors the forward family exactly: VEER = gentle steering deflection, TURN = full steering
 * lock, in either direction of travel. There is no "rotate in place" – a steered car cannot
 * pivot.
 */
enum class DriveAction {
    /** Drive straight ahead. */
    FORWARD,
    /** Keep moving forward with gentle left steering (course correction). */
    VEER_LEFT,
    /** Keep moving forward with gentle right steering (course correction). */
    VEER_RIGHT,
    /** Drive forward at full left steering lock (sharp turn). */
    TURN_LEFT,
    /** Drive forward at full right steering lock (sharp turn). */
    TURN_RIGHT,
    /** Back up straight. */
    REVERSE,
    /** Back up with gentle left steering – the tail swings left. */
    REVERSE_VEER_LEFT,
    /** Back up with gentle right steering – the tail swings right. */
    REVERSE_VEER_RIGHT,
    /** Back up at full left steering lock. */
    REVERSE_TURN_LEFT,
    /** Back up at full right steering lock. */
    REVERSE_TURN_RIGHT,
    /** Halt all motion. Also the fail-safe whenever the model is blocked or unsure. */
    STOP,
}

/** Coarse speed bucket; translated to concrete thrust magnitudes by [DriveCommand.toDriveVector]. */
enum class DriveSpeed {
    SLOW,
    NORMAL,
    FAST,
}

/** Concrete channel values for [IMotorController.drive] (both -100…100): [throttle] is thrust for
 * the drive ESC, [steering] is the steering-servo position (-100 full left … 100 full right).
 */
data class DriveVector(val throttle: Int, val steering: Int)

/**
 * The model's coarse reading of the camera frame, split into three vertical corridors – the same
 * resolution as the steering vocabulary (the car can only go left, center, or right). Each field
 * is a few free-text words ("clear floor", "chair, very close").
 *
 * Display and logging only – it never influences the motors. Its real job is forcing the model to
 * describe the scene *before* choosing an action (describe-then-decide), and giving us a record
 * of what the model thought it saw when a decision goes wrong.
 */
data class SceneReport(
    val left: String = "",
    val center: String = "",
    val right: String = "",
)

/**
 * One decision step emitted by the VLM pilot: what it saw, what to do, how fast, and why.
 *
 * [reason] and [scene] are free text for the UI and logs only – they never influence the motors.
 */
data class DriveCommand(
    val action: DriveAction,
    val speed: DriveSpeed = DriveSpeed.SLOW,
    val reason: String = "",
    val scene: SceneReport? = null,
) {

    /**
     * Maps the declarative action onto thrust + steering for [IMotorController.drive].
     *
     * Thrust and steering are fully independent: [speed] scales only the thrust magnitude, while
     * the steering deflection depends only on the maneuver type (veer vs. turn). The steering
     * sign is the same whether driving forward or reversing – on a steered chassis you steer in
     * the direction you want the tail to go, so "left" means the same servo direction both ways.
     */
    fun toDriveVector(): DriveVector {
        val magnitude =
            when (speed) {
                DriveSpeed.SLOW -> 30
                DriveSpeed.NORMAL -> 55
                DriveSpeed.FAST -> 80
            }
        val thrust =
            when (action) {
                DriveAction.FORWARD,
                DriveAction.VEER_LEFT,
                DriveAction.VEER_RIGHT,
                DriveAction.TURN_LEFT,
                DriveAction.TURN_RIGHT -> magnitude
                DriveAction.REVERSE,
                DriveAction.REVERSE_VEER_LEFT,
                DriveAction.REVERSE_VEER_RIGHT,
                DriveAction.REVERSE_TURN_LEFT,
                DriveAction.REVERSE_TURN_RIGHT -> -magnitude
                DriveAction.STOP -> 0
            }
        val steering =
            when (action) {
                DriveAction.VEER_LEFT,
                DriveAction.REVERSE_VEER_LEFT -> -VEER_STEERING
                DriveAction.VEER_RIGHT,
                DriveAction.REVERSE_VEER_RIGHT -> VEER_STEERING
                DriveAction.TURN_LEFT,
                DriveAction.REVERSE_TURN_LEFT -> -TURN_STEERING
                DriveAction.TURN_RIGHT,
                DriveAction.REVERSE_TURN_RIGHT -> TURN_STEERING
                else -> 0
            }
        return DriveVector(thrust, steering)
    }

    companion object {
        /**
         * Steering-servo deflection for the two maneuver types. Starting points – tune by feel
         * with the on-screen drive pad; changes here affect the VLM pilot and the pad alike.
         */
        const val VEER_STEERING = 45
        const val TURN_STEERING = 100
    }
}
