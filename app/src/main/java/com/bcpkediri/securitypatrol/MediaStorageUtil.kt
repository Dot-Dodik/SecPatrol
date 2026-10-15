package com.bcpkediri.securitypatrol

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

object MediaStorageUtil {

    fun autoCleanWeeklyCache(context: Context) {
        try {
            val sp = context.getSharedPreferences("cache_cleaner_pref", Context.MODE_PRIVATE)
            val lastClean = sp.getLong("last_clean_timestamp", 0L)
            val now = System.currentTimeMillis()
            val oneWeekMillis = 7L * 24 * 60 * 60 * 1000

            if (now - lastClean >= oneWeekMillis) {
                thread(start = true, isDaemon = true) {
                    try {
                        val spQueue = context.getSharedPreferences("offline_reports_queue", Context.MODE_PRIVATE)
                        val pendingRaw = spQueue.getString("pending_list", "[]") ?: "[]"
                        val cacheFolders = listOf(
                            File(context.cacheDir, "checkpoints"),
                            File(context.cacheDir, "handover"),
                            File(context.cacheDir, "videos")
                        )
                        cacheFolders.forEach { folder ->
                            if (folder.exists() && folder.isDirectory) {
                                folder.listFiles()?.forEach { file ->
                                    val isPendingOffline = pendingRaw.contains(file.absolutePath)
                                    val isOlderThanWeek = (now - file.lastModified()) >= oneWeekMillis
                                    if (!isPendingOffline && isOlderThanWeek) file.delete()
                                }
                            }
                        }
                        sp.edit().putLong("last_clean_timestamp", now).commit()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    fun saveImageToPublicGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        autoCleanWeeklyCache(context)
        val filename = if (displayName.endsWith(".jpg")) displayName else "$displayName.jpg"
        var fos: OutputStream? = null
        var imageUri: Uri? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SecPatrol")
                }
                imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri != null) fos = resolver.openOutputStream(imageUri)
            } else {
                val imagesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SecPatrol")
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val image = File(imagesDir, filename)
                fos = FileOutputStream(image)
                imageUri = Uri.fromFile(image)
            }
            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                it.flush()
            }
        } catch (_: Exception) {
            imageUri = null
        }
        return imageUri
    }

    fun saveVideoToPublicGallery(context: Context, videoFile: File, displayName: String): Uri? {
        autoCleanWeeklyCache(context)
        val filename = if (displayName.endsWith(".mp4")) displayName else "$displayName.mp4"
        var fos: OutputStream? = null
        var videoUri: Uri? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SecPatrol")
                }
                videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (videoUri != null) fos = resolver.openOutputStream(videoUri)
            } else {
                val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SecPatrol")
                if (!moviesDir.exists()) moviesDir.mkdirs()
                val video = File(moviesDir, filename)
                fos = FileOutputStream(video)
                videoUri = Uri.fromFile(video)
            }
            fos?.use {
                videoFile.inputStream().copyTo(it)
                it.flush()
            }
        } catch (_: Exception) {
            videoUri = null
        }
        return videoUri
    }

    @Synchronized
    fun saveToOfflineQueue(
        context: Context,
        type: String,
        officer: String,
        note: String,
        coords: String,
        filePath: String,
        address: String = "-"
    ) {
        try {
            val sp = context.getSharedPreferences("offline_reports_queue", Context.MODE_PRIVATE)
            val raw = sp.getString("pending_list", "[]") ?: "[]"
            val arr = JSONArray(raw)

            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).optString("filePath") == filePath) return
            }

            val cleanAddress = if (address.isBlank() || address == "-") {
                LocationUtil.getFallbackPatrolArea(context)
            } else address

            val fileObj = File(filePath)
            val obj = JSONObject().apply {
                put("reportId", "REP_${fileObj.nameWithoutExtension}_${fileObj.lastModified()}")
                put("title", type)
                put("type", type)
                put("checkpoint", type)
                put("officer", officer)
                put("note", note)
                put("coords", coords)
                put("address", cleanAddress)
                put("filePath", filePath)
                put("timestamp", SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()))
            }
            arr.put(obj)
            sp.edit().putString("pending_list", arr.toString()).commit()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun recordVerificationLog(
        context: Context,
        title: String,
        fileName: String,
        status: String,
        coords: String
    ) {
        autoCleanWeeklyCache(context)
        try {
            val sp = context.getSharedPreferences("verification_logs", Context.MODE_PRIVATE)
            val raw = sp.getString("logs_array", "[]") ?: "[]"
            val oldArr = JSONArray(raw)
            val newArr = JSONArray()

            for (i in 0 until oldArr.length()) {
                val item = oldArr.getJSONObject(i)
                if (!item.optString("fileName").equals(fileName, ignoreCase = true)) {
                    newArr.put(item)
                }
            }

            val obj = JSONObject().apply {
                put("title", title)
                put("fileName", fileName)
                put("status", status)
                put("coords", coords)
                put("timestamp", SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()))
            }
            newArr.put(obj)

            while (newArr.length() > 100) {
                newArr.remove(0)
            }
            sp.edit().putString("logs_array", newArr.toString()).commit()
        } catch (_: Exception) {}
    }
}
