package com.resourcefork.rccontrol

/**
 * One snapshot of the car's *measured* forward clearances, as reported by the Arduino's "D?"
 * command: `D:<center>,<frontLeft>,<frontRight>` with each value in millimeters and -1 meaning "no
 * reading" (sensor absent, out of range, or no echo).
 *
 * The three sensors form a forward-perception array mirroring the camera's corridor bands:
 * ```
 *   frontLeftMm      centerMm      frontRightMm
 *   (HC-SR04,        (VL53L4CD     (HC-SR04,
 *    wide cone,       ToF, narrow    wide cone,
 *    left corner)     center beam)   right corner)
 * ```
 *
 * These are true time-of-flight / ultrasonic measurements, complementing the camera's *relative*
 * monocular depth with absolute millimeters. There is deliberately no rear sensor: reversing is
 * only used to back out along ground the car has already covered.
 *
 * @param centerMm VL53L4CD ToF, straight ahead (~0–1200mm). Null = no reading.
 * @param frontLeftMm Front-left corner HC-SR04 (~20–2000mm). Null = no reading.
 * @param frontRightMm Front-right corner HC-SR04 (~20–2000mm). Null = no reading.
 */
data class DistanceReport(
    val centerMm: Int?,
    val frontLeftMm: Int?,
    val frontRightMm: Int?,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    /** All three readings, ordered left → center → right (matching the corridor overlay). */
    val readings: List<Int?>
        get() = listOf(frontLeftMm, centerMm, frontRightMm)

    /**
     * The closest measured obstacle anywhere in the forward arc, or null when no sensor has a
     * reading. This is the single number to check before advancing.
     */
    val nearestFrontMm: Int?
        get() = readings.filterNotNull().minOrNull()

    /**
     * True when every reporting sensor sees at least [thresholdMm] of clearance. Conservative on
     * missing data: if *no* sensor is reporting, the front is not considered clear.
     */
    fun isFrontClear(thresholdMm: Int): Boolean = nearestFrontMm?.let { it >= thresholdMm } == true

    /** True while the report is recent enough to act on. */
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs - timestampMs <= FRESH_MS

    companion object {
        /** Reports older than this are ignored (poller or link may have stalled). */
        const val FRESH_MS = 1500L

        /**
         * Parses "D:<center>,<frontLeft>,<frontRight>" (mm, -1 = no reading). Null if malformed.
         */
        fun parse(line: String): DistanceReport? {
            if (!line.startsWith("D:")) return null
            val parts = line.substring(2).split(",")
            if (parts.size != 3) return null
            return try {
                fun mm(s: String): Int? = s.trim().toInt().takeIf { it >= 0 }
                DistanceReport(
                    centerMm = mm(parts[0]),
                    frontLeftMm = mm(parts[1]),
                    frontRightMm = mm(parts[2]),
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/**
 * Median-of-3 spike filter for [DistanceReport] streams.
 *
 * HC-SR04 readings are noisy in a specific way: occasional single-sample ghosts (an absurdly short
 * reading from a stray echo) and single-sample dropouts (-1 when an echo is missed). A per-channel
 * median over the last three raw samples removes exactly those one-off outliers while adding only
 * ~one poll cycle of latency; real obstacles persist across samples and pass straight through.
 *
 * Feed raw reports in poll order via [smooth]; it returns the filtered report to publish. Not
 * thread-safe – call from the single polling coroutine.
 */
class DistanceSmoother {
    private val history = ArrayDeque<DistanceReport>()

    fun smooth(raw: DistanceReport): DistanceReport {
        history.addLast(raw)
        if (history.size > WINDOW) history.removeFirst()
        return DistanceReport(
            centerMm = medianOf { it.centerMm },
            frontLeftMm = medianOf { it.frontLeftMm },
            frontRightMm = medianOf { it.frontRightMm },
            timestampMs = raw.timestampMs,
        )
    }

    /** Clears history, e.g. on reconnect, so stale samples can't bleed into a new session. */
    fun reset() = history.clear()

    /**
     * Median of the channel's non-null values in the window; null when the channel has produced no
     * reading in the whole window (a persistently absent sensor stays absent).
     */
    private fun medianOf(channel: (DistanceReport) -> Int?): Int? {
        val values = history.mapNotNull(channel).sorted()
        if (values.isEmpty()) return null
        return values[values.size / 2]
    }

    private companion object {
        const val WINDOW = 3
    }
}
