package com.resourcefork.rccontrol

/**
 * Pure reactive obstacle-avoidance policy: maps an [ObstacleField] (the fused forward picture –
 * camera depth bands + measured ToF/ultrasonics) directly to a [DriveCommand], with no VLM in the
 * loop.
 *
 * This is the geometry layer acting as a *driver* in its own right, not just the safety veto in
 * [RCViewModel.dispatchDriveCommand]. Because it runs at the depth frame rate (a few Hz) it reacts
 * far faster than a VLM pilot step, which makes it the cheapest way to watch the full perception ->
 * decision -> actuation chain move (e.g. against the mock receiver, with no car).
 *
 * The policy is deliberately simple and stateless – a classic reactive navigator over the five
 * [ObstacleField] zones `[outer-left, inner-left, center, inner-right, outer-right]`. The field
 * fuses everything the car senses forward (camera depth bands + measured ToF/ultrasonics), so the
 * same policy runs on inference, measurement, or both – whatever is fresh:
 * - center lane clear -> drive forward; veer away only if an inner band is crowding
 * - center blocked, inner clear -> veer (gentle, keeps forward progress) into the open inner band
 * - center+inner blocked -> turn (full lock) toward the more open outer band
 * - boxed in on all sides -> back up straight, then re-evaluate on the next frame
 *
 * Veers are preferred over full-lock turns because they keep the car moving forward – the old
 * three-band policy could only spin in place when its (too wide) center read as blocked, which is
 * what caused the left/right bouncing. Corrections are obstacle-driven (a band being *close*),
 * never openness-difference wandering.
 *
 * All scores are nearness in 0..1 (higher = closer/more blocked), matching [ObstacleField].
 */
object ReflexPilot {

    /** Center-lane nearness below this is considered safe to drive straight into. */
    const val CLEAR_AHEAD = 0.45f

    /** An inner band at/above this nearness crowds the lane enough to veer away from it. */
    const val NUDGE_AWAY = 0.60f

    /** An inner band below this nearness is open enough to veer into when the center is blocked. */
    const val VEER_INTO = 0.50f

    /** An outer band at/above this nearness is too blocked to turn into. */
    const val SIDE_PASSABLE = 0.75f

    /** Below this center nearness the way is very open, so it's safe to pick up speed. */
    const val FAST_AHEAD = 0.20f

    /**
     * Turns one fused obstacle field into the next drive command. Deterministic and side-effect
     * free.
     */
    fun decide(field: ObstacleField): DriveCommand {
        val zones = field.zones
        val outerL = zones[0]
        val innerL = zones[1]
        val center = zones[2]
        val innerR = zones[3]
        val outerR = zones[4]

        return when {
            // Center lane clear: drive forward. Only correct course when an inner band is
            // actually crowding the lane (obstacle-driven), and veer away from it.
            center < CLEAR_AHEAD -> {
                val action =
                    when {
                        innerL >= NUDGE_AWAY && innerL > innerR -> DriveAction.VEER_RIGHT
                        innerR >= NUDGE_AWAY && innerR > innerL -> DriveAction.VEER_LEFT
                        else -> DriveAction.FORWARD
                    }
                DriveCommand(action, speedFor(center), reason = "lane clear")
            }
            // Center blocked but an inner band is open: veer into it – gentler than a full-lock
            // turn and keeps forward progress, which is what kills the bounce.
            innerL < VEER_INTO || innerR < VEER_INTO -> {
                if (innerR <= innerL) {
                    DriveCommand(
                        DriveAction.VEER_RIGHT,
                        DriveSpeed.SLOW,
                        reason = "lane blocked; slipping right",
                    )
                } else {
                    DriveCommand(
                        DriveAction.VEER_LEFT,
                        DriveSpeed.SLOW,
                        reason = "lane blocked; slipping left",
                    )
                }
            }
            // Center and inner bands blocked: full-lock turn toward the more open outer band.
            outerR <= outerL && outerR < SIDE_PASSABLE ->
                DriveCommand(
                    DriveAction.TURN_RIGHT,
                    DriveSpeed.SLOW,
                    reason = "blocked; turning right",
                )
            outerL < outerR && outerL < SIDE_PASSABLE ->
                DriveCommand(
                    DriveAction.TURN_LEFT,
                    DriveSpeed.SLOW,
                    reason = "blocked; turning left",
                )
            // Boxed in on every side: back up straight and re-evaluate next frame.
            else ->
                DriveCommand(DriveAction.REVERSE, DriveSpeed.SLOW, reason = "boxed in; backing up")
        }
    }

    private fun speedFor(centerNearness: Float): DriveSpeed =
        if (centerNearness < FAST_AHEAD) DriveSpeed.NORMAL else DriveSpeed.SLOW
}
