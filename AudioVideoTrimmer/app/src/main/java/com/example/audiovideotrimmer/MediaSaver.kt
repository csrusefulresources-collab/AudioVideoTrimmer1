package com.example.audiovideotrimmer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/**
 * Copies an exported temp file into public storage (Music/ or Movies/ → AudioVideoTrimmer)
 * so it shows up in file managers and media apps.
 */
object MediaSaver {

    const val FOLDER = "AudioVideoTrimmer"

    /** Returns a human readable location, or null on failure. */
    fun save(
        context: Context,
        source: File,
        isVideo: Boolean,
        displayName: String,
        mime: String? = null
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(context, source, isVideo, displayName, mime)
            } else {
                saveLegacy(context, source, isVideo, displayName, mime)
            }
        } catch (e: Exception) {
            AppLog.e("Save", "Could not copy the file to public storage", e)
            null
        }
    }

    private fun mimeFor(isVideo: Boolean) = if (isVideo) "video/mp4" else "audio/mp4"

    private fun folderFor(isVideo: Boolean) =
        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC

    private fun saveWithMediaStore(
        context: Context,
        source: File,
        isVideo: Boolean,
        displayName: String,
        mime: String?
    ): String? {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime ?: mimeFor(isVideo))
            put(MediaStore.MediaColumns.RELATIVE_PATH, folderFor(isVideo) + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: throw IllegalStateException("No output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            folderFor(isVideo) + "/" + FOLDER
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Android 9 (API 28): needs WRITE_EXTERNAL_STORAGE, which MainActivity requests first. */
    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        source: File,
        isVideo: Boolean,
        displayName: String,
        mime: String?
    ): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(folderFor(isVideo)), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, displayName)
        source.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mime ?: mimeFor(isVideo)),
            null
        )
        return folderFor(isVideo) + "/" + FOLDER
    }

    /** Saves a picture (for example a video frame) to Pictures/AudioVideoTrimmer. Returns the folder text or null. */
    fun saveBitmap(context: Context, bitmap: Bitmap, displayName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                ) ?: return null
                try {
                    resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        ?: throw IllegalStateException("No output stream")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Environment.DIRECTORY_PICTURES + "/" + FOLDER
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
                if (!dir.exists() && !dir.mkdirs()) return null
                val target = File(dir, displayName)
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null)
                Environment.DIRECTORY_PICTURES + "/" + FOLDER
            }
        } catch (e: Exception) {
            AppLog.e("Save", "Could not save the picture", e)
            null
        }
    }
}
