package com.rvh.camera.camera

/**
 * Conservative runtime tier derived from Camera2 capabilities and the application's memory budget.
 * It describes what the imaging engine should attempt, not what the HAL merely advertises.
 */
data class CameraHardwareProfile(
    val tier: Tier,
    val maxComputationalFrames: Int,
    val highResolutionHistoryFrames: Int,
    val highResolutionHistoryBytes: Long,
    val supportsReprocessing: Boolean,
    val supportsBurst: Boolean,
    val supportsManualSensor: Boolean,
    val isoRange: IntRange?,
    val exposureTimeRangeNs: LongRange?,
    val supportsAeCompensation: Boolean,
    val aeCompensationRange: IntRange?,
    val aeCompensationStep: Float?,
    val supportsMaximumResolutionYuv: Boolean,
    val supportsMaximumResolutionJpeg: Boolean,
    val hasVendorNight: Boolean,
    val hasVendorHdr: Boolean,
    val estimatedCaptureStallNs: Long?,
    val pipelineDepth: Int?,
) {
    enum class Tier { CONSERVATIVE, BALANCED, CAPABLE }

    fun summary(): String = buildString {
        appendLine("tier=$tier")
        appendLine("maxComputationalFrames=$maxComputationalFrames")
        appendLine("highResolutionHistoryFrames=$highResolutionHistoryFrames")
        appendLine("highResolutionHistoryBytes=$highResolutionHistoryBytes")
        appendLine("supportsReprocessing=$supportsReprocessing")
        appendLine("canAttemptTrueZsl=${ReprocessingPolicy.canAttemptTrueZsl(this@CameraHardwareProfile)}")
        appendLine("supportsBurst=$supportsBurst")
        appendLine("supportsManualSensor=$supportsManualSensor")
        appendLine("isoRange=$isoRange")
        appendLine("exposureTimeRangeNs=$exposureTimeRangeNs")
        appendLine("supportsAeCompensation=$supportsAeCompensation")
        appendLine("aeCompensationRange=$aeCompensationRange")
        appendLine("aeCompensationStep=$aeCompensationStep")
        appendLine("supportsMaximumResolutionYuv=$supportsMaximumResolutionYuv")
        appendLine("supportsMaximumResolutionJpeg=$supportsMaximumResolutionJpeg")
        appendLine("hasVendorNight=$hasVendorNight")
        appendLine("hasVendorHdr=$hasVendorHdr")
        appendLine("estimatedCaptureStallNs=$estimatedCaptureStallNs")
        appendLine("pipelineDepth=$pipelineDepth")
    }

    companion object {
        fun from(
            capabilities: CameraCapabilities,
            maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
        ): CameraHardwareProfile {
            val reprocessing = capabilities.yuvReprocessingSupported || capabilities.privateReprocessingSupported
            val burst = capabilities.availableCapabilities.contains(
                android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE
            )
            val maxYuv = capabilities.maximumResolutionYuvSizes.isNotEmpty()
            val maxJpeg = capabilities.maximumResolutionJpegSizes.isNotEmpty()
            val vendorNight = capabilities.supportedExtensions.contains(
                android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT
            )
            val vendorHdr = capabilities.supportedExtensions.contains(
                android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_HDR
            )

            val memoryTier = when {
                maxMemoryBytes < 192L * 1024L * 1024L -> Tier.CONSERVATIVE
                maxMemoryBytes < 384L * 1024L * 1024L -> Tier.BALANCED
                else -> Tier.CAPABLE
            }

            val capabilityScore = listOf(
                reprocessing,
                burst,
                capabilities.manualSensorSupported,
                maxYuv,
                capabilities.oisModes.any { it != 0 },
            ).count { it }

            val tier = when {
                memoryTier == Tier.CONSERVATIVE || capabilityScore <= 1 -> Tier.CONSERVATIVE
                memoryTier == Tier.CAPABLE && capabilityScore >= 4 -> Tier.CAPABLE
                else -> Tier.BALANCED
            }

            val frames = when (tier) {
                Tier.CONSERVATIVE -> 2
                Tier.BALANCED -> 3
                Tier.CAPABLE -> 3
            }
            val historyBytes = when (tier) {
                Tier.CONSERVATIVE -> 12L * 1024L * 1024L
                Tier.BALANCED -> 24L * 1024L * 1024L
                Tier.CAPABLE -> 36L * 1024L * 1024L
            }
            val historyFrames = when (tier) {
                Tier.CONSERVATIVE -> 1
                Tier.BALANCED -> 2
                Tier.CAPABLE -> 3
            }

            return CameraHardwareProfile(
                tier = tier,
                maxComputationalFrames = frames,
                highResolutionHistoryFrames = historyFrames,
                highResolutionHistoryBytes = historyBytes,
                supportsReprocessing = reprocessing,
                supportsBurst = burst,
                supportsManualSensor = capabilities.manualSensorSupported,
                isoRange = capabilities.isoRange,
                exposureTimeRangeNs = capabilities.exposureTimeRangeNs,
                supportsAeCompensation = capabilities.aeCompensationRange != null &&
                    capabilities.aeCompensationStep != null && capabilities.aeCompensationStep > 0f,
                aeCompensationRange = capabilities.aeCompensationRange,
                aeCompensationStep = capabilities.aeCompensationStep,
                supportsMaximumResolutionYuv = maxYuv,
                supportsMaximumResolutionJpeg = maxJpeg,
                hasVendorNight = vendorNight,
                hasVendorHdr = vendorHdr,
                estimatedCaptureStallNs = capabilities.reprocessMaxCaptureStallNs,
                pipelineDepth = capabilities.pipelineMaxDepth,
            )
        }
    }
}
