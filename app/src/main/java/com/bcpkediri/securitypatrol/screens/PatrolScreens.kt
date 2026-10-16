package com.bcpkediri.securitypatrol

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.*
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.*

data class InboundQueueItem(
    val nopol: String,
    val driverName: String,
    val driverPhone: String,
    val division: String,
    val vehicleType: String,
    val expedition: String,
    val sealNo: String,
    val status: String
)

fun formatTitleCase(input: String): String {
    if (input.isBlank()) return ""
    return input.split(" ").joinToString(" ") { word ->
        if (word.isNotEmpty()) {
            word.lowercase(Locale.getDefault()).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        } else ""
    }
}

private fun parseCheckpointNameFromQr(rawQr: String): String {
    val trimmed = rawQr.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
            val obj = JSONObject(trimmed)
            val name = obj.optString("pos")
                .ifBlank { obj.optString("name") }
                .ifBlank { obj.optString("checkpoint") }
                .ifBlank { obj.optString("title") }
                .ifBlank { obj.optString("lokasi") }
            if (name.isNotBlank()) return name.trim()
        } catch (_: Exception) {}
    }
    return trimmed.replace("\n", " ").take(60).trim()
}

private fun fetchSatelliteMinimapTile(coordsText: String): Bitmap? {
    return try {
        val clean = coordsText.replace("°S", "").replace("°E", "").replace("S", "").replace("E", "")
        val parts = clean.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDouble()
        val lon = parts[1].trim().toDouble()
        val zoom = 18
        val n = 2.0.pow(zoom.toDouble())
        val xTile = ((lon + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(lat)
        val yTile = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()

        val tileUrl = "https://mt1.google.com/vt/lyrs=s&x=$xTile&y=$yTile&z=$zoom"
        val conn = (URL(tileUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        if (conn.responseCode == 200) {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } else null
    } catch (_: Exception) {
        null
    }
}

private fun drawTimemarkOverlayOnCanvas(
    canvas: Canvas,
    w: Float,
    h: Float,
    tileBmp: Bitmap?,
    badgeLabel: String,
    officerName: String,
    coordsText: String,
    addressText: String,
    bottomBannerText: String
) {
    val s = (min(w, h) / 1080f).coerceAtLeast(0.45f)

    // 1. Minimap Satelit di Pojok Kiri Atas
    val mapSize = (260f * s).toInt()
    val mapLeft = 36f * s
    val mapTop = 36f * s
    val mapRect = RectF(mapLeft, mapTop, mapLeft + mapSize, mapTop + mapSize)

    val mapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35, 55, 50) }
    canvas.drawRoundRect(mapRect, 24f * s, 24f * s, mapBgPaint)

    if (tileBmp != null) {
        val scaledTile = Bitmap.createScaledBitmap(tileBmp, mapSize, mapSize, true)
        val saveCount = canvas.save()
        val clipPath = Path().apply { addRoundRect(mapRect, 24f * s, 24f * s, Path.Direction.CW) }
        canvas.clipPath(clipPath)
        canvas.drawBitmap(scaledTile, mapLeft, mapTop, null)
        canvas.restoreToCount(saveCount)
    }

    val cx = mapRect.centerX()
    val cy = mapRect.centerY()
    val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(115, 46, 204, 113)
        style = Paint.Style.FILL
    }
    val coneRadius = mapSize * 0.42f
    val coneOval = RectF(cx - coneRadius, cy - coneRadius, cx + coneRadius, cy + coneRadius)
    val startAngle = (LocationUtil.currentAzimuth - 90f - 30f)
    canvas.drawArc(coneOval, startAngle, 60f, true, conePaint)

    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val blueDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(25, 118, 210) }
    canvas.drawCircle(cx, cy, 20f * s, blueDotPaint)
    canvas.drawCircle(cx, cy, 9f * s, whitePaint)

    val mapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * s
    }
    canvas.drawRoundRect(mapRect, 24f * s, 24f * s, mapBorderPaint)

    val googlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 26f * s
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
    }
    canvas.drawText("Google", mapLeft + 16f * s, mapRect.bottom - 16f * s, googlePaint)

    // 2. Pita Putih Bawah
    val bannerHeight = 74f * s
    val bannerTop = h - bannerHeight - (18f * s)
    val bannerRect = RectF(36f * s, bannerTop, w - 36f * s, h - 18f * s)
    canvas.drawRoundRect(bannerRect, 18f * s, 18f * s, whitePaint)

    val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = 30f * s
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    canvas.drawText(bottomBannerText.take(46), 54f * s, bannerRect.centerY() + (10f * s), bannerTextPaint)

    // 3. Kotak Gelap Transparan BORWITA Security Patrol
    val darkBoxHeight = 225f * s
    val darkBoxTop = bannerTop - darkBoxHeight - (10f * s)
    val darkBoxRect = RectF(36f * s, darkBoxTop, w - 36f * s, bannerTop - (6f * s))
    val darkBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(180, 25, 25, 25) }
    canvas.drawRoundRect(darkBoxRect, 22f * s, 22f * s, darkBgPaint)

    val flagLeft = 58f * s
    val flagTop = darkBoxTop + 24f * s
    val flagW = 52f * s
    val flagH = 34f * s
    val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(211, 47, 47) }
    canvas.drawRect(flagLeft, flagTop, flagLeft + flagW, flagTop + flagH / 2f, redPaint)
    canvas.drawRect(flagLeft, flagTop + flagH / 2f, flagLeft + flagW, flagTop + flagH, whitePaint)

    val borwitaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 31f * s
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText("BORWITA", flagLeft + flagW + 18f * s, flagTop + 28f * s, borwitaPaint)

    val badgeLeft = flagLeft + flagW + 190f * s
    val badgeRect = RectF(badgeLeft, flagTop - 2f * s, badgeLeft + 230f * s, flagTop + flagH + 4f * s)
    val greenBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(46, 139, 87) }
    canvas.drawRoundRect(badgeRect, 10f * s, 10f * s, greenBadgePaint)

    val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 22f * s
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    canvas.drawText(badgeLabel.take(16), badgeLeft + 16f * s, flagTop + 25f * s, badgeTextPaint)

    val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(235, 235, 235)
        textSize = 24f * s
    }
    val dayFormat = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale("id", "ID")).format(Date())
    var textY = flagTop + flagH + 40f * s
    canvas.drawText(addressText.take(56), flagLeft, textY, detailPaint)
    textY += 38f * s
    canvas.drawText("Lat/Long: $coordsText  •  Petugas: $officerName", flagLeft, textY, detailPaint)
    textY += 38f * s
    canvas.drawText(dayFormat, flagLeft, textY, detailPaint)
}

