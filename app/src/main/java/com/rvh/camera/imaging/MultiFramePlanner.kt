package com.rvh.camera.imaging

/**
 * Selects a small, high-value subset of frames for fusion. It intentionally does not perform
 * fusion itself; alignment and fusion remain separate stages so each can be benchmarked.
 */
class MultiFramePlanner(
    private val maximumFrames: Int = 3,
) {
    init {
        require(maximumFrames >= 1)
    }

    fun select(frames: List<Yuv420Frame>): List<Yuv420Frame> {
        if (frames.isEmpty()) return emptyList()
        if (frames.size <= maximumFrames) return frames

        return frames
            .asSequence()
            .map { frame -> frame to FrameQualityScorer.score(frame) }
            .sortedByDescending { (_, quality) -> quality.overall }
            .take(maximumFrames)
            .map { (frame, _) -> frame }
            .toList()
    }
}
