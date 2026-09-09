package com.rvh.camera.imaging

/** Immutable description of one camera frame entering the RVH imaging pipeline. */
data class FrameMetadata(
    val timestampNs: Long,
    val exposureTimeNs: Long? = null,
    val sensitivityIso: Int? = null,
    val lensAperture: Float? = null,
    val focusDistance: Float? = null,
    val frameNumber: Long? = null,
    /** Sensor rolling-shutter skew from first-row to last-row exposure start, when reported. */
    val rollingShutterSkewNs: Long? = null,
    /** Whether OIS sample metadata was available for this frame. */
    val oisDataAvailable: Boolean = false,
    /** OIS sample timestamps in sensor timebase, when reported. */
    val oisTimestampsNs: LongArray? = null,
    /** OIS x shifts in pre-correction active-array pixels. */
    val oisXShifts: FloatArray? = null,
    /** OIS y shifts in pre-correction active-array pixels. */
    val oisYShifts: FloatArray? = null,
    /** Rotation required to present the sensor image upright in the saved JPEG. */
    val rotationDegrees: Int = 0,
    /** Camera2 SENSOR_NOISE_PROFILE coefficients: [S,R], [S,G_even], [S,G_odd], [S,B]. */
    val sensorNoiseProfile: FloatArray? = null,
    /** Camera2 estimated neutral point in native sensor RGB, when available. */
    val sensorNeutralPoint: FloatArray? = null,
    /** Camera2 sensor-to-linear-sRGB color transform, row-major 3x3, when available. */
    val sensorColorTransform: FloatArray? = null,
    /** Camera2 Bayer white-balance gains in [R, G_even, G_odd, B] order. */
    val sensorColorGains: FloatArray? = null,
    /** Worst-case divergence between the two Bayer green channels. */
    val sensorGreenSplit: Float? = null,
    /** Per-frame lens shading correction map: width,height followed by RGGB gains. */
    val sensorLensShadingMap: FloatArray? = null,
    val sensorLensShadingMapWidth: Int? = null,
    val sensorLensShadingMapHeight: Int? = null,
    /** Whether RAW already contains some/all lens shading correction. */
    val sensorLensShadingApplied: Boolean? = null,
    /** Camera2 RAW Bayer layout: 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR. */
    val sensorCfaArrangement: Int? = null,
    /** Fixed 2x2 RAW black-level pattern in CFA order, when available. */
    val sensorBlackLevelPattern: FloatArray? = null,
    /** Dynamic per-frame RAW black level in CFA order, when available. */
    val sensorDynamicBlackLevel: FloatArray? = null,
    /** Optically shielded RAW regions [left, top, width, height] repeated per region. */
    val sensorOpticalBlackRegions: IntArray? = null,
    /** Maximum non-saturated RAW encoding value. */
    val sensorWhiteLevel: Int? = null,
    /** Per-frame dynamic white level, preferred over the static characteristic when present. */
    val sensorDynamicWhiteLevel: Int? = null,
    /** DNG/Camera2 reference illuminant identifiers used by the dual-illuminant calibration. */
    val sensorReferenceIlluminant1: Int? = null,
    val sensorReferenceIlluminant2: Int? = null,
    /** Reference-sensor calibration transform, row-major 3x3. */
    val sensorCalibrationTransform1: FloatArray? = null,
    /** Reference-sensor calibration transform for second illuminant, row-major 3x3. */
    val sensorCalibrationTransform2: FloatArray? = null,
    /** Forward matrix for first reference illuminant, row-major 3x3. */
    val sensorForwardMatrix1: FloatArray? = null,
    /** Forward matrix for second reference illuminant, row-major 3x3. */
    val sensorForwardMatrix2: FloatArray? = null,
    /** Camera2 lens intrinsic calibration: [fx, fy, cx, cy, skew] in pre-correction sensor pixels. */
    val lensIntrinsicCalibration: FloatArray? = null,
    /** Camera2 Brown-Conrady lens distortion coefficients: [k1, k2, k3, p1, p2]. */
    val lensDistortion: FloatArray? = null,
    /** Pre-correction active-array rectangle [left, top, width, height], when available. */
    val sensorPreCorrectionActiveArray: IntArray? = null,
    /** Whether this frame originated from RAW_SENSOR rather than processed YUV/JPEG. */
    val isRawSensorFrame: Boolean = false,
)

enum class CaptureIntent {
    PHOTO,
    HDR,
    NIGHT,
    PORTRAIT,
}

data class ProcessingRequest(
    val intent: CaptureIntent = CaptureIntent.PHOTO,
    val allowMultiFrame: Boolean = false,
    val allowVendorExtension: Boolean = true,
)
