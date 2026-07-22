package com.resourcefork.rccontrol

/**
 * The car's fused, single picture of "what's in front of me": all four forward sources – the
 * camera's five monocular-depth bands ([CorridorReport]) plus the three measured sensors
 * ([DistanceReport]: center ToF beam and the two corner ultrasonics) – aggregated into
 * [SEGMENT_COUNT] segments spanning the forward arc, leftmost first.
 *
 * Each segment is 0..1 *blockedness* (0 = open, 1 = obstacle at the bumper), the same vocabulary as
 * [CorridorReport].
 *
 * Fusion is anchor + interpolate, so the result reads as a heatmap of viable/inviable directions:
 * - Each source contributes a **concrete anchor** at the segment its beam/band actually points at
 *   (camera bands at their band-center segments; sensors at [ANCHOR_LEFT] / [ANCHOR_CENTER] /
 *   [ANCHOR_RIGHT]). Anchors landing on the same segment merge via **max** – pessimistic on
 *   purpose, so a measured sensor hardens the camera's inference rather than being averaged away.
 * - Segments without concrete data are **linearly interpolated** between their two surrounding
 *   anchors, weighted toward the nearer one (two empty segments between two anchors each lean
 *   toward their closer neighbor). Segments outside the outermost anchors clamp to the nearest
 *   anchor's value – no extrapolation.
 *
 * Anchor layout across the 13 segments:
 * ```
 * segment:        0   1   2   3   4   5   6   7   8   9  10  11  12
 * camera bands:       b0          b1     b2/tof      b3          b4
 * left SR04:               FL
 * right SR04:                                              FR
 * ```
 *
 * Stale or absent inputs contribute no anchors (freshness-gated). When *nothing* fresh remains,
 * [fuse] returns null and callers must treat the field as unknown, not clear.
 *
 * This type is deliberately UI-agnostic: the preview's obstacle bar renders [segments], and the
 * choosing layer ([ReflexPilot], the forward veto, the VLM hint) reads [zones].
 */
data class ObstacleField(val segments: List<Float>) {
    init {
        require(segments.size == SEGMENT_COUNT) {
            "expected $SEGMENT_COUNT segments, got ${segments.size}"
        }
    }

    /**
     * The field folded down to [ZONE_COUNT] steering zones – `[outer-left, inner-left, center,
     * inner-right, outer-right]` – the resolution the drive-action vocabulary actually steers at.
     * Each zone is the pessimistic **max** of the segments it covers, so nothing a segment saw can
     * hide inside a zone average. This is what the choosing layer reads; the full
     * [SEGMENT_COUNT]-segment resolution stays available for display.
     */
    val zones: List<Float>
        get() =
            List(ZONE_COUNT) { zone ->
                var worst = 0f
                for (s in 0 until SEGMENT_COUNT) {
                    if ((s * ZONE_COUNT) / SEGMENT_COUNT == zone && segments[s] > worst) {
                        worst = segments[s]
                    }
                }
                worst
            }

    /** Blockedness of the center zone – the car's actual driving lane. */
    val center: Float
        get() = zones[ZONE_COUNT / 2]

    /** True when the driving lane reads as blocked – the forward-veto trigger. */
    val centerBlocked: Boolean
        get() = center >= BLOCK_THRESHOLD

    companion object {
        /** Number of fused segments across the forward arc. Odd, so a true center exists. */
        const val SEGMENT_COUNT = 13

        /** Number of steering zones the field folds down to for decisions. */
        const val ZONE_COUNT = 5

        /**
         * Center-zone blockedness at/above this vetoes forward motion. Same value the camera-only
         * veto used ([CorridorReport.BLOCK_THRESHOLD]).
         */
        const val BLOCK_THRESHOLD = 0.65f

        /**
         * Ranges used to convert measured millimeters into 0..1 blockedness: value = 1 - mm/range.
         * "At the far edge of what the sensor can see" reads ~0 and "touching the bumper" reads 1,
         * lining up with the camera bands' semantics.
         */
        const val TOF_RANGE_MM = 1200
        const val SR04_RANGE_MM = 2000

        /**
         * Anchor segments for the measured sensors – where each beam's center actually points.
         * Public so the UI can position each sensor's numeric readout over its own segment.
         */
        const val ANCHOR_LEFT = 2
        const val ANCHOR_CENTER = 6
        const val ANCHOR_RIGHT = 10

        /** Anchor segment for each camera band (band-center under the 13→5 zone fold). */
        private val CAMERA_ANCHORS = intArrayOf(1, 4, 6, 9, 11)

        /**
         * Fuses the camera's depth bands and the measured distances into one field. Either input
         * may be null or stale; it then contributes no anchors. Returns null when no anchors remain
         * (nothing fresh is sensing – unknown, not clear).
         */
        fun fuse(
            corridors: CorridorReport?,
            distances: DistanceReport?,
            nowMs: Long = System.currentTimeMillis(),
        ): ObstacleField? {
            val anchors = sortedMapOf<Int, Float>() // segment -> blockedness, max-merged
            fun addAnchor(segment: Int, value: Float) {
                val existing = anchors[segment]
                if (existing == null || value > existing) anchors[segment] = value
            }

            corridors
                ?.takeIf { it.isFresh(nowMs) }
                ?.bands
                ?.forEachIndexed { band, value -> addAnchor(CAMERA_ANCHORS[band], value) }

            distances
                ?.takeIf { it.isFresh(nowMs) }
                ?.let { d ->
                    fun blockedness(mm: Int, rangeMm: Int) =
                        (1f - mm.toFloat() / rangeMm).coerceIn(0f, 1f)
                    d.frontLeftMm?.let { addAnchor(ANCHOR_LEFT, blockedness(it, SR04_RANGE_MM)) }
                    d.centerMm?.let { addAnchor(ANCHOR_CENTER, blockedness(it, TOF_RANGE_MM)) }
                    d.frontRightMm?.let { addAnchor(ANCHOR_RIGHT, blockedness(it, SR04_RANGE_MM)) }
                }

            if (anchors.isEmpty()) return null

            // Interpolate the gaps: each empty segment is a blend of its two surrounding anchors,
            // weighted toward the nearer one; segments beyond the outermost anchors clamp.
            val positions = anchors.keys.toList()
            val segments =
                List(SEGMENT_COUNT) { s ->
                    val left = positions.lastOrNull { it <= s }
                    val right = positions.firstOrNull { it >= s }
                    when {
                        left == null && right == null -> 0f // unreachable: anchors is non-empty
                        left == null -> anchors[right]!!
                        right == null -> anchors[left]!!
                        left == right -> anchors[left]!!
                        else -> {
                            val span = (right - left).toFloat()
                            val towardRight = (s - left) / span
                            anchors[left]!! * (1f - towardRight) + anchors[right]!! * towardRight
                        }
                    }
                }

            return ObstacleField(segments)
        }
    }
}
