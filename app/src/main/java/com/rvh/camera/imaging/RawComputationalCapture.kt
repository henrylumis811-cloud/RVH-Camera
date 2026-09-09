package com.rvh.camera.imaging


/**
 * RAW capture bridge. Keeps RAW-domain fusion separate from the established YUV pipeline.
 * This is deliberately capability-gated: callers should only use it when RAW frames are valid.
 */
class RawComputationalCapture(
    private val development: RawDevelopmentPipeline = RawDevelopmentPipeline(),
    private val fusion: RawMultiFrameFusion = RawMultiFrameFusion(),
    private val rawNoiseReducer: RawAdaptiveNoiseReducer = RawAdaptiveNoiseReducer(),
    private val bayerHighlightRecovery: RawBayerHighlightRecovery = RawBayerHighlightRecovery(),
    private val detailRecovery: RawDetailRecovery = RawDetailRecovery(),
    private val chromaNoiseReducer: RawChromaNoiseReducer = RawChromaNoiseReducer(),
    private val highlightReconstructor: RawHighlightReconstructor = RawHighlightReconstructor(),
    private val colorAppearance: RawColorAppearance = RawColorAppearance(),
    private val skinToneProtection: RawSkinToneProtection = RawSkinToneProtection(),
    private val lensOpticalCorrection: RawLensOpticalCorrection = RawLensOpticalCorrection(),
    private val chromaticFringeReducer: RawChromaticFringeReducer = RawChromaticFringeReducer(),
    private val falseColorReducer: RawFalseColorReducer = RawFalseColorReducer(),
    private val localToneMapper: RawLocalToneMapper = RawLocalToneMapper(),
    private val toneMapper: RawToneMapper = RawToneMapper(),
) {
    fun process(frames: List<RawSensorFrame>): RawDevelopmentPipeline.LinearRgb? {
        val mosaics = frames.map { development.normalize(it) }
        val fused = fusion.fuse(mosaics.map { RawMultiFrameFusion.Input(it) }) ?: return null
        val noiseClean = rawNoiseReducer.apply(fused.mosaic)
        val highlightRecoveredMosaic = bayerHighlightRecovery.apply(noiseClean)
        val detailRecovered = detailRecovery.apply(highlightRecoveredMosaic)
        val developed = development.demosaic(detailRecovered)
        val opticallyCorrected = lensOpticalCorrection.apply(developed)
        val fringeClean = chromaticFringeReducer.apply(opticallyCorrected)
        val falseColorClean = falseColorReducer.apply(fringeClean)
        val chromaClean = chromaNoiseReducer.apply(falseColorClean)
        val highlightRecovered = highlightReconstructor.apply(chromaClean)
        val locallyMapped = localToneMapper.apply(highlightRecovered)
        val colorFinished = colorAppearance.apply(locallyMapped)
        val skinProtected = skinToneProtection.apply(locallyMapped, colorFinished)
        return toneMapper.apply(skinProtected)
    }
}
