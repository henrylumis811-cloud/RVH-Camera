package com.rvh.camera.camera

import com.rvh.camera.imaging.CaptureIntent
import com.rvh.camera.imaging.CapturePlan
import com.rvh.camera.imaging.FrameMetadata

/**
 * Lifecycle-safe state machine for computational still capture.
 *
 * This class intentionally knows nothing about Camera2 Surfaces. CameraController owns the
 * hardware requests; this object owns sequencing, cancellation and completion semantics.
 */
class CaptureSequence(
    private val sequenceId: Long,
    private val plan: CapturePlan,
) {
    enum class State {
        CREATED,
        CAPTURING,
        COMPLETE,
        CANCELLED,
        FAILED,
    }

    data class CapturedFrame(
        val bytes: ByteArray,
        val metadata: FrameMetadata,
    )

    @Volatile
    var state: State = State.CREATED
        private set

    private val frames = ArrayList<CapturedFrame>(plan.frameCount)

    fun start(): Boolean {
        if (state != State.CREATED) return false
        state = State.CAPTURING
        return true
    }

    /** Adds one frame in arrival order. Returns false when the sequence cannot accept it. */
    @Synchronized
    fun addFrame(frame: CapturedFrame): Boolean {
        if (state != State.CAPTURING) return false
        if (frames.size >= plan.frameCount) return false
        frames += frame
        if (frames.size == plan.frameCount) {
            state = State.COMPLETE
        }
        return true
    }

    /** Records completion for a YUV frame without allocating a duplicate byte payload. */
    @Synchronized
    fun addYuvFrame(metadata: FrameMetadata): Boolean {
        if (state != State.CAPTURING) return false
        if (frames.size >= plan.frameCount) return false
        frames += CapturedFrame(ByteArray(0), metadata)
        if (frames.size == plan.frameCount) state = State.COMPLETE
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        if (state == State.COMPLETE || state == State.CANCELLED || state == State.FAILED) return false
        state = State.CANCELLED
        return true
    }

    @Synchronized
    fun fail(): Boolean {
        if (state == State.COMPLETE || state == State.CANCELLED || state == State.FAILED) return false
        state = State.FAILED
        return true
    }

    @Synchronized
    fun snapshot(): List<CapturedFrame> = frames.toList()

    fun remainingFrames(): Int = (plan.frameCount - frames.size).coerceAtLeast(0)

    fun isComplete(): Boolean = state == State.COMPLETE

    fun sequenceId(): Long = sequenceId

    fun intent(): CaptureIntent = plan.intent

    fun plan(): CapturePlan = plan
}

/**
 * Explicit sequence bookkeeping kept separate from the Camera2 request machinery.
 */
class CaptureSequenceManager {
    private var nextId = 0L
    private var active: CaptureSequence? = null

    @Synchronized
    fun begin(plan: CapturePlan): CaptureSequence? {
        if (active?.state == CaptureSequence.State.CAPTURING) return null
        val sequence = CaptureSequence(++nextId, plan)
        check(sequence.start())
        active = sequence
        return sequence
    }

    @Synchronized
    fun active(): CaptureSequence? = active

    @Synchronized
    fun finish(sequence: CaptureSequence) {
        if (active?.sequenceId() == sequence.sequenceId()) active = null
    }

    @Synchronized
    fun cancelActive(): Boolean {
        val sequence = active ?: return false
        val changed = sequence.cancel()
        if (changed) active = null
        return changed
    }

    @Synchronized
    fun clear() {
        active?.cancel()
        active = null
    }
}
