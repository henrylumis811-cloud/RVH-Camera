package com.rvh.camera.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Exposure/readout-aware OIS motion model for RAW fusion.
 *
 * OIS samples are timestamped in the sensor timebase.  A rolling-shutter frame does not expose
 * every row at exactly the same instant, so a single frame-level OIS score can be misleading:
 * motion can be quiet at the top of the frame and spike while lower rows are being exposed.
 *
 * This model deliberately does NOT geometrically warp the RAW from OIS data.  The sign and exact
 * optical-to-image mapping are device/lens dependent.  Instead it produces a conservative,
 * row-local confidence used by fusion.  Scene alignment remains image-derived and therefore
 * determines the actual spatial transform.
 */
class OisRollingShutterModel private constructor(
    private val rowScores: FloatArray,
    val available: Boolean,
    val exposureMotion: Float,
    val rollingMotion: Float,
) {
    fun scoreForRow(y: Int): Float {
        if (rowScores.isEmpty()) return 1f
        return rowScores[y.coerceIn(0, rowScores.lastIndex)]
    }

    companion object {
        fun from(metadata: FrameMetadata, height: Int): OisRollingShutterModel {
            require(height > 0)
            val xs = metadata.oisXShifts
            val ys = metadata.oisYShifts
            val ts = metadata.oisTimestampsNs
            if (!metadata.oisDataAvailable || xs == null || ys == null || ts == null ||
                xs.isEmpty() || xs.size != ys.size || xs.size != ts.size
            ) {
                return OisRollingShutterModel(FloatArray(height) { 1f }, false, 0f, 0f)
            }

            val samples = ArrayList<Sample>(xs.size)
            for (i in xs.indices) {
                val x = xs[i]
                val y = ys[i]
                val t = ts[i]
                if (x.isFinite() && y.isFinite() && t > 0L) {
                    samples += Sample(t, x, y)
                }
            }
            if (samples.size < 2) {
                return OisRollingShutterModel(FloatArray(height) { 1f }, false, 0f, 0f)
            }
            samples.sortBy { it.timestampNs }

            val exposureNs = metadata.exposureTimeNs?.coerceAtLeast(1L) ?: 1L
            val skewNs = metadata.rollingShutterSkewNs?.coerceAtLeast(0L) ?: 0L
            val frameStart = metadata.timestampNs
            val firstRowStart = frameStart
            // We interpolate only inside the measured OIS interval; outside it the nearest
            // sample is held. This avoids inventing motion beyond the sensor's telemetry.

            fun interpolate(t: Long): Pair<Float, Float> {
                if (t <= samples.first().timestampNs) return samples.first().x to samples.first().y
                if (t >= samples.last().timestampNs) return samples.last().x to samples.last().y
                var lo = 0
                var hi = samples.lastIndex
                while (lo + 1 < hi) {
                    val mid = (lo + hi) ushr 1
                    if (samples[mid].timestampNs <= t) lo = mid else hi = mid
                }
                val a = samples[lo]
                val b = samples[hi]
                val span = (b.timestampNs - a.timestampNs).coerceAtLeast(1L).toDouble()
                val f = ((t - a.timestampNs).toDouble() / span).toFloat().coerceIn(0f, 1f)
                return (a.x + (b.x - a.x) * f) to (a.y + (b.y - a.y) * f)
            }

            val rowScores = FloatArray(height)
            var exposureMotion = 0f
            var rollingMotion = 0f
            var previous = interpolate(firstRowStart)
            for (row in 0 until height) {
                val rowFraction = if (height <= 1) 0f else row.toFloat() / (height - 1).toFloat()
                val rowStart = firstRowStart + (skewNs * rowFraction).toLong()
                val rowEnd = rowStart + exposureNs
                val start = interpolate(rowStart)
                val end = interpolate(rowEnd)
                val mid = interpolate(rowStart + exposureNs / 2L)

                val withinExposure = hypot(end.first - start.first, end.second - start.second)
                val fromPreviousRow = hypot(mid.first - previous.first, mid.second - previous.second)
                previous = mid

                val local = withinExposure.coerceIn(0f, 12f)
                val rolling = fromPreviousRow.coerceIn(0f, 8f)
                exposureMotion = max(exposureMotion, local)
                rollingMotion = max(rollingMotion, rolling)

                // OIS is a camera-motion prior, not a scene-motion measurement. Keep the penalty
                // deliberately gentle so useful frames are not discarded merely because OIS is
                // active. Strong intra-exposure movement gets a progressively smaller fusion vote.
                val localPenalty = (local / 3.5f).coerceIn(0f, 1f)
                val rollingPenalty = (rolling / 2.5f).coerceIn(0f, 1f)
                rowScores[row] = (1f - 0.65f * localPenalty - 0.20f * rollingPenalty)
                    .coerceIn(0.15f, 1f)
            }

            // If the reported rolling skew is absent/zero, all rows describe effectively the same
            // exposure interval. Preserve a frame-wide stability score instead of manufacturing a
            // vertical motion gradient from timestamp interpolation.
            if (skewNs == 0L) {
                val framePenalty = (exposureMotion / 3.5f).coerceIn(0f, 1f)
                val score = (1f - 0.65f * framePenalty).coerceIn(0.15f, 1f)
                rowScores.fill(score)
                rollingMotion = 0f
            }

            return OisRollingShutterModel(rowScores, true, exposureMotion, rollingMotion)
        }

        private data class Sample(
            val timestampNs: Long,
            val x: Float,
            val y: Float,
        )
    }
}