fun applyPhotoAndVideoTimemark(
    context: Context,
    imageFile: File,
    badgeLabel: String,
    officerName: String,
    coordsText: String,
    addressText: String,
    bottomBannerText: String
) {
    try {
        val original = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return
        val mutableBmp = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBmp)
        val tileBmp = fetchSatelliteMinimapTile(coordsText)

        drawTimemarkOverlayOnCanvas(
            canvas = canvas,
            w = mutableBmp.width.toFloat(),
            h = mutableBmp.height.toFloat(),
            tileBmp = tileBmp,
            badgeLabel = badgeLabel,
            officerName = officerName,
            coordsText = coordsText,
            addressText = addressText,
            bottomBannerText = bottomBannerText
        )

        FileOutputStream(imageFile).use { out ->
            mutableBmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.flush()
        }
        MediaStorageUtil.saveImageToPublicGallery(context, mutableBmp, imageFile.name)
    } catch (_: Exception) {}
}

private fun applyTimemarkToVideoFile(
    videoFile: File,
    badgeLabel: String,
    officerName: String,
    coordsText: String,
    addressText: String,
    bottomBannerText: String
) {
    val tempOutFile = File(videoFile.parentFile, "wm_${videoFile.name}")
    try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(videoFile.absolutePath)
        val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
        val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        retriever.release()

        val isPortrait = rotation == 90 || rotation == 270 || rawH >= rawW
        val outW = if (isPortrait) 720 else 1280
        val outH = if (isPortrait) 1280 else 720

        val tileBmp = fetchSatelliteMinimapTile(coordsText)
        val overlayBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val overlayCanvas = Canvas(overlayBmp)
        overlayCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawTimemarkOverlayOnCanvas(
            canvas = overlayCanvas,
            w = outW.toFloat(),
            h = outH.toFloat(),
            tileBmp = tileBmp,
            badgeLabel = badgeLabel,
            officerName = officerName,
            coordsText = coordsText,
            addressText = addressText,
            bottomBannerText = bottomBannerText
        )

        val extractor = MediaExtractor()
        extractor.setDataSource(videoFile.absolutePath)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var inputVideoFormat: MediaFormat? = null
        var inputAudioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i
                inputVideoFormat = fmt
            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                audioTrackIndex = i
                inputAudioFormat = fmt
            }
        }

        if (videoTrackIndex == -1 || inputVideoFormat == null) return

        val outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE,
