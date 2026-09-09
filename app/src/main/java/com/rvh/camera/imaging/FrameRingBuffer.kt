package com.rvh.camera.imaging

import java.util.ArrayDeque

/**
 * Bounded frame storage for multi-frame photography.
 *
 * Both count and byte budget are enforced so a high-resolution sensor cannot silently turn
 * a small burst into an unbounded heap allocation on budget hardware.
 */
class FrameRingBuffer(
    private val maxFrames: Int = 2,
    private val maxBytes: Long = 48L * 1024L * 1024L,
) {
    private val frames = ArrayDeque<Yuv420Frame>()
    private var bytes: Long = 0L

    init {
        require(maxFrames > 0)
        require(maxBytes > 0)
    }

    @Synchronized
    fun offer(frame: Yuv420Frame): Boolean {
        val size = frame.byteCount()
        if (size > maxBytes) {
            frame.close()
            return false
        }

        while (frames.size >= maxFrames || bytes + size > maxBytes) {
            if (frames.isEmpty()) break
            val removed = frames.removeFirst()
            bytes -= removed.byteCount()
            removed.close()
        }

        frames.addLast(frame)
        bytes += size
        return true
    }

    @Synchronized
    fun snapshot(): List<Yuv420Frame> = frames.toList()

    @Synchronized
    fun clear() {
        while (frames.isNotEmpty()) {
            frames.removeFirst().close()
        }
        bytes = 0L
    }

    @Synchronized
    fun size(): Int = frames.size

    @Synchronized
    fun byteCount(): Long = bytes
}
