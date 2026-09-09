package com.rvh.camera.imaging

/**
 * First complete computational-photo processing boundary.
 *
 * Capture/session code can feed this pipeline once the device-specific YUV capture path is enabled.
 * It deliberately remains independent from CameraController while the capture path is validated.
 */
class ComputationalPhotoPipeline(
    private val planner: MultiFramePlanner = MultiFramePlanner(),
    private val fusion: FrameFusion = FrameFusion(),
    private val hdrFusion: HdrFusion = HdrFusion(),
    private val denoise: AdaptiveDenoise = AdaptiveDenoise(),
    private val detailRecovery: DetailRecovery = DetailRecovery(),
    private val multiFrameDetailRecovery: MultiFrameDetailRecovery = MultiFrameDetailRecovery(),
    private val toneMapper: ToneMapper = ToneMapper(),
    private val localToneMapper: LocalToneMapper = LocalToneMapper(),
    private val colorScience: ColorScience = ColorScience(),
    private val qualityGate: ProcessingQualityGate = ProcessingQualityGate(),
) {
    data class Result(
        val frame: Yuv420Frame,
        val contributingFrames: Int,
        val rejectedFrames: Int,
    ) {
        fun jpegBytes(): ByteArray = YuvJpegEncoder().encode(frame)
    }

    fun process(
        frames: List<Yuv420Frame>,
        request: ProcessingRequest = ProcessingRequest(allowMultiFrame = true),
    ): Result {
        require(frames.isNotEmpty()) { "At least one frame is required" }

        val selected = if (request.intent == CaptureIntent.HDR) frames else planner.select(frames)
        val reference = selected.first()
        val candidates = selected.drop(1)

        val fused = when {
            request.intent == CaptureIntent.HDR && frames.size > 1 -> hdrFusion.fuse(frames)
            request.allowMultiFrame && candidates.isNotEmpty() -> fusion.fuse(reference, candidates, request)
            else -> FusionResult(reference, contributingFrames = 1, rejectedFrames = 0)
        }

        val denoised = denoise.apply(fused.frame, request.intent, fused.contributingFrames)
        val detailedBase = detailRecovery.apply(denoised)
        val detailed = if (request.allowMultiFrame && candidates.isNotEmpty() &&
            request.intent != CaptureIntent.HDR
        ) {
            multiFrameDetailRecovery.apply(detailedBase, reference, candidates)
        } else {
            detailedBase
        }
        val toned = toneMapper.apply(detailed, request.intent)
        val locallyToned = localToneMapper.apply(toned, request.intent)
        val colored = colorScience.apply(locallyToned, request.intent)
        val rotation = colored.metadata.rotationDegrees
        val oriented = YuvRotation.apply(colored, rotation)

        // Compare against the original reference before committing the computational result.
        // A computational shot is only an improvement if it does not introduce a strong global
        // regression. This is intentionally a safety gate, not an aesthetic scoring system.
        val referenceOriented = YuvRotation.apply(reference, reference.metadata.rotationDegrees)
        val decision = qualityGate.evaluate(referenceOriented, oriented, request.intent)
        val finalFrame = if (decision.acceptProcessed) oriented else referenceOriented
        return Result(
            frame = finalFrame,
            contributingFrames = fused.contributingFrames,
            rejectedFrames = fused.rejectedFrames,
        )
    }
}
