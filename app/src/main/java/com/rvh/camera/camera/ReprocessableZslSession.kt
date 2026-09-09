package com.rvh.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Build
import android.os.Handler
import android.util.Size
import android.util.Rational
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import com.rvh.camera.imaging.FrameMetadata
import com.rvh.camera.imaging.Yuv420Frame

/**
 * True Camera2 YUV reprocessing/ZSL plumbing.
 *
 * This class owns the reprocessable session topology, the live YUV history and the
 * ImageWriter hand-off used by createReprocessCaptureRequest(). It is intentionally
 * independent of the normal JPEG/YUV burst path so a device can reject it without
 * weakening the proven capture path.
 */
@android.annotation.SuppressLint("NewApi")
class ReprocessableZslSession(
    private val device: CameraDevice,
    private val characteristics: CameraCharacteristics,
    private val handler: Handler,
) {
    companion object {
        private const val MAX_HISTORY = 3
        private const val MAX_HISTORY_AGE_NS = 650_000_000L
        private const val MAX_RESULT_IMAGE_SKEW_NS = 3_000_000L
    }

    data class Topology(
        val inputSize: Size,
        val outputFormats: Set<Int>,
        val jpegSize: Size,
        val previewSurface: Surface,
        val jpegSurface: Surface,
        val analysisSurface: Surface?,
        val inputConfiguration: InputConfiguration,
    )

    data class ZslFrame(
        val timestampNs: Long,
        val image: Image,
        val result: TotalCaptureResult,
    )

    private data class PendingResult(val result: TotalCaptureResult, val timestampNs: Long)

    var topology: Topology? = null
        private set

    var session: CameraCaptureSession? = null
        private set

    var sourceReader: ImageReader? = null
        private set

    var inputWriter: ImageWriter? = null
        private set

    private val history = ArrayDeque<ZslFrame>()
    private val pendingResults = ArrayDeque<PendingResult>()
    private val pendingImages = ArrayDeque<Image>()

    /**
     * Finds a complete reprocessable topology. The YUV source reader is part of the regular
     * output set because it must receive the original camera buffer before it can be queued into
     * the session input surface for reprocessing.
     */
    fun probe(
        previewSurface: Surface,
        jpegSurface: Surface,
        jpegSize: Size,
        analysisSurface: Surface? = null,
    ): Topology? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        if (!map.inputFormats.contains(ImageFormat.YUV_420_888)) return null

        val inputSizes = map.getInputSizes(ImageFormat.YUV_420_888).orEmpty()
        if (inputSizes.isEmpty()) return null

        val selected = inputSizes
            .filter {
                it.width.toLong() * it.height.toLong() <=
                    jpegSize.width.toLong() * jpegSize.height.toLong()
            }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: inputSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return null

        val validOutputs = map.getValidOutputFormatsForInput(ImageFormat.YUV_420_888)
            ?.toSet()
            .orEmpty()
        if (ImageFormat.JPEG !in validOutputs) return null

        val inputConfig = InputConfiguration(
            selected.width,
            selected.height,
            ImageFormat.YUV_420_888,
        )

        val candidateReader = try {
            ImageReader.newInstance(
                selected.width,
                selected.height,
                ImageFormat.YUV_420_888,
                MAX_HISTORY + 2,
            )
        } catch (_: Exception) {
            return null
        }

        val outputs = ArrayList<OutputConfiguration>(4)
        outputs += OutputConfiguration(previewSurface)
        outputs += OutputConfiguration(jpegSurface)
        outputs += OutputConfiguration(candidateReader.surface)
        if (analysisSurface != null && analysisSurface != previewSurface && analysisSurface != jpegSurface) {
            outputs += OutputConfiguration(analysisSurface)
        }
        val executor = Executor { command -> handler.post(command) }
        val config = try {
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        val inputSurface = configured.inputSurface
                        inputWriter = if (inputSurface != null) {
                            runCatching {
                                ImageWriter.newInstance(inputSurface, MAX_HISTORY + 2)
                            }.getOrNull()
                        } else {
                            null
                        }
                        if (inputWriter == null) {
                            configured.close()
                            session = null
                            onConfigureFailed?.invoke("Unable to create ZSL input writer")
                        } else {
                            onConfigured?.invoke(configured)
                        }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        try { configured.close() } catch (_: Exception) {}
                        session = null
                        onConfigureFailed?.invoke("Camera rejected ZSL reprocessable session")
                    }
                },
            ).also { it.setInputConfiguration(inputConfig) }
        } catch (_: Exception) {
            candidateReader.close()
            return null
        }

        return try {
            if (!device.isSessionConfigurationSupported(config)) {
                candidateReader.close()
                return null
            }
            sourceReader = candidateReader
            topology = Topology(
                inputSize = selected,
                outputFormats = validOutputs,
                jpegSize = jpegSize,
                previewSurface = previewSurface,
                jpegSurface = jpegSurface,
                analysisSurface = analysisSurface,
                inputConfiguration = inputConfig,
            )
            pendingOpenConfig = config
            topology
        } catch (_: Exception) {
            candidateReader.close()
            null
        }
    }

    private var pendingOpenConfig: SessionConfiguration? = null
    private var onConfigured: ((CameraCaptureSession) -> Unit)? = null
    private var onConfigureFailed: ((String) -> Unit)? = null

    fun open(
        onReady: () -> Unit,
        onFailed: (String) -> Unit,
    ): Boolean {
        val config = pendingOpenConfig ?: return false
        onConfigured = { onReady() }
        onConfigureFailed = onFailed
        return try {
            device.createCaptureSession(config)
            true
        } catch (e: Exception) {
            pendingOpenConfig = null
            onConfigured = null
            onConfigureFailed = null
            try { sourceReader?.close() } catch (_: Exception) {}
            sourceReader = null
            topology = null
            onFailed(e.message ?: "Unable to open ZSL session")
            false
        }
    }

    /**
     * Installs the listener which turns the source ImageReader into a bounded timestamped ZSL
     * history. Capture results may arrive before or after images, so both sides are correlated.
     */
    fun installSourceListener(onFrameAvailable: (ZslFrame) -> Unit = {}) {
        val reader = sourceReader ?: return
        reader.setOnImageAvailableListener({ imageReader ->
            var image: Image? = null
            try {
                image = imageReader.acquireNextImage()
                val timestamp = image.timestamp
                val pending = pendingResults.firstOrNull { abs(it.timestampNs - timestamp) <= MAX_RESULT_IMAGE_SKEW_NS }
                if (pending != null) {
                    pendingResults.remove(pending)
                    acceptMatchedImage(image, pending.result, onFrameAvailable)
                    image = null
                } else {
                    pendingImages.addLast(image)
                    image = null
                    trimPendingImages()
                    matchPending(onFrameAvailable)
                }
            } catch (_: Exception) {
                try { image?.close() } catch (_: Exception) {}
            }
        }, handler)
    }

    fun onCaptureResult(result: TotalCaptureResult, onFrameAvailable: (ZslFrame) -> Unit = {}) {
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val pendingImage = pendingImages.firstOrNull { abs(it.timestamp - timestamp) <= MAX_RESULT_IMAGE_SKEW_NS }
        if (pendingImage != null) {
            pendingImages.remove(pendingImage)
            acceptMatchedImage(pendingImage, result, onFrameAvailable)
        } else {
            pendingResults.addLast(PendingResult(result, timestamp))
            trimPendingResults()
            matchPending(onFrameAvailable)
        }
    }

    private fun matchPending(onFrameAvailable: (ZslFrame) -> Unit) {
        while (pendingImages.isNotEmpty() && pendingResults.isNotEmpty()) {
            val image = pendingImages.first()
            val result = pendingResults.minByOrNull { abs(it.timestampNs - image.timestamp) } ?: break
            if (abs(result.timestampNs - image.timestamp) > MAX_RESULT_IMAGE_SKEW_NS) break
            pendingImages.removeFirst()
            pendingResults.remove(result)
            acceptMatchedImage(image, result.result, onFrameAvailable)
        }
    }

    private fun acceptMatchedImage(
        image: Image,
        result: TotalCaptureResult,
        onFrameAvailable: (ZslFrame) -> Unit,
    ) {
        history.addLast(ZslFrame(image.timestamp, image, result))
        trimHistory()
        onFrameAvailable(history.last())
    }

    private fun trimHistory() {
        val newest = history.lastOrNull()?.timestampNs ?: return
        while (history.size > MAX_HISTORY) history.removeFirst().image.close()
        while (history.isNotEmpty() && newest - history.first().timestampNs > MAX_HISTORY_AGE_NS) {
            history.removeFirst().image.close()
        }
    }

    private fun trimPendingImages() {
        while (pendingImages.size > MAX_HISTORY + 1) pendingImages.removeFirst().close()
    }

    private fun trimPendingResults() {
        while (pendingResults.size > MAX_HISTORY + 1) pendingResults.removeFirst()
    }

    fun latestFrame(): ZslFrame? = history.lastOrNull()

    fun selectNearest(timestampNs: Long): ZslFrame? = history.minByOrNull { abs(it.timestampNs - timestampNs) }

    /**
     * Chooses the most useful retained frame without blindly taking the newest one. A tiny
     * recency bias keeps shutter latency low while a short exposure and lower ISO are preferred
     * when several frames are nearly simultaneous. This is deliberately conservative until real
     * device motion/OIS telemetry can be incorporated into the score.
     */
    fun selectBestForShutter(
        referenceTimestampNs: Long?,
        sceneMotionScore: Float = 0f,
    ): ZslFrame? {
        if (history.isEmpty()) return null
        val reference = referenceTimestampNs ?: history.last().timestampNs
        return history.maxByOrNull { frame ->
            val ageNs = abs(reference - frame.timestampNs).coerceAtMost(MAX_HISTORY_AGE_NS)
            val recency = 1f - (ageNs.toFloat() / MAX_HISTORY_AGE_NS.toFloat())
            val exposure = frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: Long.MAX_VALUE
            val iso = frame.result.get(CaptureResult.SENSOR_SENSITIVITY) ?: Int.MAX_VALUE
            val stability = (1f / (1f + (exposure / 8_000_000f).coerceAtMost(8f)))
            val isoPenalty = (1f / (1f + (iso.coerceAtLeast(100) / 800f)))
            val oisAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                frame.result.get(CaptureResult.STATISTICS_OIS_SAMPLES)?.isNotEmpty() == true
            } else {
                false
            }
            val motionPenalty = sceneMotionScore.coerceIn(0f, 1f) *
                if (oisAvailable) 0.10f else 0.18f
            recency * (0.54f - motionPenalty * 0.20f).coerceAtLeast(0.30f) +
                stability * 0.22f +
                isoPenalty * 0.18f +
                if (oisAvailable) 0.06f else 0f
        }
    }

    fun snapshot(): List<ZslFrame> = history.toList()

    /**
     * Promotes the best retained ZSL frames into the application computational pipeline.
     * Ownership of the selected Image buffers is transferred to the returned packed frames;
     * unselected history remains available for a subsequent shutter.
     *
     * We intentionally select from a very small temporal window. The goal is not to collect
     * every frame, but to obtain a sharp anchor plus nearby frames with compatible exposure.
     * This gives FrameFusion real full-resolution material instead of asking the HAL to render
     * a single JPEG before our computational stages get a chance to work.
     */
    fun takeFramesForProcessing(
        referenceTimestampNs: Long?,
        desiredCount: Int,
        sceneMotionScore: Float,
    ): List<Yuv420Frame> {
        if (history.isEmpty() || desiredCount <= 0) return emptyList()

        val reference = referenceTimestampNs ?: history.last().timestampNs
        val motion = sceneMotionScore.coerceIn(0f, 1f)
        val candidates = history.toList()
            .sortedBy { abs(reference - it.timestampNs) }
            .take(MAX_HISTORY)

        val anchor = candidates.maxByOrNull { frameScore(it, reference, motion) } ?: return emptyList()
        val selected = ArrayList<ZslFrame>(desiredCount)
        selected += anchor

        // Prefer frames on both sides of the anchor when possible. This is important for
        // temporal fusion: a burst of nearly identical frames is more useful than three stale
        // frames all from one side of the shutter event.
        val remaining = candidates
            .filter { it !== anchor }
            .sortedByDescending {
                val distance = abs(it.timestampNs - anchor.timestampNs).toFloat()
                val exposure = it.result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: Long.MAX_VALUE
                val iso = it.result.get(CaptureResult.SENSOR_SENSITIVITY) ?: Int.MAX_VALUE
                val exposureSimilarity = if (exposure == Long.MAX_VALUE) 0f else
                    exp(-abs(exposure - (anchor.result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: exposure)).toFloat() / 8_000_000f)
                val isoSimilarity = if (iso == Int.MAX_VALUE) 0f else {
                    val anchorIso = anchor.result.get(CaptureResult.SENSOR_SENSITIVITY) ?: iso
                    exp(-abs(iso - anchorIso).toFloat() / 800f)
                }
                val temporal = exp(-distance / 250_000_000f)
                temporal * (0.55f + 0.25f * exposureSimilarity + 0.20f * isoSimilarity)
            }
        for (candidate in remaining) {
            if (selected.size >= desiredCount.coerceAtMost(MAX_HISTORY)) break
            selected += candidate
        }

        val output = ArrayList<Yuv420Frame>(selected.size)
        for (frame in selected.sortedBy { it.timestampNs }) {
            if (!history.remove(frame)) continue
            try {
                val metadata = FrameMetadata(
                    timestampNs = frame.timestampNs,
                    exposureTimeNs = frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    sensitivityIso = frame.result.get(CaptureResult.SENSOR_SENSITIVITY),
                    lensAperture = frame.result.get(CaptureResult.LENS_APERTURE),
                    focusDistance = frame.result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    frameNumber = frame.result.frameNumber,
                    rollingShutterSkewNs = frame.result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                    oisDataAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        frame.result.get(CaptureResult.STATISTICS_OIS_SAMPLES)?.isNotEmpty() == true
                    } else false,
                    sensorNoiseProfile = frame.result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { metadataValuesToFloatArray(it) },
                    sensorNeutralPoint = frame.result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { metadataValuesToFloatArray(it) },
                    sensorColorTransform = frame.result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { metadataValuesToFloatArray(it) },
                )
                output += Yuv420Frame.fromImage(frame.image, metadata)
            } finally {
                try { frame.image.close() } catch (_: Exception) {}
            }
        }
        return output
    }

    private fun metadataValuesToFloatArray(values: Any?): FloatArray? {
        if (values == null) return null
        if (values is FloatArray) return values
        val array = values as? Array<*> ?: return null
        return FloatArray(array.size) { index ->
            when (val value = array[index]) {
                is Rational -> value.toFloat()
                is Pair<*, *> -> {
                    val numerator = (value.first as? Number)?.toDouble()
                    val denominator = (value.second as? Number)?.toDouble()
                    if (numerator == null || denominator == null || denominator == 0.0) {
                        0f
                    } else {
                        (numerator / denominator).toFloat()
                    }
                }
                else -> 0f
            }
        }
    }

    private fun frameScore(frame: ZslFrame, reference: Long, motion: Float): Float {
        val age = abs(reference - frame.timestampNs).toFloat().coerceAtMost(MAX_HISTORY_AGE_NS.toFloat())
        val recency = 1f - age / MAX_HISTORY_AGE_NS.toFloat()
        val exposure = frame.result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: Long.MAX_VALUE
        val iso = frame.result.get(CaptureResult.SENSOR_SENSITIVITY) ?: Int.MAX_VALUE
        val exposurePenalty = if (exposure == Long.MAX_VALUE) 0f
            else (exposure / 20_000_000f).coerceIn(0f, 1f)
        val isoPenalty = if (iso == Int.MAX_VALUE) 1f
            else ((iso - 100).coerceAtLeast(0) / 1600f).coerceIn(0f, 1f)
        val oisBonus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            frame.result.get(CaptureResult.STATISTICS_OIS_SAMPLES)?.isNotEmpty() == true) 0.08f else 0f
        return recency * 0.52f +
            (1f - exposurePenalty) * 0.20f +
            (1f - isoPenalty) * 0.18f +
            oisBonus - motion * exposurePenalty * 0.12f
    }

    /**
     * Copies one previously captured YUV frame into the reprocessable input surface and submits
     * a JPEG reprocess request. The source image is closed only after its contents are copied.
     */
    fun reprocess(
        frame: ZslFrame,
        jpegOrientation: Int,
        configure: (CaptureRequest.Builder) -> Unit = {},
        onCompleted: () -> Unit = {},
        onFailed: (String) -> Unit = {},
    ): Boolean {
        val activeSession = session ?: return false
        val writer = inputWriter ?: return false
        val outputSurface = topology?.jpegSurface ?: return false

        return try {
            val input = writer.dequeueInputImage()
            val builder = device.createReprocessCaptureRequest(frame.result)
            try {
                copyYuv(frame.image, input)
                builder.addTarget(outputSurface)
                builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                builder.set(
                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE,
                )
                configure(builder)
                writer.queueInputImage(input)
            } catch (e: Exception) {
                try { input.close() } catch (_: Exception) {}
                throw e
            } finally {
                try { frame.image.close() } catch (_: Exception) {}
                history.removeIf { it.image === frame.image }
            }

            activeSession.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        onCompleted()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure,
                    ) {
                        onFailed("ZSL reprocess failed: ${failure.reason}")
                    }
                },
                handler,
            )
            true
        } catch (e: Exception) {
            onFailed(e.message ?: "ZSL reprocess unavailable")
            false
        }
    }

    /** Start a repeating request that continuously feeds both preview and the ZSL source reader. */
    fun buildRepeatingRequest(
        onResult: (TotalCaptureResult) -> Unit,
    ): CaptureRequest? {
        val activeSession = session ?: return null
        val source = sourceReader?.surface ?: return null
        return try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG).apply {
                addTarget(topology?.previewSurface ?: return null)
                addTarget(source)
                topology?.analysisSurface?.let(::addTarget)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
            }.build().also { request ->
                activeSession.setRepeatingRequest(
                    request,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            onCaptureResult(result)
                            onResult(result)
                        }
                    },
                    handler,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyYuv(source: Image, destination: Image) {
        require(source.format == ImageFormat.YUV_420_888)
        require(destination.format == ImageFormat.YUV_420_888)
        require(source.width == destination.width && source.height == destination.height)
        for (planeIndex in 0..2) {
            copyPlane(source.planes[planeIndex], destination.planes[planeIndex], if (planeIndex == 0) source.width else ceil(source.width / 2.0).toInt(), if (planeIndex == 0) source.height else ceil(source.height / 2.0).toInt())
        }
    }

    private fun copyPlane(source: Image.Plane, destination: Image.Plane, width: Int, height: Int) {
        val src = source.buffer.duplicate()
        val dst = destination.buffer.duplicate()
        val srcStride = source.rowStride
        val dstStride = destination.rowStride
        val srcPixel = source.pixelStride
        val dstPixel = destination.pixelStride
        val rowBytes = (width - 1) * srcPixel + 1
        if (srcPixel == 1 && dstPixel == 1) {
            val safeRowBytes = rowBytes.coerceAtLeast(0)
            for (y in 0 until height) {
                val srcIndex = y * srcStride
                val dstIndex = y * dstStride
                if (srcIndex + safeRowBytes <= src.limit() && dstIndex + safeRowBytes <= dst.limit()) {
                    val row = ByteArray(safeRowBytes)
                    val srcRow = src.duplicate()
                    srcRow.position(srcIndex)
                    srcRow.get(row)
                    val dstRow = dst.duplicate()
                    dstRow.position(dstIndex)
                    dstRow.put(row)
                }
            }
            return
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcIndex = y * srcStride + x * srcPixel
                val dstIndex = y * dstStride + x * dstPixel
                if (srcIndex < src.limit() && dstIndex < dst.limit()) {
                    dst.put(dstIndex, src.get(srcIndex))
                }
            }
        }
    }

    fun close() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { inputWriter?.close() } catch (_: Exception) {}
        inputWriter = null
        history.forEach { try { it.image.close() } catch (_: Exception) {} }
        history.clear()
        pendingImages.forEach { try { it.close() } catch (_: Exception) {} }
        pendingImages.clear()
        pendingResults.clear()
        try { sourceReader?.close() } catch (_: Exception) {}
        sourceReader = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        topology = null
        pendingOpenConfig = null
        onConfigured = null
        onConfigureFailed = null
    }
}
