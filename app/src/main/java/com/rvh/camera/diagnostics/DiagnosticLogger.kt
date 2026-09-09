package com.rvh.camera.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phone-only diagnostic recorder. Keeps a small rolling text report on-device. */
class DiagnosticLogger private constructor(private val context: Context) {
    companion object {
        private const val TAG = "RVH_DIAG"
        private const val MAX_CHARS = 120_000
        @Volatile private var instance: DiagnosticLogger? = null

        fun get(context: Context): DiagnosticLogger = instance ?: synchronized(this) {
            instance ?: DiagnosticLogger(context.applicationContext).also { instance = it }
        }
    }

    private val lock = Any()
    private val directory = File(context.filesDir, "rvh_diagnostics")
    private val latestFile = File(directory, "RVH_DIAGNOSTIC.txt")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        directory.mkdirs()
        if (!latestFile.exists()) {
            event("DIAGNOSTIC_READY sdk=${Build.VERSION.SDK_INT} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
        }
    }

    fun info(message: String) = write("INFO", message, null)
    fun warn(message: String) = write("WARN", message, null)
    fun error(message: String, throwable: Throwable? = null) = write("ERROR", message, throwable)

    fun event(message: String) = info(message)

    fun clear() {
        synchronized(lock) {
            latestFile.delete()
        }
        event("DIAGNOSTIC_CLEARED")
    }

    fun read(): String = synchronized(lock) {
        if (!latestFile.exists()) "No diagnostic events recorded yet." else latestFile.readText()
    }

    fun reportFile(): File = synchronized(lock) {
        directory.mkdirs()
        if (!latestFile.exists()) event("REPORT_REQUESTED")
        latestFile
    }

    private fun write(level: String, message: String, throwable: Throwable?) {
        val line = "${stamp.format(Date())} [$level] $message"
        when (level) {
            "ERROR" -> Log.e(TAG, line, throwable)
            "WARN" -> Log.w(TAG, line, throwable)
            else -> Log.i(TAG, line)
        }
        synchronized(lock) {
            directory.mkdirs()
            val existing = if (latestFile.exists()) latestFile.readText() else ""
            val detail = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
            val combined = (existing + line + detail + "\n").takeLast(MAX_CHARS)
            latestFile.writeText(combined)
        }
    }
}
