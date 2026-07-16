package com.resourcefork.rccontrol

/**
 * Pure reactive obstacle-avoidance policy: maps a [CorridorReport] (the fast on-device depth
 * layer) directly to a [DriveCommand], with no VLM in the loop.
 *
 * This is the geometry layer acting as a *driver* in its own right, not just the safety veto in
 * [RCViewModel.dispatchDriveCommand]. Because it runs at the depth frame rate (a few Hz) it reacts
 * far faster than a VLM pilot step, which makes it the cheapest way to watch the full
 * perception -> decision -> actuation chain move (e.g. against the mock receiver, with no car).
 *
 * The policy is deliberately simple and stateless – a classic reactive navigator:
 * - center clear            -> drive forward, nudging toward the more open side to stay centered
 * - center blocked, side ok -> turn (full lock) toward the more open side
 * - boxed in on all sides   -> back up straight, then re-evaluate on the next frame
 *
 * All scores are nearness in 0..1 (higher = closer/more blocked), matching [CorridorReport].
 */
object ReflexPilot {

    /** Center nearness below this is considered safe to drive straight into. */
    const val CLEAR_AHEAD = 0.45f

    /** Left/right openness must differ by at least this before we bother correcting course. */
    const val VEER_BIAS = 0.15f

    /** A side corridor at/above this nearness is too blocked to turn into. */
    const val SIDE_PASSABLE = 0.75f

    /** Below this center nearness the way is very open, so it's safe to pick up speed. */
    const val FAST_AHEAD = 0.20f

    /** Turns one corridor reading into the next drive command. Deterministic and side-effect free. */
    fun decide(report: CorridorReport): DriveCommand {
        val nearL = report.left
        val nearC = report.center
        val nearR = report.right
        val openL = 1f - nearL
        val openR = 1f - nearR
        val rightMoreOpen = openR >= openL

        return when {
            // Clear path ahead: drive forward, nudging toward the more open side to stay centered.
            nearC < CLEAR_AHEAD -> {
                val bias = openR - openL // > 0 => right is more open
                val action =
                    when {
                        bias > VEER_BIAS -> DriveAction.VEER_RIGHT
                        bias < -VEER_BIAS -> DriveAction.VEER_LEFT
                        else -> DriveAction.FORWARD
                    }
                DriveCommand(action, speedFor(nearC), reason = "path ahead clear")
            }
            // Center blocked but a side is passable: turn (full lock) toward the more open side.
            rightMoreOpen && nearR < SIDE_PASSABLE ->
                DriveCommand(
                    DriveAction.TURN_RIGHT,
                    DriveSpeed.SLOW,
                    reason = "center blocked; right is clearer",
                )
            !rightMoreOpen && nearL < SIDE_PASSABLE ->
                DriveCommand(
                    DriveAction.TURN_LEFT,
                    DriveSpeed.SLOW,
                    reason = "center blocked; left is clearer",
                )
            // Boxed in on every side: back up straight and re-evaluate next frame.
            else -> DriveCommand(DriveAction.REVERSE, DriveSpeed.SLOW, reason = "boxed in; backing up")
        }
    }

    private fun speedFor(centerNearness: Float): DriveSpeed =
        if (centerNearness < FAST_AHEAD) DriveSpeed.NORMAL else DriveSpeed.SLOW
}
