package com.rvh.camera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.Log
import android.view.Surface
import android.util.Rational
import android.view.TextureView
import com.rvh.camera.imaging.CapturePlanner
import com.rvh.camera.diagnostics.DiagnosticLogger
import com.rvh.camera.imaging.CaptureIntent
import com.rvh.camera.imaging.CapturePlan
import com.rvh.camera.imaging.FrameMetadata
import com.rvh.camera.imaging.FrameHistoryController
import com.rvh.camera.imaging.FrameHistoryQualityEstimator
import com.rvh.camera.imaging.HistoryPolicy
import com.rvh.camera.imaging.SceneAnalysis
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Camera2 foundation: responsive preview + full-resolution still capture.
 * No computational enhancement is performed here; this layer preserves camera data for later stages.
 */
class CameraController(private val context: Context) {
    private val diagTag = "RVH_DIAG"
    private val diagnostics = DiagnosticLogger.get(context)
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("rvh-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val openInProgress = AtomicBoolean(false)
    private val captureSequence = AtomicLong(0L)
    private val captureSequenceManager = CaptureSequenceManager()
    private val pendingByTimestamp = ConcurrentHashMap<Long, PendingCapture>()
    private val imageBytesByTimestamp = ConcurrentHashMap<Long, ByteArray>()
    private val rawFramesByTimestamp = ConcurrentHashMap<Long, com.rvh.camera.imaging.RawSensorFrame>()
    private val recentTimestamps = ArrayDeque<Long>()
    private val analysisMetadataByTimestamp = ConcurrentHashMap<Long, FrameMetadata>()
    private val yuvCaptureMetadataByTimestamp = ConcurrentHashMap<Long, FrameMetadata>()
    private val yuvFramesByTimestamp = ConcurrentHashMap<Long, com.rvh.camera.imaging.Yuv420Frame>()
    private val capturePlanner = CapturePlanner()
    @Volatile private var frameHistory = FrameHistoryController()
    @Volatile private var latestSceneAnalysis: SceneAnalysis? = null
    @Volatile private var latestAnalysisMetadata: FrameMetadata? = null
    @Volatile private var latestPreviewMetadata: FrameMetadata? = null
    @Volatile private var latestSceneMotionScore: Float = 0f

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var extensionSession: CameraExtensionSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var rawCaptureEnabled = false
    private var yuvAnalysisReader: ImageReader? = null
    private var yuvAnalysisConfig: YuvCaptureConfig? = null
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewSize = Size(1280, 720)
    private var jpegSize = Size(1920, 1080)
    private var previewTexture: TextureView? = null
    private var captureInFlight = AtomicBoolean(false)
    private var yuvAnalysisEnabled = false
    private var yuvCaptureReader: ImageReader? = null
    private var yuvCaptureConfig: YuvCaptureConfig? = null
    private var yuvCaptureEnabled = false
    private var zslSession: ReprocessableZslSession? = null
    private var zslEnabled = false

    var state: CameraState = CameraState.Initializing
        private set

    var capabilities: CameraCapabilities? = null
        private set

    @Volatile private var hardwareProfile: CameraHardwareProfile? = null

    fun hardwareProfile(): CameraHardwareProfile? = hardwareProfile

    /** Returns the latest hardware snapshot for diagnostics and later device profiling. */
    fun capabilityReport(): String = capabilities?.summary() ?: "Camera capabilities unavailable"

    /** Latest lightweight scene analysis produced from the live YUV stream, when available. */
    fun latestCapturePlan(): CapturePlan? {
        val analysis = latestSceneAnalysis ?: return null
        val metadata = latestAnalysisMetadata ?: FrameMetadata(timestampNs = 0L)
        val extensions = capabilities?.supportedExtensions.orEmpty()
        val nightExtension = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT in extensions
        val hdrExtension = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_HDR in extensions
        val plan = capturePlanner.choosePlan(
            analysis = analysis,
            metadata = metadata,
            vendorNightExtensionAvailable = nightExtension,
            vendorHdrExtensionAvailable = hdrExtension,
        )
        val maxFrames = hardwareProfile?.maxComputationalFrames ?: 2
        // Fast-moving PHOTO scenes do not benefit enough from temporal fusion to justify the
        // extra shutter latency. Spatial scene change is measured on the tiny analysis stream,
        // while the full-resolution fusion layer remains the final ghost-rejection authority.
        val motionLimitedCount = if (plan.intent == com.rvh.camera.imaging.CaptureIntent.PHOTO &&
            latestSceneMotionScore >= 0.28f) 1 else plan.frameCount
        return plan.copy(frameCount = motionLimitedCount.coerceAtMost(maxFrames))
    }

    fun open(
        texture: TextureView,
        viewWidth: Int,
        viewHeight: Int,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (closed.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            state = CameraState.PermissionRequired
            onError("Camera permission required")
            return
        }

        handler.post {
            if (closed.get() || camera != null || !openInProgress.compareAndSet(false, true)) return@post

            val id = findRearCamera()
            if (id == null) {
                fail("No rear camera found", onError)
                return@post
            }

            try {
                state = CameraState.Opening
                cameraId = id
                val c = manager.getCameraCharacteristics(id)
                characteristics = c
                capabilities = CameraCapabilities.inspect(manager, id)
                hardwareProfile = capabilities?.hardwareProfile()
                hardwareProfile?.let { profile ->
                    frameHistory.clear()
                    frameHistory = FrameHistoryController(
                        HistoryPolicy(
                            maxFrames = profile.highResolutionHistoryFrames,
                            maxBytes = profile.highResolutionHistoryBytes,
                            maxAgeNs = 650_000_000L,
                            minimumQualityAdvantage = 0.08f,
                        )
                    )
                }

                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw IllegalStateException("Camera stream configuration unavailable")

                val jpegSizes: Array<Size> = map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList()?.toTypedArray() ?: emptyArray()
                val previewSizes: Array<Size> = map.getOutputSizes(SurfaceTexture::class.java)?.toList()?.toTypedArray() ?: emptyArray()
                if (jpegSizes.isEmpty() || previewSizes.isEmpty()) {
                    throw IllegalStateException("Required camera output sizes unavailable")
                }

                jpegSize = chooseCaptureSize(jpegSizes)
                previewSize = choosePreviewSize(previewSizes, viewWidth, viewHeight)

                val surfaceTexture = texture.surfaceTexture
                    ?: throw IllegalStateException("Preview surface is no longer available")
                previewTexture = texture
                surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                previewSurface = Surface(surfaceTexture)
                imageReader = ImageReader.newInstance(
                    jpegSize.width,
                    jpegSize.height,
                    android.graphics.ImageFormat.JPEG,
                    2
                ).also { reader ->
                    reader.setOnImageAvailableListener({ source ->
                        source.acquireLatestImage()?.use { image ->
                            val buffer = image.planes.firstOrNull()?.buffer ?: return@use
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            onImageAvailable(image.timestamp, bytes)
                        }
                    }, handler)
                }

                rawCaptureEnabled = capabilities?.rawSupported == true && capabilities?.rawSizes?.isNotEmpty() == true
                if (rawCaptureEnabled) {
                    val rawSize = capabilities?.rawSizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    if (rawSize != null) {
                        rawReader = ImageReader.newInstance(
                            rawSize.width, rawSize.height, android.graphics.ImageFormat.RAW_SENSOR, 3
                        ).also { reader ->
                            reader.setOnImageAvailableListener({ source ->
                                runCatching {
                                    while (true) {
                                        val image = source.acquireNextImage() ?: break
                                        image.use { captured ->
                                            val ts = captured.timestamp
                                            val frame = com.rvh.camera.imaging.RawSensorFrame.fromImage(
                                                captured, latestPreviewMetadata?.copy(timestampNs = ts)
                                                    ?: FrameMetadata(timestampNs = ts)
                                            )
                                            rawFramesByTimestamp.remove(ts)?.close()
                                            rawFramesByTimestamp[ts] = frame
                                            while (rawFramesByTimestamp.size > 4) {
                                                val oldest = rawFramesByTimestamp.keys.minOrNull() ?: break
                                                rawFramesByTimestamp.remove(oldest)?.close()
                                            }
                                        }
                                    }
                                }
                            }, handler)
                        }
                    } else rawCaptureEnabled = false
                }

                // A small YUV analysis stream is optional. It stays out of the session unless
                // the complete preview + YUV + JPEG topology is validated by Camera2. This gives
                // the capture planner real sensor statistics without forcing a full-resolution
                // YUV buffer through the live preview path.
                val aspect = jpegSize.width.toFloat() / jpegSize.height.toFloat()
                yuvAnalysisConfig = YuvCaptureConfigSelector.choose(
                    characteristics = c,
                    preferredAspect = aspect,
                    targetPixels = 640L * 480L,
                )
                yuvAnalysisConfig?.let { config ->
                    yuvAnalysisReader = ImageReader.newInstance(
                        config.size.width,
                        config.size.height,
                        android.graphics.ImageFormat.YUV_420_888,
                        3,
                    ).also { reader ->
                        YuvFrameCollector(
                            reader = reader,
                            handler = handler,
                            metadataForTimestamp = { timestamp ->
                                analysisMetadataByTimestamp.remove(timestamp)
                                    ?: latestPreviewMetadata?.copy(timestampNs = timestamp)
                                    ?: FrameMetadata(timestampNs = timestamp)
                            },
                            onFrame = { frame ->
                                val analysis = capturePlanner.analyze(frame)
                                val previous = latestSceneAnalysis
                                latestSceneMotionScore = previous?.temporalDifference(analysis) ?: 0f
                                latestSceneAnalysis = analysis
                                latestAnalysisMetadata = frame.metadata
                                frame.close()
                            },
                            onError = { /* Analysis is optional; JPEG capture remains authoritative. */ },
                        )
                    }
                }

                // Separate still-processing YUV output. It is only enabled after the complete
                // preview + JPEG + YUV topology is validated below. The target is deliberately
                // moderate first; we will raise it only after real-device memory/latency tests.
                yuvCaptureConfig = YuvCaptureConfigSelector.choose(
                    characteristics = c,
                    preferredAspect = aspect,
                    targetPixels = jpegSize.width.toLong() * jpegSize.height.toLong(),
                )
                yuvCaptureConfig?.let { config ->
                    diagnostics.info("YUV_CAPTURE_CONFIG size=${config.size.width}x${config.size.height}")
                    yuvCaptureReader = ImageReader.newInstance(
                        config.size.width,
                        config.size.height,
                        android.graphics.ImageFormat.YUV_420_888,
                        3,
                    ).also { reader ->
                        reader.setOnImageAvailableListener({ source ->
                            runCatching {
                                while (true) {
                                    val image = source.acquireNextImage() ?: break
                                    image.use { captured ->
                                        val timestamp = captured.timestamp
                                        val metadata = yuvCaptureMetadataByTimestamp[timestamp]
                                        val frame = com.rvh.camera.imaging.Yuv420Frame.fromImage(
                                            captured,
                                            metadata ?: FrameMetadata(timestampNs = timestamp),
                                        )
                                        yuvFramesByTimestamp[timestamp]?.close()
                                        yuvFramesByTimestamp[timestamp] = frame
                                        // If the image arrived before CaptureResult, the frame was
                                        // necessarily created with placeholder metadata. Replace it
                                        // as soon as the real sensor result arrives so exposure/ISO
                                        // and rolling-shutter information survive into processing.
                                        if (metadata != null) {
                                            attemptDeliverYuvFrame(timestamp, captureSequenceManager.active())
                                        }
                                    }
                                }
                            }.onFailure { /* A failed optional computational frame never breaks JPEG capture. */ }
                        }, handler)
                    }
                }

                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        openInProgress.set(false)
                        if (closed.get()) {
                            device.close()
                            return
                        }
                        camera = device
                        createSession(device, onReady, onError)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        openInProgress.set(false)
                        cleanupCameraResources(device)
                        state = CameraState.Error("Camera disconnected")
                        onError("Camera disconnected")
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        openInProgress.set(false)
                        cleanupCameraResources(device)
                        state = CameraState.Error("Camera error: $error")
                        onError("Camera error: $error")
                    }
                }, handler)
            } catch (e: Exception) {
                openInProgress.set(false)
                cleanupCameraResources()
                fail(e.message ?: "Unable to open camera", onError)
            }
        }
    }

    fun takePicture(
        onCaptured: (ByteArray, FrameMetadata) -> Unit,
        onError: (String) -> Unit,
        onCapturedYuv: ((List<com.rvh.camera.imaging.Yuv420Frame>, CapturePlan, ByteArray?) -> Unit)? = null,
        onCapturedRaw: ((List<com.rvh.camera.imaging.RawSensorFrame>, CapturePlan) -> Unit)? = null,
    ) {
        handler.post {
            val device = camera
            val captureSession = session
            if (device == null || captureSession == null) {
                onError("Camera is not ready")
                return@post
            }
            if (!captureInFlight.compareAndSet(false, true)) return@post

            val plan = latestCapturePlan() ?: CapturePlan(
                intent = com.rvh.camera.imaging.CaptureIntent.PHOTO,
                frameCount = 1,
                preferVendorExtension = false,
                score = 0f,
            )

            // Build 001 device gate: JPEG is the authoritative shutter path. The optional
            // YUV computational stream is not enabled until it has passed real-device
            // timestamp/session validation. This prevents an advertised-but-broken YUV
            // topology from making the physical shutter appear dead.
            diagnostics.info("TAKE_PICTURE plan=${plan.intent} frames=${plan.frameCount} yuvEnabled=$yuvCaptureEnabled yuvReader=${yuvCaptureReader != null} jpegReader=${imageReader != null}")

            val computationalCaptureAvailable =
                onCapturedYuv != null && plan.frameCount > 1 && yuvCaptureEnabled && yuvCaptureReader != null

            // Application-operated ZSL is the low-latency still path for ordinary PHOTO/NIGHT.
            // HDR/vendor-extension captures deliberately bypass it because they require a fresh
            // bracket or the OEM extension pipeline.
            if (ENABLE_ZSL_STILL_CAPTURE && zslEnabled && zslSession != null &&
                (plan.intent == com.rvh.camera.imaging.CaptureIntent.PHOTO ||
                    plan.intent == com.rvh.camera.imaging.CaptureIntent.NIGHT) &&
                !plan.preferVendorExtension &&
                onCapturedRaw == null
            ) {
                val zsl = zslSession ?: return@post
                val referenceTimestamp = latestPreviewMetadata?.timestampNs

                // When the planner asks for temporal fusion, do not throw the ZSL frames away
                // by immediately asking the HAL for one JPEG. Promote the retained full-resolution
                // YUV frames directly into our computational pipeline. This is the critical bridge
                // between true application-operated ZSL and RVH's own multi-frame imaging stack.
                if (onCapturedYuv != null && plan.frameCount > 1) {
                    val frames = zsl.takeFramesForProcessing(
                        referenceTimestampNs = referenceTimestamp,
                        desiredCount = plan.frameCount.coerceIn(2, 3),
                        sceneMotionScore = latestSceneMotionScore,
                    )
                    if (frames.size >= 2) {
                        captureInFlight.set(false)
                        onCapturedYuv.invoke(frames, plan, null)
                        return@post
                    }
                    frames.forEach { it.close() }
                }

                // For a single-frame PHOTO/NIGHT capture, use HAL reprocessing. It preserves the
                // sensor result that belongs to the selected ZSL image while avoiding unnecessary
                // CPU copying when temporal fusion is not justified.
                val target = zsl.selectBestForShutter(referenceTimestamp, latestSceneMotionScore)
                if (target != null && zsl.reprocess(
                        frame = target,
                        jpegOrientation = jpegOrientation(),
                        configure = { builder -> applyHighQualityStillProcessing(builder) },
                        onCompleted = { captureInFlight.set(false) },
                        onFailed = { message ->
                            val fallbackSession = session
                            if (fallbackSession != null) {
                                captureJpeg(device, fallbackSession, onCaptured, onError)
                            } else {
                                captureInFlight.set(false)
                                onError(message)
                            }
                        },
                    )
                ) {
                    return@post
                }
                // If ZSL cannot safely submit, continue through the ordinary capture path.
            }

            // HDR has a special output-quality requirement: if the device exposes a native
            // Camera2 Extension JPEG_R path, take it before entering our RAW branch. JPEG_R is
            // the platform Ultra HDR still format, so this preserves the HDR gain map instead of
            // reducing the result to an ordinary 8-bit JPEG at the end of the custom RAW path.
            // If the extension is unavailable or fails, the regular computational RAW pipeline
            // remains the next-best path.
            if (plan.intent == CaptureIntent.HDR &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                plan.preferVendorExtension &&
                tryCaptureWithVendorExtension(device, plan, onCaptured, onError)) {
                return@post
            }

            // BUILD 002 device-validation priority: when the Spark 30C exposes a working
            // full-resolution YUV burst topology, RVH computational capture is the primary
            // PHOTO/NIGHT path. The device probe confirms BURST_CAPTURE, while the probe's
            // RAW test is not a reliable enough gate to make RAW the first shutter path.
            // Keeping YUV ahead of RAW lets this build test the thing that matters most:
            // one physical shutter -> RVH multi-frame quality -> saved photo.
            if (computationalCaptureAvailable &&
                (plan.intent == CaptureIntent.PHOTO || plan.intent == CaptureIntent.NIGHT)) {
                val proceed = {
                    startYuvBurst(device, captureSession, plan, onCaptured, onCapturedYuv!!, onError)
                }
                if (CaptureTimingPolicy.needsAePrecapture(plan, hardwareProfile)) {
                    runAePrecapture(device, captureSession, proceed, onError)
                } else {
                    proceed()
                }
                return@post
            }

            // RAW remains available as a sensor-domain enhancement path for future explicit
            // RAW captures, but it no longer blocks the primary PHOTO/NIGHT quality validation.
            if (onCapturedRaw != null && rawCaptureEnabled && rawReader != null) {
                captureRawSequenceAndJpeg(device, captureSession, plan, onCaptured, onCapturedRaw, onError)
                return@post
            }

            // Prefer the OEM multi-frame HDR/Night algorithm when the planner explicitly
            // selected it and RVH's own computational YUV path was not selected above.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                plan.preferVendorExtension &&
                tryCaptureWithVendorExtension(device, plan, onCaptured, onError)) {
                return@post
            }

            if (computationalCaptureAvailable) {
                val proceed = {
                    startYuvBurst(device, captureSession, plan, onCaptured, onCapturedYuv!!, onError)
                }
                if (CaptureTimingPolicy.needsAePrecapture(plan, hardwareProfile)) {
                    runAePrecapture(device, captureSession, proceed, onError)
                } else {
                    proceed()
                }
            } else {
                // No optional computational output: take the same direct JPEG path that
                // already proved reliable on the Spark 30C.
                captureJpeg(device, captureSession, onCaptured, onError)
            }
        }
    }

    /**
     * Uses the device manufacturer's HDR/Night multi-frame pipeline when Camera2 Extensions
     * exposes a compatible still output. The regular CameraCaptureSession is replaced for the
     * duration of the extension capture and rebuilt immediately afterwards.
     *
     * This is intentionally a best-effort quality path: extension configuration/capture failure
     * never strands the shutter. In that case the normal JPEG capture is restored and executed.
     */
    @android.annotation.SuppressLint("NewApi")
    private fun tryCaptureWithVendorExtension(
        device: CameraDevice,
        plan: CapturePlan,
        onCaptured: (ByteArray, FrameMetadata) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val id = cameraId ?: return false
        val regularSession = session ?: return false
        val extension = when (plan.intent) {
            com.rvh.camera.imaging.CaptureIntent.NIGHT ->
                CameraExtensionCharacteristics.EXTENSION_NIGHT
            com.rvh.camera.imaging.CaptureIntent.HDR ->
                CameraExtensionCharacteristics.EXTENSION_HDR
            else -> return false
        }

        val extensionCharacteristics = try {
            manager.getCameraExtensionCharacteristics(id)
        } catch (_: Exception) {
            return false
        }
        if (extension !in extensionCharacteristics.supportedExtensions) return false

        // On Android 14+ prefer JPEG_R for vendor HDR when the extension advertises it.
        // JPEG_R is the platform's Ultra HDR still format: it remains JPEG-compatible while
        // carrying a gain map for HDR-capable displays. Night stays on ordinary JPEG because
        // its primary goal is noise suppression rather than expanded display dynamic range.
        val preferredFormat = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            plan.intent == com.rvh.camera.imaging.CaptureIntent.HDR
        ) {
            val ultraHdrSizes = runCatching {
                extensionCharacteristics.getExtensionSupportedSizes(
                    extension,
                    android.graphics.ImageFormat.JPEG_R,
                )
            }.getOrDefault(emptyList())
            if (ultraHdrSizes.isNotEmpty()) android.graphics.ImageFormat.JPEG_R
            else android.graphics.ImageFormat.JPEG
        } else {
            android.graphics.ImageFormat.JPEG
        }

        val supportedSizes = try {
            extensionCharacteristics.getExtensionSupportedSizes(extension, preferredFormat)
        } catch (_: Exception) {
            emptyList()
        }
        if (supportedSizes.isEmpty()) return false

        // Prefer the largest extension-supported size that does not exceed the camera's normal
        // still size. If the extension advertises only larger sizes, use its smallest supported
        // size rather than silently disabling the vendor algorithm.
        val captureSize = supportedSizes
            .filter { it.width.toLong() * it.height.toLong() <= jpegSize.width.toLong() * jpegSize.height.toLong() }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: supportedSizes.minByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return false

        // The extension session replaces the regular capture session. Keep the existing regular
        // session object only as a marker; CameraDevice.createExtensionSession() closes it.
        try { regularSession.stopRepeating() } catch (_: Exception) {}
        try { regularSession.close() } catch (_: Exception) {}
        session = null

        val extensionReader = try {
            ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                preferredFormat,
                2,
            )
        } catch (_: Exception) {
            // The regular session has already been closed, so ownership of this shutter now
            // belongs to the extension fallback path. Rebuild the session and capture normally.
            recreateRegularSessionAfterExtension(device) {
                session?.let { captureJpeg(device, it, onCaptured, onError) }
                    ?: run { captureInFlight.set(false); onError("Camera session could not be restored") }
            }
            return true
        }

        val finished = AtomicBoolean(false)
        val resultBytes = arrayOfNulls<ByteArray>(1)
        val resultMetadata = arrayOfNulls<FrameMetadata>(1)

        fun restoreAndDeliver(fallback: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            val bytes = resultBytes[0]
            val metadata = resultMetadata[0] ?: latestPreviewMetadata ?: FrameMetadata(
                timestampNs = System.nanoTime(),
                rotationDegrees = jpegOrientation(),
            )
            try { extensionSession?.close() } catch (_: Exception) {}
            extensionSession = null
            try { extensionReader.close() } catch (_: Exception) {}

            if (bytes != null && !fallback) {
                captureInFlight.set(false)
                onCaptured(bytes, metadata)
                // Recreate the normal preview/capture session after the extension result has been
                // handed off. The extension's JPEG is already fully processed by the OEM stack.
                recreateRegularSessionAfterExtension(device, onError = onError)
            } else {
                recreateRegularSessionAfterExtension(device) {
                    captureJpeg(device, session ?: return@recreateRegularSessionAfterExtension, onCaptured, onError)
                }
            }
        }

        extensionReader.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage()?.use { image ->
                    val buffer = image.planes.firstOrNull()?.buffer ?: return@use
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    resultBytes[0] = bytes
                    resultMetadata[0] = latestPreviewMetadata?.copy(
                        timestampNs = image.timestamp,
                        rotationDegrees = jpegOrientation(),
                    ) ?: FrameMetadata(
                        timestampNs = image.timestamp,
                        rotationDegrees = jpegOrientation(),
                    )
                }
            } catch (_: Exception) {
                resultBytes[0] = null
            }
            if (resultBytes[0] != null) {
                restoreAndDeliver(fallback = false)
            }
        }, handler)

        val executor = java.util.concurrent.Executor { command -> handler.post(command) }
        val stateCallback = object : CameraExtensionSession.StateCallback() {
            override fun onConfigured(newSession: CameraExtensionSession) {
                extensionSession = newSession
                try {
                    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    requestBuilder.addTarget(extensionReader.surface)
                    requestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                    // Extension implementations may expose only a subset of request controls.
                    // JPEG_ORIENTATION is applied only if the extension explicitly advertises it.
                    if (extensionCharacteristics.getAvailableCaptureRequestKeys(extension)
                            .contains(CaptureRequest.JPEG_ORIENTATION)) {
                        requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                    }
                    newSession.capture(
                        requestBuilder.build(),
                        executor,
                        object : CameraExtensionSession.ExtensionCaptureCallback() {
                            override fun onCaptureFailed(
                                session: CameraExtensionSession,
                                request: CaptureRequest,
                            ) {
                                restoreAndDeliver(fallback = true)
                            }

                            override fun onCaptureSequenceAborted(
                                session: CameraExtensionSession,
                                sequenceId: Int,
                            ) {
                                restoreAndDeliver(fallback = true)
                            }

                            override fun onCaptureSequenceCompleted(
                                session: CameraExtensionSession,
                                sequenceId: Int,
                            ) {
                                // The image buffer may arrive just before or just after this
                                // callback. Give ImageReader a short window before falling back.
                                handler.postDelayed({
                                    if (resultBytes[0] == null) restoreAndDeliver(fallback = true)
                                }, EXTENSION_RESULT_GRACE_MS)
                            }
                        },
                    )
                } catch (_: Exception) {
                    restoreAndDeliver(fallback = true)
                }
            }

            override fun onConfigureFailed(session: CameraExtensionSession) {
                restoreAndDeliver(fallback = true)
            }

            override fun onClosed(session: CameraExtensionSession) {
                if (extensionSession === session) extensionSession = null
            }
        }

        return try {
            extensionSession = null
            val outputs = arrayListOf(OutputConfiguration(extensionReader.surface))
            val config = ExtensionSessionConfiguration(extension, outputs, executor, stateCallback)
            device.createExtensionSession(config)
            true
        } catch (_: Exception) {
            try { extensionReader.close() } catch (_: Exception) {}
            // Session ownership has already moved away from the regular session. Restore it and
            // complete this shutter through the reliable JPEG path rather than returning false to
            // the caller with a stale CameraCaptureSession reference.
            recreateRegularSessionAfterExtension(device) {
                session?.let { captureJpeg(device, it, onCaptured, onError) }
                    ?: run { captureInFlight.set(false); onError("Camera extension could not be started") }
            }
            true
        }
    }

    private fun recreateRegularSessionAfterExtension(
        device: CameraDevice,
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) {
        if (closed.get()) return
        handler.post {
            createSession(
                device = device,
                onReady = { onReady?.invoke() },
                onError = { message -> onError?.invoke(message) },
            )
        }
    }

    private fun runAePrecapture(
        device: CameraDevice,
        captureSession: CameraCaptureSession,
        proceed: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val preview = previewSurface
        if (preview == null) {
            proceed()
            return
        }

        var settled = false
        var callbackCount = 0
        fun finish() {
            if (settled) return
            settled = true
            proceed()
        }

        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }.build()

            captureSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    callbackCount++
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        callbackCount >= 2
                    ) {
                        finish()
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    // A metering checkpoint is an optimization, never a reason to lose a photo.
                    finish()
                }
            }, handler)

            handler.postDelayed({ finish() }, AE_PRECAPTURE_TIMEOUT_MS)
        } catch (_: Exception) {
            // Fall back immediately. The still request remains the source of truth.
            finish()
        }
    }

    private fun captureRawSequenceAndJpeg(
        device: CameraDevice,
        captureSession: CameraCaptureSession,
        plan: CapturePlan,
        onCapturedJpeg: (ByteArray, FrameMetadata) -> Unit,
        onCapturedRaw: (List<com.rvh.camera.imaging.RawSensorFrame>, CapturePlan) -> Unit,
        onError: (String) -> Unit,
    ) {
        val jpeg = imageReader?.surface
        val raw = rawReader?.surface
        if (jpeg == null || raw == null) {
            captureInFlight.set(false)
            onError("RAW outputs unavailable")
            return
        }

        val desired = plan.frameCount.coerceIn(1, 4)
        val completed = ArrayList<Pair<Long, FrameMetadata>>(desired)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        fun finishFailure(message: String) {
            if (finished.compareAndSet(false, true)) {
                // RAW is an enhancement path. If the RAW stream fails, preserve the physical
                // shutter contract with one conventional JPEG rather than losing the capture.
                captureInFlight.set(false)
                captureJpeg(device, captureSession, onCapturedJpeg, onError)
            }
        }

        fun finishSuccess() {
            if (!finished.compareAndSet(false, true)) return
            val frames = completed.sortedBy { it.first }.mapNotNull { (timestamp, metadata) ->
                val key = rawFramesByTimestamp.keys.minByOrNull { kotlin.math.abs(it - timestamp) }
                if (key != null && kotlin.math.abs(key - timestamp) <= JPEG_TIMESTAMP_TOLERANCE_NS) {
                    rawFramesByTimestamp.remove(key)?.copy(
                        metadata = metadata.copy(isRawSensorFrame = true)
                    )?.also { rawFramesByTimestamp[key]?.close() }
                } else null
            }
            if (frames.isEmpty()) {
                captureInFlight.set(false)
                onError("RAW frames unavailable")
                return
            }
            // The RAW computational result is the sole successful output here. Calling the
            // conventional JPEG callback as well would create a duplicate gallery photo.
            captureInFlight.set(false)
            onCapturedRaw(frames, plan)
        }

        try {
            val nightExposureSequence = NightExposurePlanner.plan(
                capturePlan = plan,
                metadata = latestAnalysisMetadata ?: FrameMetadata(timestampNs = 0L),
                profile = hardwareProfile,
                sceneMotionScore = latestSceneMotionScore,
            )
            val requests = (0 until desired).map { index ->
                val sequence = captureSequence.incrementAndGet()
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpeg)
                    addTarget(raw)
                    setTag(sequence)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    applyHighQualityStillProcessing(this)
                    set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    set(CaptureRequest.JPEG_THUMBNAIL_QUALITY, 100.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                }.build()
            }

            captureSession.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                    if (timestamp == null) {
                        finishFailure("RAW capture returned no timestamp")
                        return
                    }
                    synchronized(completed) {
                        if (completed.none { it.first == timestamp } && completed.size < desired) {
                            completed += timestamp to frameMetadata(result)
                        }
                        if (completed.size >= desired) finishSuccess()
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    finishFailure("RAW capture failed: ${failure.reason}")
                }
            }, handler)

            handler.postDelayed({
                if (!finished.get()) {
                    synchronized(completed) {
                        if (completed.isNotEmpty()) finishSuccess()
                        else finishFailure("RAW frame timed out")
                    }
                }
            }, 1800L)
        } catch (e: Exception) {
            finishFailure(e.message ?: "RAW capture failed")
        }
    }

    private fun captureJpeg(
        device: CameraDevice,
        captureSession: CameraCaptureSession,
        onCaptured: (ByteArray, FrameMetadata) -> Unit,
        onError: (String) -> Unit,
    ) {
        val target = imageReader?.surface ?: run { captureInFlight.set(false); onError("JPEG reader unavailable"); return }
        try {
            val sequence = captureSequence.incrementAndGet()
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(target)
                setTag(sequence)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyHighQualityStillProcessing(this)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_THUMBNAIL_QUALITY, 100.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            }.build()
            captureSession.capture(request, jpegCallback(sequence, onCaptured, onError), handler)
        } catch (e: Exception) {
            captureInFlight.set(false)
            onError(e.message ?: "Capture failed")
        }
    }

    /**
     * Ask the HAL for its highest-quality still-image processing wherever the device advertises
     * that mode. Every setting is capability-gated so this remains safe on budget/legacy Camera2
     * implementations.
     */
    private fun applyHighQualityStillProcessing(builder: CaptureRequest.Builder) {
        val c = characteristics ?: return
        applyRawTelemetryRequests(builder, c)

        val edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        if (CaptureRequest.EDGE_MODE_HIGH_QUALITY in edgeModes) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }

        val noiseModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        if (CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY in noiseModes) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        }

        // Let capable HALs perform their calibrated chromatic-aberration correction on
        // conventional processed outputs. RAW stills remain under our explicit,
        // evidence-gated Stage 37 correction path.
        val aberrationModes = c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        if (aberrationModes?.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY) == true) {
            builder.set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val distortionModes = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            if (distortionModes?.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) == true) {
                builder.set(
                    CaptureRequest.DISTORTION_CORRECTION_MODE,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hotPixelModes = c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: intArrayOf()
            if (CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY in hotPixelModes) {
                builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            }

            val shadingModes = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: intArrayOf()
            if (CaptureRequest.SHADING_MODE_HIGH_QUALITY in shadingModes) {
                builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
            }

            val tonemapModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
            if (CaptureRequest.TONEMAP_MODE_HIGH_QUALITY in tonemapModes) {
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            }
        }
    }

    private fun applyRawTelemetryRequests(
        builder: CaptureRequest.Builder,
        c: CameraCharacteristics,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val oisModes = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES)
            if (oisModes?.contains(CaptureRequest.STATISTICS_OIS_DATA_MODE_ON) == true) {
                builder.set(CaptureRequest.STATISTICS_OIS_DATA_MODE, CaptureRequest.STATISTICS_OIS_DATA_MODE_ON)
            }
        }
        val shadingModes = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
        if (shadingModes?.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true) {
            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            )
        }
    }

    private fun jpegCallback(
        sequence: Long,
        onCaptured: (ByteArray, FrameMetadata) -> Unit,
        onError: (String) -> Unit,
    ) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp == null) { captureInFlight.set(false); onError("Capture returned no sensor timestamp"); return }
            val metadata = frameMetadata(result)
            pendingByTimestamp[timestamp] = PendingCapture(sequence, metadata, onCaptured, onError)
            val bufferedTimestamp = imageBytesByTimestamp.keys.minByOrNull { kotlin.math.abs(it - timestamp) }
            if (bufferedTimestamp != null && kotlin.math.abs(bufferedTimestamp - timestamp) <= JPEG_TIMESTAMP_TOLERANCE_NS) {
                val bytes = imageBytesByTimestamp.remove(bufferedTimestamp)
                val pending = pendingByTimestamp.remove(timestamp)
                if (bytes != null && pending != null) {
                    diagnostics.info("JPEG_MATCHED_PENDING bytes=${bytes.size} ts=$timestamp")
            deliver(timestamp, bytes, pending)
                } else {
                    bytes?.let { imageBytesByTimestamp[bufferedTimestamp] = it }
                    pending?.let { pendingByTimestamp[timestamp] = it }
                }
            }
            handler.postDelayed({
                val pending = pendingByTimestamp.remove(timestamp)
                if (pending != null) {
                    imageBytesByTimestamp.remove(timestamp)
                    captureInFlight.set(false)
                    pending.onError("Capture timed out")
                }
            }, CAPTURE_TIMEOUT_MS)
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            pendingByTimestamp.entries.removeIf { it.value.sequence == sequence }
            captureInFlight.set(false)
            onError("Capture failed: ${failure.reason}")
        }
    }

    private fun startYuvBurst(
        device: CameraDevice,
        captureSession: CameraCaptureSession,
        plan: CapturePlan,
        onCapturedJpeg: (ByteArray, FrameMetadata) -> Unit,
        onCapturedYuv: (List<com.rvh.camera.imaging.Yuv420Frame>, CapturePlan, ByteArray?) -> Unit,
        onError: (String) -> Unit,
    ) {
        val target = yuvCaptureReader?.surface ?: run { captureInFlight.set(false); onError("YUV capture reader unavailable"); return }
        val shutterTimestampNs = latestPreviewMetadata?.timestampNs ?: System.nanoTime()
        frameHistory.setSelectionReferenceIso(latestAnalysisMetadata?.sensitivityIso)
        val historyCandidates = if (plan.intent == com.rvh.camera.imaging.CaptureIntent.PHOTO ||
            plan.intent == com.rvh.camera.imaging.CaptureIntent.NIGHT) {
            frameHistory.selectForCapture(
                shutterTimestampNs = shutterTimestampNs,
                count = (plan.frameCount - 1).coerceAtLeast(0),
                currentSceneMeanLuma = latestSceneAnalysis?.meanLuma,
                currentSceneSpatialLuma = latestSceneAnalysis?.spatialLuma,
                maxExposureNs = if (plan.intent == com.rvh.camera.imaging.CaptureIntent.PHOTO) 120_000_000L else 800_000_000L,
            )
        } else {
            emptyList()
        }
        val newFrameCount = (plan.frameCount - historyCandidates.size).coerceAtLeast(1)
        val effectivePlan = plan.copy(frameCount = newFrameCount)
        val sequence = captureSequenceManager.begin(effectivePlan) ?: run { captureInFlight.set(false); return }
        yuvCallbacks[sequence.sequenceId()] = onCapturedYuv
        computationalJpegReferenceCallbacks[sequence.sequenceId().toLong()] = onCapturedJpeg
        computationalJpegReferenceDelivered.remove(sequence.sequenceId().toLong())
        yuvHistoryCandidates[sequence.sequenceId()] = historyCandidates
        yuvCaptureMetadataByTimestamp.clear()
        yuvFramesByTimestamp.values.forEach { it.close() }
        yuvFramesByTimestamp.clear()
        try {
            val nightExposureSequence = NightExposurePlanner.plan(
                capturePlan = effectivePlan,
                metadata = latestAnalysisMetadata ?: FrameMetadata(timestampNs = 0L),
                profile = hardwareProfile,
                sceneMotionScore = latestSceneMotionScore,
            )
            diagnostics.info("YUV_BURST_START seq=${sequence.sequenceId()} frames=${effectivePlan.frameCount} size=${yuvCaptureConfig?.size?.width}x${yuvCaptureConfig?.size?.height}")
            val requests = (0 until effectivePlan.frameCount).map { index ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(target)
                    // Every computational shutter also produces a full-resolution JPEG companion
                    // from the same capture request. It is the immediate save fallback if RVH
                    // processing fails, times out, or rejects the processed result.
                    imageReader?.surface?.let(::addTarget)
                    set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    set(CaptureRequest.JPEG_THUMBNAIL_QUALITY, 100.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                    setTag(sequence.sequenceId().toString() + ":" + index)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    applyHighQualityStillProcessing(this)
                    val compensation = computationalAeCompensation(effectivePlan, index)
                    if (compensation != null) {
                        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
                    }
                    nightExposureSequence?.getOrNull(index)?.let { exposure ->
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exposureTimeNs)
                        set(CaptureRequest.SENSOR_SENSITIVITY, exposure.sensitivityIso)
                        set(CaptureRequest.SENSOR_FRAME_DURATION, exposure.exposureTimeNs + 1_000_000L)
                    }
                }.build()
            }
            captureSession.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    diagnostics.info("CAPTURE_COMPLETED seq=${sequence.sequenceId()} frame=${request.tag} ts=$timestamp")
                    val metadata = frameMetadata(result)
                    yuvCaptureMetadataByTimestamp[timestamp] = metadata
                    computationalJpegSequenceByTimestamp[timestamp] = sequence.sequenceId().toLong()
                    // ImageReader and CaptureResult may arrive in either order. If the JPEG
                    // arrived first, promote the buffered bytes to this computational shutter.
                    val bufferedJpegTimestamp = imageBytesByTimestamp.keys.minByOrNull { kotlin.math.abs(it - timestamp) }
                    if (bufferedJpegTimestamp != null && kotlin.math.abs(bufferedJpegTimestamp - timestamp) <= JPEG_TIMESTAMP_TOLERANCE_NS) {
                        imageBytesByTimestamp.remove(bufferedJpegTimestamp)?.let { bytes ->
                            val id = sequence.sequenceId().toLong()
                            computationalJpegBySequence[id] = bytes
                            computationalJpegMetadataBySequence[id] = metadata
                            deliverComputationalReference(id, bytes, metadata)
                        }
                    }
                    // The ImageReader may have delivered the YUV buffer first. In that case
                    // replace the placeholder metadata before attempting completion.
                    val existing = yuvFramesByTimestamp.remove(timestamp)
                    if (existing != null) {
                        yuvFramesByTimestamp[timestamp] = existing.withMetadata(metadata)
                    }
                    attemptDeliverYuvFrame(timestamp, sequence)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (!sequence.isComplete()) {
                        abortYuvSequence(sequence)
                        // Computational YUV is an enhancement path. A HAL failure must never make
                        // the physical shutter appear dead; immediately fall back to the proven
                        // JPEG still path for this shutter press.
                        captureJpeg(device, captureSession, onCapturedJpeg, onError)
                    }
                }
                override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                    handler.postDelayed({
                        if (!sequence.isComplete()) {
                            val fallback = computationalJpegBySequence.remove(sequence.sequenceId().toLong())
                            if (fallback != null) {
                                // The companion JPEG came from the same physical shutter burst.
                                // Prefer it over launching a second exposure so the saved fallback
                                // still represents exactly what the user pressed the shutter for.
                                val metadata = computationalJpegMetadataBySequence.remove(sequence.sequenceId().toLong())
                                    ?: latestPreviewMetadata
                                    ?: FrameMetadata(timestampNs = System.nanoTime())
                                abortYuvSequence(sequence)
                                onCapturedJpeg(fallback, metadata)
                            } else {
                                abortYuvSequence(sequence)
                                // Last-resort fresh JPEG if the companion buffer was not delivered.
                                captureJpeg(device, captureSession, onCapturedJpeg, onError)
                            }
                        }
                    }, YUV_CAPTURE_FALLBACK_TIMEOUT_MS)
                }
            }, handler)
        } catch (e: Exception) {
            abortYuvSequence(sequence)
            captureJpeg(device, captureSession, onCapturedJpeg, onError)
        }
    }

    private fun abortYuvSequence(sequence: CaptureSequence) {
        sequence.fail()
        captureSequenceManager.finish(sequence)
        yuvCallbacks.remove(sequence.sequenceId())
        // History candidates remain owned by the bounded history controller.
        yuvHistoryCandidates.remove(sequence.sequenceId())
        computationalJpegBySequence.remove(sequence.sequenceId().toLong())
        computationalJpegMetadataBySequence.remove(sequence.sequenceId().toLong())
        computationalJpegReferenceCallbacks.remove(sequence.sequenceId().toLong())
        computationalJpegReferenceDelivered.remove(sequence.sequenceId().toLong())
        computationalJpegSequenceByTimestamp.entries.removeIf { it.value == sequence.sequenceId().toLong() }
        yuvCaptureMetadataByTimestamp.clear()
        yuvCompletedFrames.remove(sequence.sequenceId()).orEmpty().forEach { it.close() }
        yuvFramesByTimestamp.values.forEach { it.close() }
        yuvFramesByTimestamp.clear()
        captureInFlight.set(false)
    }

    private fun attemptDeliverYuvFrame(
        timestamp: Long,
        sequence: CaptureSequence?,
    ) {
        val activeSequence = sequence ?: return
        val frame = yuvFramesByTimestamp[timestamp] ?: return
        val metadata = yuvCaptureMetadataByTimestamp[timestamp] ?: return

        // Only remove the pair once both sides of the sensor-timestamp correlation exist.
        // ImageReader and CaptureCallback are allowed to arrive in either order.
        yuvFramesByTimestamp.remove(timestamp)
        yuvCaptureMetadataByTimestamp.remove(timestamp)

        if (!activeSequence.addYuvFrame(metadata)) {
            frame.close()
            return
        }

        yuvCompletedFrames.getOrPut(activeSequence.sequenceId()) { ArrayList() }.add(frame)
        if (activeSequence.isComplete()) {
            val freshFrames = yuvCompletedFrames.remove(activeSequence.sequenceId()).orEmpty().toList()
            val callback = yuvCallbacks.remove(activeSequence.sequenceId())
            val plan = activeSequence.plan()
            val historyCandidates = yuvHistoryCandidates.remove(activeSequence.sequenceId()).orEmpty()

            // Warm-start computational photography from the most recent high-resolution frames.
            // For PHOTO/NIGHT this can reduce the number of newly captured frames, lowering
            // shutter latency while retaining a temporal candidate for alignment/fusion. HDR never
            // uses history because its bracket must come from the same shutter event.
            val frames = (historyCandidates + freshFrames).distinctBy { it.metadata.timestampNs }
            val processingPlan = plan.copy(frameCount = frames.size.coerceAtLeast(1))

            // Keep newly acquired frames available as a bounded temporal history for the next
            // shutter. The history owns these packed arrays; close() is intentionally a no-op on
            // Yuv420Frame, so the processing callback can consume the same immutable frame objects.
            if (plan.intent == com.rvh.camera.imaging.CaptureIntent.PHOTO ||
                plan.intent == com.rvh.camera.imaging.CaptureIntent.NIGHT) {
                freshFrames.forEach { frame ->
                    val quality = FrameHistoryQualityEstimator.estimate(
                        metadata = frame.metadata,
                        referenceIso = latestAnalysisMetadata?.sensitivityIso,
                        referenceExposureNs = latestAnalysisMetadata?.exposureTimeNs,
                    )
                    frameHistory.offer(
                        frame,
                        quality = quality,
                        sceneMeanLuma = latestSceneAnalysis?.meanLuma,
                        sceneSpatialLuma = latestSceneAnalysis?.spatialLuma,
                    )
                }
            }

            val fallbackJpeg = computationalJpegBySequence.remove(activeSequence.sequenceId().toLong())
            computationalJpegMetadataBySequence.remove(activeSequence.sequenceId().toLong())
            computationalJpegReferenceCallbacks.remove(activeSequence.sequenceId().toLong())
            computationalJpegReferenceDelivered.remove(activeSequence.sequenceId().toLong())
            computationalJpegSequenceByTimestamp.entries.removeIf { it.value == activeSequence.sequenceId().toLong() }
            captureSequenceManager.finish(activeSequence)
            captureInFlight.set(false)
            if (callback != null) {
                callback(frames, processingPlan, fallbackJpeg)
            } else {
                // No consumer: do not retain frames unnecessarily.
                freshFrames.forEach { frame ->
                    // History may own this object; clear only if it was accepted.
                    if (frameHistory.snapshot().none { it === frame }) frame.close()
                }
            }
        }
    }

    private val AE_PRECAPTURE_TIMEOUT_MS = 180L
    private val JPEG_TIMESTAMP_TOLERANCE_NS = 500_000_000L
    private val YUV_CAPTURE_FALLBACK_TIMEOUT_MS = 1_200L

    private val yuvCompletedFrames = HashMap<Long, MutableList<com.rvh.camera.imaging.Yuv420Frame>>()
    private val yuvCallbacks = HashMap<Long, (List<com.rvh.camera.imaging.Yuv420Frame>, CapturePlan, ByteArray?) -> Unit>()
    private val yuvHistoryCandidates = HashMap<Long, List<com.rvh.camera.imaging.Yuv420Frame>>()
    /** JPEG companion produced by the same physical shutter burst as computational YUV. */
    private val computationalJpegSequenceByTimestamp = ConcurrentHashMap<Long, Long>()
    private val computationalJpegBySequence = ConcurrentHashMap<Long, ByteArray>()
    private val computationalJpegMetadataBySequence = ConcurrentHashMap<Long, FrameMetadata>()
    private val computationalJpegReferenceCallbacks = ConcurrentHashMap<Long, (ByteArray, FrameMetadata) -> Unit>()
    private val computationalJpegReferenceDelivered = ConcurrentHashMap.newKeySet<Long>()


    private fun frameMetadata(result: TotalCaptureResult): FrameMetadata {
        val c = characteristics
        val ois = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result.get(CaptureResult.STATISTICS_OIS_SAMPLES)
        } else null
        val lensShading = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val shadingValues = lensShading?.let { map ->
            FloatArray(map.getGainFactorCount()).also { map.copyGainFactors(it, 0) }
        }
        val cfa = c?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.let {
            when (it) {
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> 0
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> 1
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> 2
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> 3
                else -> null
            }
        }
        val whiteLevel = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: c?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        return FrameMetadata(
            timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L,
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            lensAperture = result.get(CaptureResult.LENS_APERTURE),
            focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            frameNumber = result.frameNumber,
            rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
            oisDataAvailable = ois != null,
            oisTimestampsNs = ois?.map { it.timestamp }?.toLongArray(),
            oisXShifts = ois?.map { it.xshift }?.toFloatArray(),
            oisYShifts = ois?.map { it.yshift }?.toFloatArray(),
            rotationDegrees = jpegOrientation(),
            sensorNoiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { metadataValuesToFloatArray(it) },
            sensorNeutralPoint = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { metadataValuesToFloatArray(it) },
            sensorColorTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { metadataValuesToFloatArray(it) },
            sensorColorGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
                floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
            },
            sensorGreenSplit = result.get(CaptureResult.SENSOR_GREEN_SPLIT),
            sensorLensShadingMap = shadingValues,
            sensorLensShadingMapWidth = lensShading?.columnCount,
            sensorLensShadingMapHeight = lensShading?.rowCount,
            sensorLensShadingApplied = c?.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED),
            sensorCfaArrangement = cfa,
            sensorBlackLevelPattern = c?.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pattern ->
                FloatArray(4) { index -> pattern.getOffsetForIndex(index % 2, index / 2).toFloat() }
            },
            sensorDynamicBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL),
            sensorOpticalBlackRegions = c?.get(CameraCharacteristics.SENSOR_OPTICAL_BLACK_REGIONS)?.let { regions ->
                regions.flatMap { rect -> listOf(rect.left, rect.top, rect.width(), rect.height()) }.toIntArray()
            },
            sensorWhiteLevel = whiteLevel,
            sensorDynamicWhiteLevel = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL),
            sensorReferenceIlluminant1 = c?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt(),
            sensorReferenceIlluminant2 = c?.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            sensorCalibrationTransform1 = c?.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let { metadataValuesToFloatArray(it) },
            sensorCalibrationTransform2 = c?.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let { metadataValuesToFloatArray(it) },
            sensorForwardMatrix1 = c?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let { metadataValuesToFloatArray(it) },
            sensorForwardMatrix2 = c?.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let { metadataValuesToFloatArray(it) },
            lensIntrinsicCalibration = c?.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
            lensDistortion = c?.get(CameraCharacteristics.LENS_DISTORTION),
            sensorPreCorrectionActiveArray = c?.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.let {
                intArrayOf(it.left, it.top, it.width(), it.height())
            },
        )
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

    private fun computationalAeCompensation(plan: CapturePlan, index: Int): Int? =
        ExposureStrategy.compensationFor(plan, index, hardwareProfile)

    private fun createSession(device: CameraDevice, onReady: () -> Unit, onError: (String) -> Unit) {
        val preview = previewSurface ?: return fail("Preview surface unavailable", onError)
        val reader = imageReader ?: return fail("Image reader unavailable", onError)

        // A ZSL session owns the camera session and its reprocessing input surface. Always tear
        // down a previous candidate before rebuilding, especially after extension capture or a
        // device-level session rejection. This avoids recursive session creation and stale input
        // writers.
        zslSession?.close()
        zslSession = null
        zslEnabled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ReprocessingPolicy.canAttemptTrueZsl(hardwareProfile) &&
            capabilities?.yuvReprocessingSupported == true
        ) {
            val cameraCharacteristics = characteristics ?: return fail("Camera characteristics unavailable", onError)
            val candidate = ReprocessableZslSession(device, cameraCharacteristics, handler)
            val analysisSurface = yuvAnalysisReader?.surface
            if (candidate.probe(preview, reader.surface, jpegSize, analysisSurface) != null) {
                candidate.installSourceListener()
                if (candidate.open(
                        onReady = {
                            if (candidate.buildRepeatingRequest { result ->
                                    latestPreviewMetadata = frameMetadata(result)
                                } == null) {
                                candidate.close()
                                createRegularSession(device, onReady, onError)
                                return@open
                            }
                            zslSession = candidate
                            zslEnabled = true
                            session = candidate.session
                            yuvAnalysisEnabled = analysisSurface != null
                            state = CameraState.Ready
                            context.mainExecutor.execute { applyPreviewTransform() }
                            context.mainExecutor.execute(onReady)
                        },
                        onFailed = {
                            candidate.close()
                            createRegularSession(device, onReady, onError)
                        },
                    )
                ) {
                    return
                }
            }
            candidate.close()
        }

        createRegularSession(device, onReady, onError)
    }

    private fun createRegularSession(device: CameraDevice, onReady: () -> Unit, onError: (String) -> Unit) {
        val preview = previewSurface ?: return fail("Preview surface unavailable", onError)
        val reader = imageReader ?: return fail("Image reader unavailable", onError)
        val analysisReader = yuvAnalysisReader

        try {
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed.get()) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            if (yuvAnalysisEnabled) {
                                analysisReader?.surface?.let(::addTarget)
                            }
                            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            characteristics?.let { applyRawTelemetryRequests(this, it) }
                        }.build()
                        s.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult,
                            ) {
                                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    diagnostics.info("CAPTURE_COMPLETED seq=${sequence.sequenceId()} frame=${request.tag} ts=$timestamp")
                                val previewMetadata = frameMetadata(result)
                                latestPreviewMetadata = previewMetadata
                                analysisMetadataByTimestamp[timestamp] = previewMetadata
                                while (analysisMetadataByTimestamp.size > MAX_ANALYSIS_METADATA) {
                                    val oldest = analysisMetadataByTimestamp.keys.minOrNull() ?: break
                                    analysisMetadataByTimestamp.remove(oldest)
                                }
                            }
                        }, handler)
                        context.mainExecutor.execute { applyPreviewTransform() }
                        state = CameraState.Ready
                        context.mainExecutor.execute(onReady)
                    } catch (e: Exception) {
                        fail(e.message ?: "Preview failed", onError)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    fail("Failed to configure camera session", onError)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val previewOutput = OutputConfiguration(preview)
                val stillOutput = OutputConfiguration(reader.surface)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val supported = capabilities?.supportedStreamUseCases.orEmpty()
                    if (CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong() in supported) {
                        previewOutput.setStreamUseCase(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong())
                    }
                    if (CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong() in supported) {
                        stillOutput.setStreamUseCase(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong())
                    }
                }
                val outputs = ArrayList<OutputConfiguration>(3)
                outputs += previewOutput
                outputs += stillOutput
                if (rawCaptureEnabled) rawReader?.surface?.let { outputs += OutputConfiguration(it) }

                var analysisEnabled = false
                // Computational YUV is an optional enhancement target. The complete topology is
                // validated before it is enabled; if the HAL rejects it we fall back to the
                // proven preview + JPEG session without changing the physical shutter path.
                var captureYuvEnabled = false
                if (yuvCaptureReader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val yuvCaptureOutput = OutputConfiguration(yuvCaptureReader!!.surface)
                    val candidate = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs + yuvCaptureOutput,
                        java.util.concurrent.Executor { command -> handler.post(command) },
                        callback,
                    )
                    if (device.isSessionConfigurationSupported(candidate)) {
                        outputs += yuvCaptureOutput
                        captureYuvEnabled = true
                    }
                }
                if (analysisReader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val analysisOutput = OutputConfiguration(analysisReader.surface)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val supported = capabilities?.supportedStreamUseCases.orEmpty()
                        if (CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong() in supported) {
                            analysisOutput.setStreamUseCase(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong())
                        }
                    }
                    val candidate = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs + analysisOutput,
                        java.util.concurrent.Executor { command -> handler.post(command) },
                        callback,
                    )
                    if (device.isSessionConfigurationSupported(candidate)) {
                        outputs += analysisOutput
                        analysisEnabled = true
                    }
                }

                val callbackExecutor = java.util.concurrent.Executor { command -> handler.post(command) }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    callbackExecutor,
                    callback
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !device.isSessionConfigurationSupported(config)) {
                    // Optional outputs are removed from least important to most important.
                    // This guarantees that an ambitious computational topology can never take
                    // down the proven preview + JPEG path.
                    if (analysisEnabled) {
                        outputs.removeIf { it.surface == yuvAnalysisReader?.surface }
                        analysisEnabled = false
                        try { yuvAnalysisReader?.discardFreeBuffers() } catch (_: Exception) {}
                    }

                    var fallbackConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        callbackExecutor,
                        callback,
                    )

                    if (!device.isSessionConfigurationSupported(fallbackConfig) && captureYuvEnabled) {
                        outputs.removeIf { it.surface == yuvCaptureReader?.surface }
                        captureYuvEnabled = false
                        try { yuvCaptureReader?.discardFreeBuffers() } catch (_: Exception) {}
                        fallbackConfig = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            outputs,
                            callbackExecutor,
                            callback,
                        )
                    }

                    if (!device.isSessionConfigurationSupported(fallbackConfig) && rawCaptureEnabled) {
                        outputs.removeIf { it.surface == rawReader?.surface }
                        rawCaptureEnabled = false
                        try { rawReader?.discardFreeBuffers() } catch (_: Exception) {}
                        fallbackConfig = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            outputs,
                            callbackExecutor,
                            callback,
                        )
                    }

                    if (!device.isSessionConfigurationSupported(fallbackConfig)) {
                        fail("Camera does not support the selected preview/capture streams", onError)
                        return
                    }
                    yuvAnalysisEnabled = analysisEnabled
                    yuvCaptureEnabled = captureYuvEnabled
                    diagnostics.info("CAPTURE_SESSION_READY yuv=$yuvCaptureEnabled analysis=$yuvAnalysisEnabled")
                    device.createCaptureSession(fallbackConfig)
                } else {
                    yuvAnalysisEnabled = analysisEnabled
                    yuvCaptureEnabled = captureYuvEnabled
                    diagnostics.info("CAPTURE_SESSION_READY yuv=$yuvCaptureEnabled analysis=$yuvAnalysisEnabled")
                    device.createCaptureSession(config)
                }
            } else {
                yuvAnalysisEnabled = false
                yuvCaptureEnabled = false
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(preview, reader.surface), callback, handler)
            }
        } catch (e: Exception) {
            fail(e.message ?: "Session creation failed", onError)
        }
    }

    private fun deliverComputationalReference(
        sequenceId: Long,
        bytes: ByteArray,
        metadata: FrameMetadata,
    ) {
        if (!computationalJpegReferenceDelivered.add(sequenceId)) return
        computationalJpegReferenceCallbacks[sequenceId]?.invoke(bytes, metadata)
    }

    private fun onImageAvailable(timestamp: Long, bytes: ByteArray) {
        diagnostics.info("JPEG_IMAGE_AVAILABLE bytes=${bytes.size} ts=$timestamp")
        // Computational shutters deliberately request JPEG and YUV together. If this JPEG
        // belongs to such a burst, retain it as the same-shutter fallback instead of routing it
        // through the ordinary one-shot JPEG callback.
        val computationalSequence = computationalJpegSequenceByTimestamp.remove(timestamp)
            ?: computationalJpegSequenceByTimestamp.entries.minByOrNull { kotlin.math.abs(it.key - timestamp) }
                ?.takeIf { kotlin.math.abs(it.key - timestamp) <= JPEG_TIMESTAMP_TOLERANCE_NS }
                ?.let { computationalJpegSequenceByTimestamp.remove(it.key)?.also { _ -> } }
        if (computationalSequence != null) {
            val metadata = computationalJpegMetadataBySequence[computationalSequence]
                ?: latestPreviewMetadata?.copy(timestampNs = timestamp)
                ?: FrameMetadata(timestampNs = timestamp)
            computationalJpegBySequence[computationalSequence] = bytes
            computationalJpegMetadataBySequence.putIfAbsent(computationalSequence, metadata)
            // Persist the original HAL JPEG immediately and independently of the RVH pipeline.
            // This is the reference image for A/B quality validation, not the final RVH result.
            diagnostics.info("JPEG_MATCHED_COMPUTATIONAL seq=$computationalSequence bytes=${bytes.size}")
            deliverComputationalReference(computationalSequence, bytes, metadata)
            return
        }

        // Some Camera2 HALs report a small timestamp offset between the JPEG ImageReader
        // buffer and the CaptureResult. Prefer timestamp correlation, but when there is only
        // one outstanding JPEG shutter, safely associate the image with that capture instead of
        // letting the physical shutter time out and losing the photo.
        val pending = pendingByTimestamp.remove(timestamp)
            ?: takeNearestPending(timestamp)
            ?: if (pendingByTimestamp.size == 1) {
                val entry = pendingByTimestamp.entries.firstOrNull()
                entry?.let { pendingByTimestamp.remove(it.key) }
            } else null

        if (pending != null) {
            diagnostics.info("JPEG_MATCHED_PENDING bytes=${bytes.size} ts=$timestamp")
            deliver(timestamp, bytes, pending)
            return
        }

        imageBytesByTimestamp[timestamp] = bytes
        recentTimestamps.addLast(timestamp)
        while (recentTimestamps.size > MAX_BUFFERED_IMAGES) {
            imageBytesByTimestamp.remove(recentTimestamps.removeFirst())
        }
    }

    /** Handles small HAL timestamp skew between ImageReader and CaptureResult. */
    private fun takeNearestPending(timestamp: Long): PendingCapture? {
        val entry = pendingByTimestamp.entries.minByOrNull { kotlin.math.abs(it.key - timestamp) } ?: return null
        return if (kotlin.math.abs(entry.key - timestamp) <= JPEG_TIMESTAMP_TOLERANCE_NS) {
            pendingByTimestamp.remove(entry.key)
        } else null
    }

    private fun deliver(timestamp: Long, bytes: ByteArray, pending: PendingCapture) {
        imageBytesByTimestamp.remove(timestamp)
        captureInFlight.set(false)
        pending.onCaptured(bytes, pending.metadata)
    }

    private fun jpegOrientation(): Int {
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
        }
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - degrees + 360) % 360
    }

    fun updatePreviewTransform() {
        if (closed.get()) return
        context.mainExecutor.execute { applyPreviewTransform() }
    }

    private fun applyPreviewTransform() {
        val texture = previewTexture ?: return
        val viewWidth = texture.width
        val viewHeight = texture.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
        }
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        // IMPORTANT: TextureView/SurfaceTexture already compensates the camera sensor's
        // natural orientation. We must NOT rotate by SENSOR_ORIENTATION here, or a rear
        // camera with a 90-degree sensor gets rotated a second time and the scene appears
        // sideways. Only compensate for the actual display rotation.
        //
        // Keep the preview as a COVER transform so the full screen remains filled, which
        // was the correct behaviour in the device-fix-3 build. Only the orientation logic
        // is changed here; capture/save code is deliberately untouched.
        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val sourceWidth = previewSize.width.toFloat()
        val sourceHeight = previewSize.height.toFloat()

        val rotatedWidth = if (displayDegrees == 90 || displayDegrees == 270) {
            sourceHeight
        } else {
            sourceWidth
        }
        val rotatedHeight = if (displayDegrees == 90 || displayDegrees == 270) {
            sourceWidth
        } else {
            sourceHeight
        }

        val scale = maxOf(
            viewWidth.toFloat() / rotatedWidth,
            viewHeight.toFloat() / rotatedHeight,
        )
        matrix.postScale(scale, scale, centerX, centerY)

        if (displayDegrees != 0) {
            // TextureView content is rotated in the opposite direction to the display
            // rotation so that it follows the device display orientation.
            matrix.postRotate(-displayDegrees.toFloat(), centerX, centerY)
        }

        texture.setTransform(matrix)
    }

    private fun chooseCaptureSize(sizes: Array<Size>): Size {
        if (sizes.isEmpty()) return Size(1920, 1080)
        val preferred = sizes.filter { it.width.toLong() * it.height.toLong() >= 8_000_000L }
        return (preferred.ifEmpty { sizes.asList() }).maxBy { it.width.toLong() * it.height.toLong() }
    }

    private fun choosePreviewSize(sizes: Array<Size>, viewWidth: Int, viewHeight: Int): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val targetW = viewWidth.coerceAtLeast(720)
        val targetH = viewHeight.coerceAtLeast(720)
        val targetRatio = maxOf(targetW, targetH).toFloat() / minOf(targetW, targetH).toFloat()
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1920 }
            .ifEmpty { sizes.asList() }
        val targetLong = minOf(maxOf(targetW, targetH), 1920)
        val targetShort = minOf(minOf(targetW, targetH), 1080)
        return candidates.minBy { size ->
            val sizeRatio = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height).toFloat()
            val ratioPenalty = kotlin.math.abs(sizeRatio - targetRatio)
            val sizeLong = maxOf(size.width, size.height)
            val sizeShort = minOf(size.width, size.height)
            val undersizePenalty = if (sizeLong < targetLong || sizeShort < targetShort) 250f else 0f
            val sizePenalty = kotlin.math.abs(sizeLong - targetLong) + kotlin.math.abs(sizeShort - targetShort)
            ratioPenalty * 2500f + sizePenalty + undersizePenalty
        }
    }

    private fun findRearCamera(): String? = manager.cameraIdList.firstOrNull { id ->
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private fun fail(message: String, onError: (String) -> Unit) {
        state = CameraState.Error(message)
        onError(message)
    }

    fun stopPreview() {
        handler.post {
            if (closed.get()) return@post
            cleanupCameraResources()
            state = CameraState.Initializing
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            cleanupCameraResources()
            state = CameraState.Initializing
            thread.quitSafely()
        }
    }

    private fun cleanupCameraResources(extraDevice: CameraDevice? = null) {
        try { zslSession?.close() } catch (_: Exception) {}
        zslSession = null
        zslEnabled = false
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { extensionSession?.close() } catch (_: Exception) {}
        extensionSession = null
        try { camera?.close() } catch (_: Exception) {}
        if (extraDevice != null && extraDevice !== camera) {
            try { extraDevice.close() } catch (_: Exception) {}
        }
        try { imageReader?.close() } catch (_: Exception) {}
        try { rawReader?.close() } catch (_: Exception) {}
        rawReader = null
        rawCaptureEnabled = false
        rawFramesByTimestamp.values.forEach { it.close() }
        rawFramesByTimestamp.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { yuvAnalysisReader?.discardFreeBuffers() } catch (_: Exception) {}
        }
        try { yuvAnalysisReader?.close() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { yuvCaptureReader?.discardFreeBuffers() } catch (_: Exception) {}
        }
        try { yuvCaptureReader?.close() } catch (_: Exception) {}
        yuvCaptureReader = null
        yuvCaptureConfig = null
        yuvCaptureEnabled = false
        yuvCaptureMetadataByTimestamp.clear()
        yuvFramesByTimestamp.values.forEach { it.close() }
        yuvFramesByTimestamp.clear()
        yuvCompletedFrames.values.flatten().forEach { it.close() }
        yuvCompletedFrames.clear()
        yuvCallbacks.clear()
        yuvHistoryCandidates.clear()
        try { previewSurface?.release() } catch (_: Exception) {}
        session = null
        camera = null
        imageReader = null
        yuvAnalysisReader = null
        yuvAnalysisConfig = null
        yuvAnalysisEnabled = false
        latestSceneAnalysis = null
        latestAnalysisMetadata = null
        latestSceneMotionScore = 0f
        latestPreviewMetadata = null
        previewSurface = null
        previewTexture = null
        captureInFlight.set(false)
        pendingByTimestamp.clear()
        imageBytesByTimestamp.clear()
        computationalJpegSequenceByTimestamp.clear()
        computationalJpegBySequence.clear()
        computationalJpegMetadataBySequence.clear()
        analysisMetadataByTimestamp.clear()
        captureSequenceManager.clear()
        recentTimestamps.clear()
        cameraId = null
        characteristics = null
        openInProgress.set(false)
    }

    private data class PendingCapture(
        val sequence: Long,
        val metadata: FrameMetadata,
        val onCaptured: (ByteArray, FrameMetadata) -> Unit,
        val onError: (String) -> Unit
    )

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 3_000L
        const val MAX_BUFFERED_IMAGES = 4
        const val MAX_ANALYSIS_METADATA = 32
        const val EXTENSION_RESULT_GRACE_MS = 250L

        // BUILD 001 device-validation gate: the application-operated ZSL reprocess path is
        // kept initialized for future computational capture, but ordinary shutter presses use
        // the proven direct JPEG path until ZSL output delivery is validated on the target HAL.
        // This prevents a successful ZSL reprocess callback from clearing captureInFlight without
        // delivering a JPEG to MainActivity/PhotoSaver.
        const val ENABLE_ZSL_STILL_CAPTURE = false
    }
}
