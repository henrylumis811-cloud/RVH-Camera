package com.rvh.camera.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.rvh.camera.diagnostics.DiagnosticLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoSaver(private val resolver: ContentResolver, context: android.content.Context? = null) {
    private val diagTag = "RVH_DIAG"
    private val diagnostics = context?.let { DiagnosticLogger.get(it) }
    fun saveJpeg(bytes: ByteArray, prefix: String = "RVH_"): Result<Uri> {
        if (bytes.isEmpty()) return Result.failure(IllegalArgumentException("JPEG payload is empty"))
        var uri: Uri? = null
        return runCatching {
            val name = "${prefix}${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
            diagnostics?.info("MEDIASTORE_INSERT name=$name bytes=${bytes.size}") ?: Log.i(diagTag, "MEDIASTORE_INSERT name=$name bytes=${bytes.size}")
            uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RVH Camera")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
            ) ?: throw IllegalStateException("MediaStore insert returned null")

            diagnostics?.info("MEDIASTORE_URI uri=$uri") ?: Log.i(diagTag, "MEDIASTORE_URI uri=$uri")
            resolver.openOutputStream(uri!!, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IllegalStateException("Unable to open photo output")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri!!,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            diagnostics?.info("MEDIASTORE_FINALIZED uri=$uri") ?: Log.i(diagTag, "MEDIASTORE_FINALIZED uri=$uri")
            uri!!
        }.onFailure {
            diagnostics?.error("MEDIASTORE_FAIL uri=$uri", it) ?: Log.e(diagTag, "MEDIASTORE_FAIL uri=$uri", it)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }


    fun deletePhoto(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    fun finalizePhoto(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
        }
    }
}
