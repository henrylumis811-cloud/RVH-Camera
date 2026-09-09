package com.rvh.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size

/** Snapshot of hardware capabilities needed by the imaging pipeline and Build 001 validation. */
data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: Int,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val sensorOrientation: Int,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val rawSupported: Boolean,
    val flashSupported: Boolean,
    val afModes: IntArray,
    val aeModes: IntArray,
    val aeCompensationRange: IntRange?,
    val aeCompensationStep: Float?,
    val awbModes: IntArray,
    val oisModes: IntArray,
    val isoRange: IntRange?,
    val exposureTimeRangeNs: LongRange?,
    val focusDistanceRange: ClosedFloatingPointRange<Float>?,
    val zoomRange: ClosedFloatingPointRange<Float>?,
    val fpsRanges: List<String>,
    val jpegSizes: List<Size>,
    val yuvSizes: List<Size>,
    val rawSizes: List<Size>,
    val maximumResolutionJpegSizes: List<Size>,
    val maximumResolutionYuvSizes: List<Size>,
    val maximumResolutionRawSizes: List<Size>,
    val ultraHighResolutionSensorSupported: Boolean,
    val availableCapabilities: IntArray,
    val physicalCameraIds: Set<String>,
    val manualSensorSupported: Boolean,
    val manualPostProcessingSupported: Boolean,
    val yuvReprocessingSupported: Boolean,
    val privateReprocessingSupported: Boolean,
    val sensorTimestampSource: Int?,
    val videoStabilizationModes: IntArray,
    val sceneModes: IntArray,
    val edgeModes: IntArray,
    val noiseReductionModes: IntArray,
    val supportedExtensions: List<Int>,
    val supportedStreamUseCases: List<Long>,
    val reprocessMaxCaptureStallNs: Long?,
    val pipelineMaxDepth: Int?
) {
    fun hardwareProfile(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): CameraHardwareProfile =
        CameraHardwareProfile.from(this, maxMemoryBytes)

    fun summary(): String = buildString {
        appendLine("cameraId=$cameraId")
        appendLine("hardwareLevel=$hardwareLevel")
        appendLine("sensor=${sensorWidthMm}x${sensorHeightMm}mm")
        appendLine("sensorOrientation=$sensorOrientation")
        appendLine("activeArray=${activeArrayWidth}x${activeArrayHeight}")
        appendLine("rawSupported=$rawSupported")
        appendLine("flashSupported=$flashSupported")
        appendLine("afModes=${afModes.joinToString()}")
        appendLine("aeModes=${aeModes.joinToString()}")
        appendLine("aeCompensationRange=$aeCompensationRange")
        appendLine("aeCompensationStep=$aeCompensationStep")
        appendLine("awbModes=${awbModes.joinToString()}")
        appendLine("oisModes=${oisModes.joinToString()}")
        appendLine("isoRange=$isoRange")
        appendLine("exposureTimeRangeNs=$exposureTimeRangeNs")
        appendLine("focusDistanceRange=$focusDistanceRange")
        appendLine("zoomRange=$zoomRange")
        appendLine("fpsRanges=${fpsRanges.joinToString()}")
        appendLine("jpegSizes=${jpegSizes.joinToString()}")
        appendLine("yuvSizes=${yuvSizes.joinToString()}")
        appendLine("rawSizes=${rawSizes.joinToString()}")
        appendLine("maximumResolutionJpegSizes=${maximumResolutionJpegSizes.joinToString()}")
        appendLine("maximumResolutionYuvSizes=${maximumResolutionYuvSizes.joinToString()}")
        appendLine("maximumResolutionRawSizes=${maximumResolutionRawSizes.joinToString()}")
        appendLine("ultraHighResolutionSensorSupported=$ultraHighResolutionSensorSupported")
        appendLine("availableCapabilities=${availableCapabilities.joinToString()}")
        appendLine("physicalCameraIds=${physicalCameraIds.joinToString()}")
        appendLine("manualSensorSupported=$manualSensorSupported")
        appendLine("manualPostProcessingSupported=$manualPostProcessingSupported")
        appendLine("yuvReprocessingSupported=$yuvReprocessingSupported")
        appendLine("privateReprocessingSupported=$privateReprocessingSupported")
        appendLine("sensorTimestampSource=$sensorTimestampSource")
        appendLine("videoStabilizationModes=${videoStabilizationModes.joinToString()}")
        appendLine("sceneModes=${sceneModes.joinToString()}")
        appendLine("edgeModes=${edgeModes.joinToString()}")
        appendLine("noiseReductionModes=${noiseReductionModes.joinToString()}")
        appendLine("supportedExtensions=${supportedExtensions.joinToString()}")
        appendLine("supportedStreamUseCases=${supportedStreamUseCases.joinToString()}")
        appendLine("reprocessMaxCaptureStallNs=$reprocessMaxCaptureStallNs")
        appendLine("pipelineMaxDepth=$pipelineMaxDepth")
    }

    companion object {
        fun inspect(manager: CameraManager, cameraId: String): CameraCapabilities {
            val c = manager.getCameraCharacteristics(cameraId)
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val requestCaps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val map: StreamConfigurationMap? = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val raw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
            val maxMap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            } else null
            val maxJpeg = maxMap?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            val maxYuv = maxMap?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val maxRaw = maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
            val sensitivity = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val focus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val zoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            val capabilities = requestCaps.toSet()
            val streamUseCases = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                c.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)?.toList().orEmpty()
            } else {
                emptyList()
            }
            val supportedExtensions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                runCatching { manager.getCameraExtensionCharacteristics(cameraId).supportedExtensions }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { formatRange(it) }.orEmpty()

            return CameraCapabilities(
                cameraId = cameraId,
                hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                sensorWidthMm = sensor?.width ?: 0f,
                sensorHeightMm = sensor?.height ?: 0f,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                activeArrayWidth = active?.width() ?: 0,
                activeArrayHeight = active?.height() ?: 0,
                rawSupported = requestCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                flashSupported = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
                afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf(),
                aeModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf(),
                aeCompensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { it.lower..it.upper },
                aeCompensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
                    if (it.denominator == 0) null else it.numerator.toFloat() / it.denominator.toFloat()
                },
                awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(),
                oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf(),
                isoRange = sensitivity?.let { it.lower..it.upper },
                exposureTimeRangeNs = exposure?.let { it.lower..it.upper },
                focusDistanceRange = focus?.let { 0f..it },
                zoomRange = zoom?.let { 1f..it },
                fpsRanges = fps,
                jpegSizes = jpeg,
                yuvSizes = yuv,
                rawSizes = raw,
                maximumResolutionJpegSizes = maxJpeg,
                maximumResolutionYuvSizes = maxYuv,
                maximumResolutionRawSizes = maxRaw,
                ultraHighResolutionSensorSupported = requestCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR),
                availableCapabilities = requestCaps,
                physicalCameraIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) c.physicalCameraIds else emptySet(),
                manualSensorSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                manualPostProcessingSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
                yuvReprocessingSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING),
                privateReprocessingSupported = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING),
                sensorTimestampSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
                videoStabilizationModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf(),
                sceneModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf(),
                edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf(),
                noiseReductionModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf(),
                supportedExtensions = supportedExtensions,
                supportedStreamUseCases = streamUseCases,
                reprocessMaxCaptureStallNs = c.get(CameraCharacteristics.REPROCESS_MAX_CAPTURE_STALL)?.toLong(),
                pipelineMaxDepth = c.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt()
            )
        }

        private fun formatRange(range: Range<Int>): String = "${range.lower}-${range.upper}"
    }
}
