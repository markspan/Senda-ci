package io.github.markspan.sensorium.xdf

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.markspan.sensorium.lsl.LslNative
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The recording on the phone, if one is running. Every LSL outlet registers itself here when it
 * is created and passes on what it pushes, with the same time stamps, so the file holds exactly
 * what LSL consumers receive.
 */
object XdfRecording {
    @Volatile
    private var recorder: XdfRecorder? = null

    /** Starts recording to a new file in the app's own storage; returns that file. */
    fun start(context: Context, hostname: String): File {
        detach()?.close()
        val dir = (context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val file = File(dir, "Sensorium_$stamp.xdf")
        recorder = XdfRecorder(file, LslNative::localClock, hostname)
        return file
    }

    fun register(outlet: Any, info: XdfStreamInfo) {
        recorder?.addStream(outlet, info)
    }

    fun samples(outlet: Any, data: FloatArray, channels: Int, lastTimestamp: Double) {
        recorder?.samples(outlet, data, channels, lastTimestamp)
    }

    val bytesWritten: Long get() = recorder?.bytesWritten ?: 0L

    val error: Exception? get() = recorder?.error

    /**
     * Ends the recording: outlets no longer feed it. Returns its recorder, still to be closed
     * (which finishes the file and may take a moment), or null if none ran.
     */
    fun detach(): XdfRecorder? {
        val r = recorder
        recorder = null
        return r
    }

    /**
     * Moves a finished recording to Download/Sensorium, where file managers and a computer over
     * USB find it (Android 10 and later); on older Android it stays in the app's folder.
     * Returns where the file now is.
     */
    fun publish(context: Context, file: File): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return file.absolutePath
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Sensorium")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return file.absolutePath
        try {
            val target = checkNotNull(resolver.openOutputStream(uri)) { "cannot open $uri" }
            target.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 16) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: file.name
        file.delete()
        return "${Environment.DIRECTORY_DOWNLOADS}/Sensorium/$name"
    }
}
