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

    // ==========================================
    // 1. PEMBERSIH CACHE OTOMATIS TIAP 7 HARI (BACKGROUND THREAD)
    // ==========================================
    fun autoCleanWeeklyCache(context: Context) {
        try {
            val sp = context.getSharedPreferences("cache_cleaner_pref", Context.MODE_PRIVATE)
            val lastClean = sp.getLong("last_clean_timestamp", 0L)
            val now = System.currentTimeMillis()
            val oneWeekMillis = 7L * 24 * 60 * 60 * 1000 // 7 Hari (1 Minggu)

            // Cek apakah sudah lewat 7 hari sejak pembersihan terakhir
            if (now - lastClean >= oneWeekMillis) {
                // Jalankan di thread latar belakang agar tidak mengganggu performa UI/kamera
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

                                    // Hapus hanya jika BUKAN antrean offline dan sudah lebih dari 7 hari
                                    if (!isPendingOffline && isOlderThanWeek) {
                                        file.delete()
                                    }
                                }
                            }
                        }
                        // Perbarui waktu pembersihan sukses
                        sp.edit().putLong("last_clean_timestamp", now).commit()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    // ==========================================
    // 2. PENYIMPANAN GALERI & DOKUMENTASI
    // ==========================================
    fun saveImageToPublicGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        // Otomatis memicu pembersihan berkala tiap 7 hari
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
                if (imageUri != null) {
                    fos = resolver.openOutputStream(imageUri)
                }
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
        // Otomatis memicu pembersihan berkala tiap 7 hari
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
                if (videoUri != null) {
                    fos = resolver.openOutputStream(videoUri)
                }
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
            val obj = JSONObject().apply {
                put("title", type)
                put("type", type)
                put("checkpoint", type)
                put("officer", officer)
                put("note", note)
                put("coords", coords)
                put("address", address)
                put("filePath", filePath)
                put("timestamp", SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()))
            }
            arr.put(obj)
            sp.edit().putString("pending_list", arr.toString()).commit()
        } catch (_: Exception) {}
    }

    fun recordVerificationLog(
        context: Context,
        title: String,
        fileName: String,
        status: String,
        coords: String
    ) {
        // Otomatis memicu pembersihan berkala tiap 7 hari
        autoCleanWeeklyCache(context)

        try {
            val sp = context.getSharedPreferences("verification_logs", Context.MODE_PRIVATE)
            val raw = sp.getString("logs_array", "[]") ?: "[]"
            val arr = JSONArray(raw)
            val obj = JSONObject().apply {
                put("title", title)
                put("fileName", fileName)
                put("status", status)
                put("coords", coords)
                put("timestamp", SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date()))
            }
            arr.put(obj)
            while (arr.length() > 100) {
                arr.remove(0)
            }
            sp.edit().putString("logs_array", arr.toString()).commit()
        } catch (_: Exception) {}
    }
}
