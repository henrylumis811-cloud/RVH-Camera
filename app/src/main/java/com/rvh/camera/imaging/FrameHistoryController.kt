package com.rvh.camera.imaging

import java.util.ArrayDeque
import kotlin.math.max

/**
 * Adaptive high-resolution frame history for computational photography.
 *
 * The history is intentionally bounded by both frame count and bytes. It is not a permanent
 * high-resolution stream requirement: callers may keep it disabled on devices where the camera
 * topology or memory budget cannot justify the cost.
 */
class FrameHistoryController(
    private val policy: HistoryPolicy = HistoryPolicy.adaptive(),
) {
    private data class Entry(
        val frame: Yuv420Frame,
        val quality: Float,
        val offeredAtNs: Long,
        val sceneMeanLuma: Float?,
        val sceneSpatialLuma: FloatArray?,
    )

    private val entries = ArrayDeque<Entry>()
    private var bytes = 0L
    @Volatile private var latestSelectionIso: Int? = null

    @Synchronized
    fun offer(
        frame: Yuv420Frame,
        quality: Float = 0f,
        nowNs: Long = frame.metadata.timestampNs,
        sceneMeanLuma: Float? = null,
        sceneSpatialLuma: FloatArray? = null,
    ): Boolean {
        val size = frame.byteCount()
        if (size > policy.maxBytes || policy.maxFrames <= 0) {
            frame.close()
            return false
        }

        evictExpired(nowNs)

        // Prefer retaining a newer, higher-quality frame. A history frame is an acquisition aid,
        // not a second copy of the whole camera stream, so weak frames should not crowd it out.
        if (entries.size >= policy.maxFrames || bytes + size > policy.maxBytes) {
            val weakest = entries.minWithOrNull(compareBy<Entry> { it.quality }.thenBy { it.offeredAtNs })
            if (weakest != null && (quality > weakest.quality + policy.minimumQualityAdvantage ||
                    nowNs > weakest.offeredAtNs)) {
                remove(weakest)
            } else {
                frame.close()
                return false
            }
        }

        entries.addLast(Entry(frame, quality.coerceIn(0f, 1f), nowNs, sceneMeanLuma, sceneSpatialLuma?.copyOf()))
        bytes += size
        return true
    }

    /**
     * Returns a chronological snapshot, newest first only when explicitly requested by callers.
     * The returned frames remain owned by this history until [clear] or eviction.
     */
    @Synchronized
    fun snapshot(): List<Yuv420Frame> = entries.map { it.frame }

    /** Selects frames closest to the shutter while favoring quality and temporal proximity. */
    fun setSelectionReferenceIso(iso: Int?) {
        latestSelectionIso = iso
    }

    @Synchronized
    fun selectForCapture(
        shutterTimestampNs: Long,
        count: Int,
        maxAgeNs: Long = policy.maxAgeNs,
        currentSceneMeanLuma: Float? = null,
        maxSceneLumaDelta: Float = 28f,
        maxExposureNs: Long? = null,
        maxIsoRatio: Float = 3.5f,
        currentSceneSpatialLuma: FloatArray? = null,
        maxSceneSpatialDifference: Float = 0.18f,
    ): List<Yuv420Frame> {
        if (count <= 0) return emptyList()
        evictExpired(shutterTimestampNs)

        return entries
            .asSequence()
            .filter { kotlin.math.abs(shutterTimestampNs - it.frame.metadata.timestampNs) <= maxAgeNs }
            .filter { entry ->
                val sceneOk = currentSceneMeanLuma == null || entry.sceneMeanLuma == null ||
                    kotlin.math.abs(currentSceneMeanLuma - entry.sceneMeanLuma) <= maxSceneLumaDelta
                val spatialOk = currentSceneSpatialLuma == null || entry.sceneSpatialLuma == null ||
                    spatialDifference(currentSceneSpatialLuma, entry.sceneSpatialLuma) <= maxSceneSpatialDifference
                val exposureOk = maxExposureNs == null ||
                    (entry.frame.metadata.exposureTimeNs ?: 0L) <= maxExposureNs
                val isoOk = if (maxIsoRatio <= 1f) true else {
                    val iso = entry.frame.metadata.sensitivityIso
                    val currentIso = latestSelectionIso
                    iso == null || currentIso == null ||
                        maxOf(iso, currentIso).toFloat() / minOf(iso, currentIso).coerceAtLeast(1).toFloat() <= maxIsoRatio
                }
                sceneOk && spatialOk && exposureOk && isoOk
            }
            .sortedWith(
                compareBy<Entry> { kotlin.math.abs(shutterTimestampNs - it.frame.metadata.timestampNs) }
                    .thenByDescending { it.quality }
            )
            .take(count)
            .map { it.frame }
            .toList()
    }

    private fun spatialDifference(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 1f
        var sum = 0f
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return (sum / a.size / 255f).coerceIn(0f, 1f)
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun byteCount(): Long = bytes

    @Synchronized
    fun clear() {
        while (entries.isNotEmpty()) remove(entries.removeFirst(), alreadyRemoved = true)
    }

    @Synchronized
    private fun evictExpired(nowNs: Long) {
        if (policy.maxAgeNs <= 0L) return
        val cutoff = nowNs - policy.maxAgeNs
        val iterator = entries.iterator()
        val expired = ArrayList<Entry>()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.frame.metadata.timestampNs < cutoff) {
                expired += entry
                iterator.remove()
                bytes -= entry.frame.byteCount()
            }
        }
        expired.forEach { it.frame.close() }
    }

    private fun remove(entry: Entry, alreadyRemoved: Boolean = false) {
        if (!alreadyRemoved) entries.remove(entry)
        bytes -= entry.frame.byteCount()
        entry.frame.close()
    }

    companion object {
        /**
         * Conservative budget for phones with limited heaps. This deliberately leaves headroom
         * for Camera2 buffers, Compose/UI, JPEG encoding and the processing pipeline.
         */
        fun adaptiveRuntimePolicy(
            maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
        ): HistoryPolicy {
            val memory = max(1L, maxMemoryBytes)
            val budget = when {
                memory < 192L * 1024L * 1024L -> 12L * 1024L * 1024L
                memory < 256L * 1024L * 1024L -> 18L * 1024L * 1024L
                memory < 384L * 1024L * 1024L -> 24L * 1024L * 1024L
                else -> 36L * 1024L * 1024L
            }
            return HistoryPolicy(
                maxFrames = when {
                    budget < 18L * 1024L * 1024L -> 1
                    budget < 30L * 1024L * 1024L -> 2
                    else -> 3
                },
                maxBytes = budget,
                maxAgeNs = 650_000_000L,
                minimumQualityAdvantage = 0.08f,
            )
        }
    }
}

data class HistoryPolicy(
    val maxFrames: Int,
    val maxBytes: Long,
    val maxAgeNs: Long,
    val minimumQualityAdvantage: Float = 0.08f,
) {
    init {
        require(maxFrames >= 1)
        require(maxBytes > 0)
        require(maxAgeNs > 0)
        require(minimumQualityAdvantage >= 0f)
    }

    companion object {
        fun adaptive(): HistoryPolicy = FrameHistoryController.adaptiveRuntimePolicy()
    }
}
