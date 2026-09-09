package com.rvh.camera

import kotlin.math.pow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.FileProvider
import com.rvh.camera.diagnostics.DiagnosticLogger
import java.io.File
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.rvh.camera.camera.CameraController
import com.rvh.camera.storage.PhotoSaver
import com.rvh.camera.imaging.ImageProcessor
import com.rvh.camera.imaging.ComputationalPhotoPipeline
import com.rvh.camera.imaging.RawComputationalCapture
import com.rvh.camera.imaging.RawUltraHdrEncoder
import com.rvh.camera.imaging.JpegImageFrame
import com.rvh.camera.imaging.ProcessingRequest
import com.rvh.camera.ui.CameraScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val diagTag = "RVH_DIAG"
    private lateinit var diagnostics: DiagnosticLogger
    private lateinit var controller: CameraController
    private lateinit var saver: PhotoSaver
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var computationalPipeline: ComputationalPhotoPipeline
    private lateinit var rawComputationalCapture: RawComputationalCapture
    private lateinit var rawUltraHdrEncoder: RawUltraHdrEncoder
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rvh-photo-io").apply { isDaemon = true }
    }
    private var permissionGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true
        permissionGranted = cameraGranted
        if (!cameraGranted) toast("Camera permission is required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val storagePermission = if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        permissionGranted = cameraPermission && storagePermission
        diagnostics = DiagnosticLogger.get(this)
        controller = CameraController(this)
        diagnostics.info("APP_START sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        saver = PhotoSaver(contentResolver, this)
        imageProcessor = ImageProcessor()
        computationalPipeline = ComputationalPhotoPipeline()
        rawComputationalCapture = RawComputationalCapture()
        rawUltraHdrEncoder = RawUltraHdrEncoder()

        setContent {
            CameraScreen(
                permissionGranted = permissionGranted,
                controller = controller,
                onCapture = {
                    diagnostics.info("SHUTTER_PRESS")
                    controller.takePicture(
                        onCaptured = { bytes, metadata ->
                            diagnostics.info("HAL_JPEG_CALLBACK bytes=${bytes.size} ts=${metadata.timestampNs}")
                            // The camera HAL JPEG is always retained as the hard save fallback.
                            // Never let an RVH processing exception turn a successful shutter
                            // capture into a missing gallery photo.
                            // This callback is the HAL reference JPEG. It is intentionally
                            // saved separately so the first device-quality test can compare the
                            // camera's own still processing against the RVH computational result.
                            saveJpeg(bytes, prefix = "RVH_REFERENCE_")
                        },
                        onCapturedRaw = { frames, _ ->
                            ioExecutor.execute {
                                try {
                                    val rendered = rawComputationalCapture.process(frames)
                                    frames.forEach { it.close() }
                                    if (rendered == null) {
                                        runOnUiThread { toast("RAW processing failed") }
                                    } else {
                                        val ultraHdr = rawUltraHdrEncoder.encode(rendered)
                                        if (ultraHdr != null) saveJpeg(ultraHdr)
                                        else saveRenderedSdr(rendered)
                                    }
                                } catch (e: Exception) {
                                    diagnostics.error("PIPELINE_FAIL", e)
                                    frames.forEach { it.close() }
                                    runOnUiThread { toast(e.message ?: "RAW processing failed") }
                                }
                            }
                        },
                        onCapturedYuv = { frames, plan, fallbackJpeg ->
                            diagnostics.info("YUV_CALLBACK frames=${frames.size} intent=${plan.intent} fallbackBytes=${fallbackJpeg?.size ?: 0}")
                            ioExecutor.execute {
                                try {
                                    diagnostics.info("PIPELINE_START frames=${frames.size}")
                                    val result = computationalPipeline.process(
                                        frames,
                                        ProcessingRequest(
                                            intent = plan.intent,
                                            allowMultiFrame = frames.size > 1,
                                            allowVendorExtension = plan.preferVendorExtension,
                                        )
                                    )
                                    // Save the computationally processed result when the full RVH
                                    // pipeline succeeds. This is the image-quality path under test.
                                    val encoded = result.jpegBytes()
                                    diagnostics.info("PIPELINE_DONE bytes=${encoded.size}")
                                    saveJpeg(encoded, fallbackJpeg, prefix = "RVH_COMPUTATIONAL_")
                                    frames.forEach { it.close() }
                                    if (result.frame !== frames.firstOrNull()) result.frame.close()
                                } catch (e: Exception) {
                                    frames.forEach { it.close() }
                                    // Never lose the shutter: the JPEG companion was captured in
                                    // the same burst. It becomes the guaranteed save fallback.
                                    if (fallbackJpeg != null) {
                                        saveJpeg(fallbackJpeg, prefix = "RVH_REFERENCE_")
                                    } else {
                                        runOnUiThread { toast(e.message ?: "Image processing failed") }
                                    }
                                }
                            }
                        },
                        onError = { message ->
                            diagnostics.error("CAMERA_ERROR message=$message")
                            runOnUiThread { toast(message) }
                        }
                    )
                },
                onError = { message ->
                    diagnostics.error("CAMERA_UI_ERROR message=$message")
                    runOnUiThread { toast(message) }
                },
                diagnosticText = { diagnostics.read() },
                onShareDiagnostics = { shareDiagnostics() },
                onCopyDiagnostics = { copyDiagnostics() },
                onClearDiagnostics = { diagnostics.clear() }
            )
        }

        if (!permissionGranted) {
            val permissions = if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                arrayOf(Manifest.permission.CAMERA)
            }
            permissionLauncher.launch(permissions)
        }
    }


    private fun saveRenderedSdr(rgb: com.rvh.camera.imaging.RawDevelopmentPipeline.LinearRgb) {
        ioExecutor.execute {
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(rgb.width, rgb.height, android.graphics.Bitmap.Config.ARGB_8888)
                val pixels = IntArray(rgb.width * rgb.height)
                for (i in pixels.indices) {
                    fun srgb(v: Float): Int {
                        val x = v.coerceIn(0f, 1f)
                        val t = if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
                        return (t * 255f + 0.5f).toInt().coerceIn(0, 255)
                    }
                    pixels[i] = (0xFF shl 24) or (srgb(rgb.r[i]) shl 16) or (srgb(rgb.g[i]) shl 8) or srgb(rgb.b[i])
                }
                bitmap.setPixels(pixels, 0, rgb.width, 0, 0, rgb.width, rgb.height)
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                    out.toByteArray()
                }
                bitmap.recycle()
                saveJpeg(bytes)
            } catch (e: Exception) {
                runOnUiThread { toast(e.message ?: "Unable to encode RAW result") }
            }
        }
    }

    private fun saveJpeg(bytes: ByteArray, fallback: ByteArray? = null, prefix: String = "RVH_") {
        ioExecutor.execute {
            val result = saver.saveJpeg(bytes, prefix)
            diagnostics.info("SAVE_ATTEMPT prefix=$prefix bytes=${bytes.size} success=${result.isSuccess} uri=${result.getOrNull()}")
            if (result.isSuccess) {
                runOnUiThread { toast("Photo saved") }
                return@execute
            }

            if (fallback != null && !fallback.contentEquals(bytes)) {
                val fallbackResult = saver.saveJpeg(fallback, "RVH_REFERENCE_")
                if (fallbackResult.isSuccess) {
                    runOnUiThread { toast("Photo saved (fallback)") }
                    return@execute
                }
            }

            val error = result.exceptionOrNull()
            diagnostics.error("SAVE_FAIL prefix=$prefix message=${error?.message}", error)
            val message = error?.message ?: "Unable to save photo"
            runOnUiThread { toast(message) }
        }
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("RVH Camera diagnostics", diagnostics.read()))
        diagnostics.info("REPORT_COPIED")
        toast("Diagnostic report copied")
    }

    private fun shareDiagnostics() {
        try {
            val source = diagnostics.reportFile()
            val shareDir = File(cacheDir, "shared").apply { mkdirs() }
            val shared = File(shareDir, "RVH_DIAGNOSTIC.txt")
            source.copyTo(shared, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", shared)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "RVH Camera diagnostic report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share RVH diagnostic report"))
            diagnostics.info("REPORT_SHARED")
        } catch (e: Exception) {
            diagnostics.error("REPORT_SHARE_FAILED", e)
            toast(e.message ?: "Unable to share diagnostic report")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        controller.close()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
