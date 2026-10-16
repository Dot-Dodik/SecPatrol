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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
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

/**
 * Menggambar overlay Timemark BORWITA + Minimap Satelit ke atas Canvas (dipakai untuk Foto maupun Video)
 */
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

/**
 * Menempelkan Timemark BORWITA + Minimap Satelit langsung ke dalam file Video (.mp4)
 * menggunakan Hardware MediaCodec + OpenGL ES 2.0 agar video H.264 bisa langsung di-play di Google Drive
 */
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

        // Siapkan Bitmap Overlay Transparan berisikan Minimap & Kotak Timemark BORWITA
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

        // Setup EGL14 + OpenGL ES 2.0
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        val eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderInputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // Shaders untuk Video Frame (OES) dan Overlay Bitmap (2D)
        val vertexShaderCode = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """.trimIndent()

        val fragmentShaderOes = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """.trimIndent()

        val fragmentShader2D = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vec2(vTextureCoord.x, 1.0 - vTextureCoord.y));
            }
        """.trimIndent()

        fun compileShader(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            return sh
        }

        fun buildProgram(vs: String, fs: String): Int {
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, compileShader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(prog, compileShader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(prog)
            return prog
        }

        val oesProgram = buildProgram(vertexShaderCode, fragmentShaderOes)
        val overlayProgram = buildProgram(vertexShaderCode, fragmentShader2D)

        val quadCoords = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
             1f, -1f, 0f, 1f, 0f,
            -1f,  1f, 0f, 0f, 1f,
             1f,  1f, 0f, 1f, 1f
        )
        val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quadCoords)
                position(0)
            }

        val texIds = IntArray(2)
        GLES20.glGenTextures(2, texIds, 0)
        val oesTexId = texIds[0]
        val overlayTexId = texIds[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBmp, 0)
        overlayBmp.recycle()

        val frameSyncLock = Object()
        var frameAvailable = false
        val surfaceTexture = SurfaceTexture(oesTexId)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(frameSyncLock) {
                frameAvailable = true
                frameSyncLock.notifyAll()
            }
        }
        val decoderOutputSurface = Surface(surfaceTexture)

        val inMime = inputVideoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
        val decoder = MediaCodec.createDecoderByType(inMime)
        decoder.configure(inputVideoFormat, decoderOutputSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(tempOutFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        extractor.selectTrack(videoTrackIndex)
        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        val stMatrix = FloatArray(16)
        val identityMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }

        fun drawQuad(prog: Int, texTarget: Int, texId: Int, matrix: FloatArray) {
            GLES20.glUseProgram(prog)
            vertexBuffer.position(0)
            val aPos = GLES20.glGetAttribLocation(prog, "aPosition")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer)

            vertexBuffer.position(3)
            val aTex = GLES20.glGetAttribLocation(prog, "aTextureCoord")
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer)

            val uSTM = GLES20.glGetUniformLocation(prog, "uSTMatrix")
            GLES20.glUniformMatrix4fv(uSTM, 1, false, matrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(texTarget, texId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        while (!encoderDone) {
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            if (!decoderDone) {
                val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                if (outIdx >= 0) {
                    val doRender = decInfo.size > 0
                    decoder.releaseOutputBuffer(outIdx, doRender)
                    if (doRender) {
                        synchronized(frameSyncLock) {
                            while (!frameAvailable) {
                                frameSyncLock.wait(500)
                                if (!frameAvailable) break
                            }
                            frameAvailable = false
                        }
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(stMatrix)

                        GLES20.glViewport(0, 0, outW, outH)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        GLES20.glDisable(GLES20.GL_BLEND)
                        drawQuad(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId, stMatrix)

                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                        drawQuad(overlayProgram, GLES20.GL_TEXTURE_2D, overlayTexId, identityMatrix)

                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, decInfo.presentationTimeUs * 1000)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    }
                    if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream()
                        decoderDone = true
                    }
                }
            }

            while (true) {
                val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 10_000)
                if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                    if (inputAudioFormat != null) {
                        muxerAudioTrack = muxer.addTrack(inputAudioFormat)
                    }
                    muxer.start()
                    muxerStarted = true
                } else if (encOutIdx >= 0) {
                    val encodedData = encoder.getOutputBuffer(encOutIdx)!!
                    if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encInfo.size = 0
                    }
                    if (encInfo.size > 0 && muxerStarted) {
                        encodedData.position(encInfo.offset)
                        encodedData.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(muxerVideoTrack, encodedData, encInfo)
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                    if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                        break
                    }
                }
            }
        }

        // Salin Track Audio Asli agar suara rekaman tetap ada
        if (audioTrackIndex != -1 && muxerAudioTrack != -1 && muxerStarted) {
            extractor.unselectTrack(videoTrackIndex)
            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val audioBuf = ByteBuffer.allocateDirect(256 * 1024)
            val audioInfo = MediaCodec.BufferInfo()
            while (true) {
                val sz = extractor.readSampleData(audioBuf, 0)
                if (sz < 0) break
                audioInfo.offset = 0
                audioInfo.size = sz
                audioInfo.presentationTimeUs = extractor.sampleTime
                audioInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                extractor.advance()
            }
        }

        try { decoder.stop(); decoder.release() } catch (_: Exception) {}
        try { encoder.stop(); encoder.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer.stop(); muxer.release() } catch (_: Exception) {}
        try { extractor.release() } catch (_: Exception) {}
        try { decoderOutputSurface.release(); surfaceTexture.release() } catch (_: Exception) {}
        try {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        } catch (_: Exception) {}

        if (tempOutFile.exists() && tempOutFile.length() > 1024L) {
            tempOutFile.copyTo(videoFile, overwrite = true)
            tempOutFile.delete()
        }
    } catch (_: Exception) {
        if (tempOutFile.exists()) tempOutFile.delete()
    }
}

@Composable
private fun GpsDisabledAlertDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("⚠️ GPS / Lokasi Dimatikan!", fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C)) },
            text = {
                Text("Layanan Lokasi (GPS) sedang mati. Harap aktifkan GPS agar titik koordinat satelit dan Minimap pada foto/video terekam dengan benar.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)
                ) { Text("Aktifkan GPS Sekarang") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Tutup") }
            }
        )
    }
}

@Composable
private fun LocationCoordinateBox(
    addressText: String,
    gpsStatusText: String,
    onRefreshLocation: () -> Unit
) {
    val isGpsOff = gpsStatusText.contains("Dimatikan", ignoreCase = true) ||
                   gpsStatusText.contains("Error", ignoreCase = true)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LOKASI & KOORDINAT:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isGpsOff) Color(0xFFFFEBEE) else Color(0xFFEDE9E0),
            border = if (isGpsOff) BorderStroke(1.dp, Color(0xFFD32F2F)) else null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(addressText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
                    Text(
                        text = gpsStatusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isGpsOff) Color(0xFFD32F2F) else ColorCyprus
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = if (isGpsOff) Color(0xFFD32F2F) else ColorCyprus,
                    modifier = Modifier.size(44.dp).clickable { onRefreshLocation() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("🎯", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun QrCheckpointScannerBox(
    onQrDetected: (String) -> Unit,
    onCloseScanner: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var hasScanned by remember { mutableStateOf(false) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "posLaser")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "posLaserLine"
    )

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        modifier = Modifier.fillMaxWidth().height(320.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val scanner = BarcodeScanning.getClient()
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !hasScanned) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        for (barcode in barcodes) {
                                            val rawVal = barcode.rawValue
                                            if (!rawVal.isNullOrBlank() && !hasScanned) {
                                                hasScanned = true
                                                onQrDetected(rawVal.trim())
                                                break
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraRef = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
            )

            // Kotak Batasan + Sudut Hijau + Animasi Laser Hijau
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = size.minDimension * 0.62f
                val left = (size.width - boxSize) / 2f
                val top = (size.height - boxSize) / 2f
                val right = left + boxSize
                val bottom = top + boxSize
                val cornerLen = 34f
                val strokeW = 7f
                val greenLaser = Color(0xFF00E676)

                drawLine(greenLaser, Offset(left, top), Offset(left + cornerLen, top), strokeW)
                drawLine(greenLaser, Offset(left, top), Offset(left, top + cornerLen), strokeW)
                drawLine(greenLaser, Offset(right, top), Offset(right - cornerLen, top), strokeW)
                drawLine(greenLaser, Offset(right, top), Offset(right, top + cornerLen), strokeW)
                drawLine(greenLaser, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW)
                drawLine(greenLaser, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW)
                drawLine(greenLaser, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW)
                drawLine(greenLaser, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW)

                val laserY = top + (boxSize * laserProgress)
                drawLine(
                    color = greenLaser,
                    start = Offset(left + 10f, laserY),
                    end = Offset(right - 10f, laserY),
                    strokeWidth = 5f
                )
            }

            Surface(
                color = Color(0xAA000000),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            ) {
                Text(
                    "Posisikan QR Titik Pos di dalam kotak",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // Tombol Senter (Flashlight)
            Button(
                onClick = {
                    val nextTorch = !isTorchOn
                    isTorchOn = nextTorch
                    cameraRef?.cameraControl?.enableTorch(nextTorch)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTorchOn) Color(0xFFFFC107) else Color(0xAA000000),
                    contentColor = if (isTorchOn) Color.Black else Color.White
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Text(if (isTorchOn) "🔦 Senter ON" else "🔦 Senter", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            TextButton(
                onClick = onCloseScanner,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
            ) {
                Text("Tutup Pemindai QR", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CheckpointScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas") ?: "Petugas" }
    val isDevMode = remember {
        (spUser.getString("officer_id", "") ?: "").equals("ADMIN-DEV", true) ||
        spUser.getBoolean("is_dev_mode", false)
    }

    // Disederhanakan menjadi Pos 1 s/d Pos 6
    val checkpointOptions = listOf("Pos 1", "Pos 2", "Pos 3", "Pos 4", "Pos 5", "Pos 6")
    var selectedCheckpoint by remember { mutableStateOf(checkpointOptions[0]) }
    var scannedQrCode by remember { mutableStateOf("") }
    var isScanningQr by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }

    var addressText by remember { mutableStateOf(LocationUtil.getFallbackPatrolArea(context)) }
    var gpsStatusText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var showGpsAlert by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }

    val colorTealScan = Color(0xFF00695C)

    fun refreshGps() {
        if (!LocationUtil.isGpsEnabled(context)) {
            gpsStatusText = "Layanan Lokasi (GPS) Dimatikan"
            showGpsAlert = true
            return
        }
        addressText = LocationUtil.getFallbackPatrolArea(context)
        LocationUtil.fetchPureGpsLocation(context) { statusText, coords, loc ->
            gpsStatusText = statusText
            coordsRaw = coords
            if (statusText.contains("Dimatikan", true)) showGpsAlert = true
            if (loc != null) {
                scope.launch {
                    addressText = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshGps() }

    GpsDisabledAlertDialog(
        showDialog = showGpsAlert,
        onDismiss = { showGpsAlert = false },
        onOpenSettings = { LocationUtil.openLocationSettings(context) }
    )

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingCaptureFile
        if (taken && file != null && file.exists()) {
            isSubmitting = true
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                val qrInfo = if (scannedQrCode.isNotBlank()) "[QR: $scannedQrCode] " else ""
                val finalNote = "$qrInfo${noteInput.ifBlank { "Aman / Terkendali" }}"
                withContext(Dispatchers.IO) {
                    applyPhotoAndVideoTimemark(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "$selectedCheckpoint • ${noteInput.ifBlank { "Aman / Terkendali" }}"
                    )
                }
                val res = Uploader.sendCheckpoint(context, selectedCheckpoint, officerName, finalNote, finalAddress, coordsRaw, file)
                isSubmitting = false
                if (res.first) {
                    MediaStorageUtil.recordVerificationLog(context, selectedCheckpoint, file.name, "TERUPLOAD KE CLOUD", coordsRaw)
                    Toast.makeText(context, "Laporan $selectedCheckpoint terkirim ke Google Sheets!", Toast.LENGTH_SHORT).show()
                } else {
                    MediaStorageUtil.saveToOfflineQueue(context, selectedCheckpoint, officerName, finalNote, coordsRaw, file.absolutePath, finalAddress)
                    MediaStorageUtil.recordVerificationLog(context, selectedCheckpoint, file.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                    Toast.makeText(context, "Disimpan di Antrean Offline.", Toast.LENGTH_LONG).show()
                }
                onSuccess()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Titik Pos Patroli", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. PILIH ATAU SCAN QR TITIK POS PATROLI:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)

                if (isScanningQr) {
                    QrCheckpointScannerBox(
                        onQrDetected = { qrValue ->
                            isScanningQr = false
                            scannedQrCode = qrValue
                            val matchedPos = checkpointOptions.firstOrNull { pos ->
                                qrValue.contains(pos, ignoreCase = true) ||
                                qrValue.contains(pos.replace(" ", ""), ignoreCase = true)
                            }
                            if (matchedPos != null) selectedCheckpoint = matchedPos
                            Toast.makeText(context, "QR Titik Pos Terverifikasi: $qrValue", Toast.LENGTH_SHORT).show()
                        },
                        onCloseScanner = { isScanningQr = false }
                    )
                } else {
                    Button(
                        onClick = { isScanningQr = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (scannedQrCode.isNotBlank()) Color(0xFF2E7D32) else colorTealScan
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text(
                            if (scannedQrCode.isNotBlank()) "✅ QR Terverifikasi ($scannedQrCode) - Scan Ulang"
                            else "📷 Scan Barcode / QR Titik Pos (Pos 1 - Pos 6)"
                        )
                    }
                }

                checkpointOptions.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedCheckpoint = option
                            if (isDevMode && scannedQrCode.isBlank()) {
                                scannedQrCode = "DEV-${option.uppercase().replace(" ", "")}"
                            }
                        }
                    ) {
                        RadioButton(
                            selected = selectedCheckpoint == option,
                            onClick = {
                                selectedCheckpoint = option
                                if (isDevMode && scannedQrCode.isBlank()) {
                                    scannedQrCode = "DEV-${option.uppercase().replace(" ", "")}"
                                }
                            }
                        )
                        Text(option, fontSize = 13.sp, color = ColorTextDark)
                    }
                }

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Catatan Kondisi Pos") },
                    placeholder = { Text("Contoh: Gembok terkunci rapat, lampu menyala") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                LocationCoordinateBox(addressText, gpsStatusText) { refreshGps() }
            }
        }

        val canTakePhoto = scannedQrCode.isNotBlank() || isDevMode
        if (!canTakePhoto) {
            Text(
                "⚠️ Silakan Scan Barcode / QR Titik Pos terlebih dahulu untuk membuka kamera.",
                fontSize = 12.sp,
                color = Color(0xFFD97706),
                fontWeight = FontWeight.Medium
            )
        }

        Button(
            onClick = {
                if (!LocationUtil.isGpsEnabled(context)) {
                    showGpsAlert = true
                    return@Button
                }
                val dir = File(context.cacheDir, "checkpoints").apply { mkdirs() }
                val file = File(dir, "${selectedCheckpoint.replace(" ", "_")}_${System.currentTimeMillis()}.jpg")
                pendingCaptureFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                cameraLauncher.launch(uri)
            },
            enabled = !isSubmitting && canTakePhoto,
            colors = ButtonDefaults.buttonColors(containerColor = colorTealScan),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (isSubmitting) "Memproses & Mengunggah..." else "📷 Ambil Foto Bukti Patroli")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(24.dp)) {
            Text("Kembali")
        }
    }
}

@Composable
fun FacilityReportScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas") ?: "Petugas" }

    val facilityList = listOf(
        "Area Parkir / Sekeliling",
        "Gembok / Segel Pintu Gudang (Tutup)",
        "Buka Gudang / Pelepasan Segel Pintu",
        "Ruang Finance",
        "Gudang Utama",
        "Area Loading Dock"
    )
    var selectedFacility by remember { mutableStateOf(facilityList[0]) }
    var noteInput by remember { mutableStateOf("") }
    var addressText by remember { mutableStateOf(LocationUtil.getFallbackPatrolArea(context)) }
    var gpsStatusText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var showGpsAlert by remember { mutableStateOf(false) }

    val photoFiles = remember { mutableStateListOf<File>() }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    fun refreshGps() {
        if (!LocationUtil.isGpsEnabled(context)) {
            gpsStatusText = "Layanan Lokasi (GPS) Dimatikan"
            showGpsAlert = true
            return
        }
        addressText = LocationUtil.getFallbackPatrolArea(context)
        LocationUtil.fetchPureGpsLocation(context) { statusText, coords, loc ->
            gpsStatusText = statusText
            coordsRaw = coords
            if (statusText.contains("Dimatikan", true)) showGpsAlert = true
            if (loc != null) {
                scope.launch {
                    addressText = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshGps() }

    GpsDisabledAlertDialog(
        showDialog = showGpsAlert,
        onDismiss = { showGpsAlert = false },
        onOpenSettings = { LocationUtil.openLocationSettings(context) }
    )

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingCaptureFile
        if (taken && file != null && file.exists()) {
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                withContext(Dispatchers.IO) {
                    applyPhotoAndVideoTimemark(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "$selectedFacility • ${noteInput.ifBlank { "Kondisi Aman" }}"
                    )
                }
                photoFiles.add(file)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Dokumentasi Gudang & Fasilitas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PILIH AREA FASILITAS / GUDANG:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                facilityList.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedFacility = item }
                    ) {
                        RadioButton(selected = selectedFacility == item, onClick = { selectedFacility = item })
                        Text(item, fontSize = 13.sp, color = ColorTextDark)
                    }
                }

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Catatan Pemeriksaan") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                LocationCoordinateBox(addressText, gpsStatusText) { refreshGps() }
            }
        }

        Button(
            onClick = {
                if (!LocationUtil.isGpsEnabled(context)) {
                    showGpsAlert = true
                    return@Button
                }
                if (photoFiles.size >= 3) {
                    Toast.makeText(context, "Maksimal 3 foto per laporan.", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val dir = File(context.cacheDir, "checkpoints").apply { mkdirs() }
                val file = File(dir, "Fasilitas_${System.currentTimeMillis()}.jpg")
                pendingCaptureFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                cameraLauncher.launch(uri)
            },
            enabled = !isSubmitting && photoFiles.size < 3,
            colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("📷 Kamera (${photoFiles.size}/3)")
        }

        if (photoFiles.isNotEmpty()) {
            Button(
                onClick = {
                    isSubmitting = true
                    scope.launch {
                        val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                        val finalNote = noteInput.ifBlank { "Kondisi aman & rapi" }
                        val res = Uploader.sendFacilityMulti(context, selectedFacility, officerName, finalNote, finalAddress, coordsRaw, photoFiles.toList())
                        isSubmitting = false
                        val firstFile = photoFiles.first()
                        if (res.first) {
                            MediaStorageUtil.recordVerificationLog(context, selectedFacility, firstFile.name, "TERUPLOAD KE CLOUD", coordsRaw)
                            Toast.makeText(context, "Dokumentasi fasilitas terkirim ke Google Sheets!", Toast.LENGTH_SHORT).show()
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, selectedFacility, officerName, finalNote, coordsRaw, firstFile.absolutePath, finalAddress)
                            MediaStorageUtil.recordVerificationLog(context, selectedFacility, firstFile.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Disimpan di Antrean Offline.", Toast.LENGTH_LONG).show()
                        }
                        onSuccess()
                    }
                },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (isSubmitting) "Mengunggah..." else "✅ Kirim Dokumentasi (${photoFiles.size} Foto)")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(24.dp)) {
            Text("Kembali")
        }
    }
}

@Composable
fun InboundTruckReportScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val spInbound = remember { context.getSharedPreferences("inbound_outstanding_queue", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas") ?: "Petugas" }

    val statusOptions = listOf(
        "Truck Datang - Antri Bongkar",
        "Truck Sedang Bongkar Muat",
        "Truck Selesai Muat (Kosong & Bersih)"
    )

    var nopolInput by remember { mutableStateOf("") }
    var driverNameInput by remember { mutableStateOf("") }
    var driverPhoneInput by remember { mutableStateOf("") }
    var divisionInput by remember { mutableStateOf("") }
    var vehicleTypeInput by remember { mutableStateOf("") }
    var expeditionInput by remember { mutableStateOf("") }
    var sealNoInput by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf(statusOptions[0]) }
    var noteInput by remember { mutableStateOf("") }

    var addressText by remember { mutableStateOf(LocationUtil.getFallbackPatrolArea(context)) }
    var gpsStatusText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var showGpsAlert by remember { mutableStateOf(false) }

    var queueList by remember { mutableStateOf<List<InboundQueueItem>>(emptyList()) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var selectedQueueLabel by remember { mutableStateOf("Belum ada antrean (Ketik Truk Baru)") }
    var isSyncingQueue by remember { mutableStateOf(false) }

    val photoFiles = remember { mutableStateListOf<File>() }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    fun loadLocalQueue() {
        val raw = spInbound.getString("queue_json", "[]") ?: "[]"
        val list = mutableListOf<InboundQueueItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    InboundQueueItem(
                        nopol = o.optString("nopol"),
                        driverName = o.optString("driverName"),
                        driverPhone = o.optString("driverPhone"),
                        division = o.optString("division"),
                        vehicleType = o.optString("vehicleType"),
                        expedition = o.optString("expedition"),
                        sealNo = o.optString("sealNo"),
                        status = o.optString("status")
                    )
                )
            }
        } catch (_: Exception) {}
        queueList = list
    }

    fun saveLocalQueue(items: List<InboundQueueItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("nopol", item.nopol)
                put("driverName", item.driverName)
                put("driverPhone", item.driverPhone)
                put("division", item.division)
                put("vehicleType", item.vehicleType)
                put("expedition", item.expedition)
                put("sealNo", item.sealNo)
                put("status", item.status)
            })
        }
        spInbound.edit().putString("queue_json", arr.toString()).commit()
        queueList = items
    }

    fun refreshGps() {
        if (!LocationUtil.isGpsEnabled(context)) {
            gpsStatusText = "Layanan Lokasi (GPS) Dimatikan"
            showGpsAlert = true
            return
        }
        addressText = LocationUtil.getFallbackPatrolArea(context)
        LocationUtil.fetchPureGpsLocation(context) { statusText, coords, loc ->
            gpsStatusText = statusText
            coordsRaw = coords
            if (statusText.contains("Dimatikan", true)) showGpsAlert = true
            if (loc != null) {
                scope.launch {
                    addressText = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                }
            }
        }
    }

    fun syncCloudQueue() {
        if (isSyncingQueue) return
        isSyncingQueue = true
        scope.launch {
            val remote = Uploader.fetchActiveInboundQueue(context)
            if (remote.isNotEmpty()) saveLocalQueue(remote) else loadLocalQueue()
            isSyncingQueue = false
            Toast.makeText(context, "Antrean Truk disinkronkan (${queueList.size} aktif)", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        loadLocalQueue()
        refreshGps()
        if (LocationUtil.checkNetworkAvailable(context)) {
            val remote = Uploader.fetchActiveInboundQueue(context)
            if (remote.isNotEmpty()) saveLocalQueue(remote)
        }
    }

    GpsDisabledAlertDialog(
        showDialog = showGpsAlert,
        onDismiss = { showGpsAlert = false },
        onOpenSettings = { LocationUtil.openLocationSettings(context) }
    )

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingCaptureFile
        if (taken && file != null && file.exists()) {
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                val cleanNopol = nopolInput.ifBlank { "INBOUND" }.uppercase()
                withContext(Dispatchers.IO) {
                    applyPhotoAndVideoTimemark(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "$cleanNopol • $selectedStatus"
                    )
                }
                photoFiles.add(file)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && photoFiles.size < 3) {
            try {
                val dir = File(context.cacheDir, "checkpoints").apply { mkdirs() }
                val destFile = File(dir, "Inbound_Gallery_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                if (destFile.exists()) {
                    scope.launch {
                        val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                        val cleanNopol = nopolInput.ifBlank { "INBOUND" }.uppercase()
                        withContext(Dispatchers.IO) {
                            applyPhotoAndVideoTimemark(
                                context = context,
                                imageFile = destFile,
                                badgeLabel = "Security Patrol",
                                officerName = officerName,
                                coordsText = coordsRaw,
                                addressText = finalAddress,
                                bottomBannerText = "$cleanNopol • $selectedStatus"
                            )
                        }
                        photoFiles.add(destFile)
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(context, "Gagal memuat foto dari galeri", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Dokumentasi Truck Inbound", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
            Text(
                text = if (isSyncingQueue) "⏳..." else "🔄 Sinkron",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ColorCyprus,
                modifier = Modifier.clickable { syncCloudQueue() }.padding(4.dp)
            )
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedCard(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF81C784)),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color(0xFFF1F8E9)),
                        modifier = Modifier.fillMaxWidth().clickable { dropdownExpanded = true }
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text("PILIH ANTREAN OUTSTANDING:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedQueueLabel, fontSize = 13.sp, color = ColorTextDark)
                                Text("▼", fontSize = 11.sp, color = ColorTextDark)
                            }
                        }
                    }

                    DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Ketik Truk Baru (Reset Form)") },
                            onClick = {
                                dropdownExpanded = false
                                selectedQueueLabel = "Belum ada antrean (Ketik Truk Baru)"
                                nopolInput = ""
                                driverNameInput = ""
                                driverPhoneInput = ""
                                divisionInput = ""
                                vehicleTypeInput = ""
                                expeditionInput = ""
                                sealNoInput = ""
                                selectedStatus = statusOptions[0]
                            }
                        )
                        queueList.forEach { item ->
                            DropdownMenuItem(
                                text = { Text("${item.nopol} - ${item.driverName} (${item.status})") },
                                onClick = {
                                    dropdownExpanded = false
                                    selectedQueueLabel = "${item.nopol} (${item.driverName})"
                                    nopolInput = item.nopol
                                    driverNameInput = item.driverName
                                    driverPhoneInput = item.driverPhone
                                    divisionInput = item.division
                                    vehicleTypeInput = item.vehicleType
                                    expeditionInput = item.expedition
                                    sealNoInput = item.sealNo
                                    selectedStatus = if (item.status.contains("Antri", true)) statusOptions[1] else statusOptions[2]
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = nopolInput,
                    onValueChange = { nopolInput = it.uppercase() },
                    label = { Text("Nomor Polisi Truck *") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = driverNameInput,
                        onValueChange = { driverNameInput = formatTitleCase(it) },
                        label = { Text("Nama Sopir / Driver *") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = driverPhoneInput,
                        onValueChange = { driverPhoneInput = it },
                        label = { Text("No HP / WA Driver *") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = divisionInput,
                        onValueChange = { divisionInput = it.uppercase() },
                        label = { Text("Divisi") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = vehicleTypeInput,
                        onValueChange = { vehicleTypeInput = formatTitleCase(it) },
                        label = { Text("Jenis Kendaraan") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = expeditionInput,
                    onValueChange = { expeditionInput = formatTitleCase(it) },
                    label = { Text("Ekspedisi / Asal Barang") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = sealNoInput,
                    onValueChange = { sealNoInput = it.uppercase() },
                    label = { Text("Nomor Segel Plastik (Jika Ada)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("STATUS TAHAPAN KONDISI TRUCK:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                statusOptions.forEach { st ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selectedStatus = st }
                    ) {
                        RadioButton(selected = selectedStatus == st, onClick = { selectedStatus = st })
                        Text(st, fontSize = 13.sp, color = ColorTextDark, fontWeight = FontWeight.Medium)
                    }
                }

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Catatan / Keterangan Muatan") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                LocationCoordinateBox(addressText, gpsStatusText) { refreshGps() }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (!LocationUtil.isGpsEnabled(context)) {
                        showGpsAlert = true
                        return@Button
                    }
                    if (photoFiles.size >= 3) {
                        Toast.makeText(context, "Maksimal 3 foto.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val dir = File(context.cacheDir, "checkpoints").apply { mkdirs() }
                    val file = File(dir, "Inbound_${nopolInput.replace(" ", "")}_${System.currentTimeMillis()}.jpg")
                    pendingCaptureFile = file
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    cameraLauncher.launch(uri)
                },
                enabled = !isSubmitting && photoFiles.size < 3,
                colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text("📷 Kamera (${photoFiles.size}/3)", fontSize = 12.sp)
            }

            Button(
                onClick = { galleryLauncher.launch("image/*") },
                enabled = !isSubmitting && photoFiles.size < 3,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0277BD)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text("🖼️ Galeri (${photoFiles.size}/3)", fontSize = 12.sp)
            }
        }

        if (photoFiles.isNotEmpty()) {
            Button(
                onClick = {
                    val cleanNopol = nopolInput.trim().uppercase()
                    if (cleanNopol.isBlank() || driverNameInput.isBlank()) {
                        Toast.makeText(context, "Nomor Polisi dan Nama Sopir wajib diisi!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isSubmitting = true
                    scope.launch {
                        val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                        val title = "Truck $cleanNopol [$selectedStatus]"
                        val combinedNote = "${driverNameInput.trim()} (${driverPhoneInput.ifBlank { "-" }}) - ${noteInput.ifBlank { selectedStatus }}"

                        val updatedQueue = mutableListOf<InboundQueueItem>()
                        queueList.forEach { item ->
                            if (!item.nopol.equals(cleanNopol, ignoreCase = true)) {
                                updatedQueue.add(item)
                            }
                        }
                        if (!selectedStatus.contains("Selesai", ignoreCase = true)) {
                            updatedQueue.add(
                                InboundQueueItem(
                                    nopol = cleanNopol,
                                    driverName = driverNameInput.trim(),
                                    driverPhone = driverPhoneInput.trim(),
                                    division = divisionInput.trim(),
                                    vehicleType = vehicleTypeInput.trim(),
                                    expedition = expeditionInput.trim(),
                                    sealNo = sealNoInput.trim(),
                                    status = selectedStatus
                                )
                            )
                        }
                        saveLocalQueue(updatedQueue)

                        val res = Uploader.sendInbound(
                            context = context,
                            nopol = cleanNopol,
                            driverName = driverNameInput.trim(),
                            driverPhone = driverPhoneInput.ifBlank { "-" },
                            division = divisionInput.ifBlank { "-" },
                            vehicleType = vehicleTypeInput.ifBlank { "-" },
                            expedition = expeditionInput.ifBlank { "-" },
                            sealNo = sealNoInput.ifBlank { "-" },
                            status = selectedStatus,
                            officer = officerName,
                            note = combinedNote,
                            coords = coordsRaw,
                            files = photoFiles.toList()
                        )
                        isSubmitting = false
                        val firstFile = photoFiles.first()
                        if (res.first) {
                            MediaStorageUtil.recordVerificationLog(context, title, firstFile.name, "TERUPLOAD KE CLOUD", coordsRaw)
                            Toast.makeText(context, "Data Inbound $cleanNopol terkirim ke Google Sheets!", Toast.LENGTH_SHORT).show()
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, title, officerName, combinedNote, coordsRaw, firstFile.absolutePath, finalAddress)
                            MediaStorageUtil.recordVerificationLog(context, title, firstFile.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Disimpan ke Antrean Offline.", Toast.LENGTH_LONG).show()
                        }
                        onSuccess()
                    }
                },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (isSubmitting) "Mengunggah..." else "✅ Kirim Laporan Truck (${photoFiles.size} Foto)")
            }
        }

        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text("Kembali")
        }
    }
}

@Composable
fun GensetCheckScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas") ?: "Petugas" }

    // Ditambahkan pilihan Pemanasan / Warming Up Rutin
    val statusList = listOf(
        "Uji Pemanasan (Warming Up Rutin)",
        "Standby / Mati Normal",
        "Menyala (Running)",
        "Perlu Perbaikan"
    )
    val oilList = listOf("Level Normal & Bening/Kuning", "Kurang / Perlu Tambah")
    val fuelList = listOf("Standby Normal (75-100%)", "Sedang (40-75%)", "Menipis (<40%)")

    var statusGenset by remember { mutableStateOf(statusList[0]) }
    var oilCheck by remember { mutableStateOf(oilList[0]) }
    var fuelInfo by remember { mutableStateOf(fuelList[0]) }
    var noteInput by remember { mutableStateOf("") }

    var addressText by remember { mutableStateOf(LocationUtil.getFallbackPatrolArea(context)) }
    var gpsStatusText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var showGpsAlert by remember { mutableStateOf(false) }

    // 3 Media Genset: Foto Genset, Video Genset, Foto Dokumen HSE
    var photoGensetFile by remember { mutableStateOf<File?>(null) }
    var videoGensetFile by remember { mutableStateOf<File?>(null) }
    var photoHseFile by remember { mutableStateOf<File?>(null) }

    var tempCapturePhotoGenset by remember { mutableStateOf<File?>(null) }
    var tempCaptureVideoGenset by remember { mutableStateOf<File?>(null) }
    var tempCapturePhotoHse by remember { mutableStateOf<File?>(null) }

    var isProcessingMedia by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    fun refreshGps() {
        if (!LocationUtil.isGpsEnabled(context)) {
            gpsStatusText = "Layanan Lokasi (GPS) Dimatikan"
            showGpsAlert = true
            return
        }
        addressText = LocationUtil.getFallbackPatrolArea(context)
        LocationUtil.fetchPureGpsLocation(context) { statusText, coords, loc ->
            gpsStatusText = statusText
            coordsRaw = coords
            if (statusText.contains("Dimatikan", true)) showGpsAlert = true
            if (loc != null) {
                scope.launch {
                    addressText = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshGps() }

    GpsDisabledAlertDialog(
        showDialog = showGpsAlert,
        onDismiss = { showGpsAlert = false },
        onOpenSettings = { LocationUtil.openLocationSettings(context) }
    )

    // 1. Launcher Foto Fisik Genset
    val photoGensetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val f = tempCapturePhotoGenset
        if (taken && f != null && f.exists()) {
            isProcessingMedia = true
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                withContext(Dispatchers.IO) {
                    applyPhotoAndVideoTimemark(
                        context = context,
                        imageFile = f,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "Foto Genset • $statusGenset"
                    )
                }
                photoGensetFile = f
                isProcessingMedia = false
                Toast.makeText(context, "Foto Genset siap!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 2. Launcher Video Genset (Otomatis Diberi Timemark + Minimap)
    val videoGensetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { recorded ->
        val vf = tempCaptureVideoGenset
        if (recorded && vf != null && vf.exists() && vf.length() > 0L) {
            isProcessingMedia = true
            Toast.makeText(context, "Memasang Timemark pada Video Genset...", Toast.LENGTH_SHORT).show()
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                withContext(Dispatchers.Default) {
                    applyTimemarkToVideoFile(
                        videoFile = vf,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "Video Genset • $statusGenset"
                    )
                    MediaStorageUtil.saveVideoToPublicGallery(context, vf, vf.name)
                }
                videoGensetFile = vf
                isProcessingMedia = false
                Toast.makeText(context, "Video Genset ber-Timemark siap!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 3. Launcher Foto Dokumen HSE
    val photoHseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val hf = tempCapturePhotoHse
        if (taken && hf != null && hf.exists()) {
            isProcessingMedia = true
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                withContext(Dispatchers.IO) {
                    applyPhotoAndVideoTimemark(
                        context = context,
                        imageFile = hf,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "Dokumen HSE Genset • $oilCheck"
                    )
                }
                photoHseFile = hf
                isProcessingMedia = false
                Toast.makeText(context, "Foto Dokumen HSE siap!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pengecekan Genset, Oli & Solar", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("STATUS MESIN GENSET:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                statusList.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { statusGenset = item }) {
                        RadioButton(selected = statusGenset == item, onClick = { statusGenset = item })
                        Text(item, fontSize = 13.sp, color = ColorTextDark)
                    }
                }

                Text("KONDISI OLI MESIN:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                oilList.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { oilCheck = item }) {
                        RadioButton(selected = oilCheck == item, onClick = { oilCheck = item })
                        Text(item, fontSize = 13.sp, color = ColorTextDark)
                    }
                }

                Text("SOLAR & VOLTASE:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                fuelList.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { fuelInfo = item }) {
                        RadioButton(selected = fuelInfo == item, onClick = { fuelInfo = item })
                        Text(item, fontSize = 13.sp, color = ColorTextDark)
                    }
                }

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Catatan Temuan Genset") },
                    placeholder = { Text("Contoh: Kondisi genset normal") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                LocationCoordinateBox(addressText, gpsStatusText) { refreshGps() }
            }
        }

        // 3 Tombol Dokumentasi Genset: Photo Genset, Video Genset, Photo Dokumen HSE
        Text("DOKUMENTASI GENSET (FOTO, VIDEO & HSE):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)

        Button(
            onClick = {
                if (!LocationUtil.isGpsEnabled(context)) { showGpsAlert = true; return@Button }
                val dir = File(context.cacheDir, "checkpoints").apply { mkdirs() }
                val file = File(dir, "Genset_Fisik_${System.currentTimeMillis()}.jpg")
                tempCapturePhotoGenset = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                photoGensetLauncher.launch(uri)
            },
            enabled = !isSubmitting && !isProcessingMedia,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (photoGensetFile != null) Color(0xFF2E7D32) else ColorCyprus
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text(if (photoGensetFile != null) "✅ 1. Photo Genset Tersimpan (Ambil Ulang)" else "📷 1. Ambil Photo Genset *")
        }

        Button(
            onClick = {
                if (!LocationUtil.isGpsEnabled(context)) { showGpsAlert = true; return@Button }
                val dir = File(context.cacheDir, "videos").apply { mkdirs() }
                val file = File(dir, "Genset_Video_${System.currentTimeMillis()}.mp4")
                tempCaptureVideoGenset = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                videoGensetLauncher.launch(uri)
            },
            enabled = !isSubmitting && !isProcessingMedia,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (videoGensetFile != null) Color(0xFF2E7D32) else Color(0xFF6A1B9A)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text(if (videoGensetFile != null) "✅ 2. Video Genset Ber-Timemark Siap (Rekam Ulang)" else "🎥 2. Rekam Video Genset")
        }

        Button(
            onClick = {
                if (!LocationUtil.isGpsEnabled(context)) { showGpsAlert = true; return@Button }
                val dir = File(context.cacheDir, "checkpoints").apply { mkdirs() }
                val file = File(dir, "Genset_HSE_${System.currentTimeMillis()}.jpg")
                tempCapturePhotoHse = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                photoHseLauncher.launch(uri)
            },
            enabled = !isSubmitting && !isProcessingMedia,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (photoHseFile != null) Color(0xFF2E7D32) else Color(0xFF0277BD)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text(if (photoHseFile != null) "✅ 3. Photo Dokumen HSE Tersimpan (Ambil Ulang)" else "📄 3. Ambil Photo Dokumen HSE")
        }

        if (isProcessingMedia) {
            Text(
                "⏳ Sedang menempelkan Timemark & Minimap pada media...",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD97706)
            )
        }

        if (photoGensetFile != null) {
            Button(
                onClick = {
                    val mainPhoto = photoGensetFile ?: return@Button
                    isSubmitting = true
                    scope.launch {
                        val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                        val finalNote = noteInput.ifBlank { "Kondisi genset normal" }
                        val res = Uploader.sendGensetCheck(
                            context = context,
                            statusGenset = statusGenset,
                            oilCheck = oilCheck,
                            fuelInfo = fuelInfo,
                            officer = officerName,
                            note = finalNote,
                            address = finalAddress,
                            coords = coordsRaw,
                            photoFisik = mainPhoto,
                            photoHse = photoHseFile,
                            videoFile = videoGensetFile
                        )
                        isSubmitting = false
                        if (res.first) {
                            MediaStorageUtil.recordVerificationLog(context, "Pengecekan Genset", mainPhoto.name, "TERUPLOAD KE CLOUD", coordsRaw)
                            Toast.makeText(context, "Laporan Genset lengkap terkirim ke Google Sheets!", Toast.LENGTH_SHORT).show()
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, "Pengecekan Genset", officerName, finalNote, coordsRaw, mainPhoto.absolutePath, finalAddress)
                            MediaStorageUtil.recordVerificationLog(context, "Pengecekan Genset", mainPhoto.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Disimpan ke Antrean Offline.", Toast.LENGTH_LONG).show()
                        }
                        onSuccess()
                    }
                },
                enabled = !isSubmitting && !isProcessingMedia,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(if (isSubmitting) "Mengunggah Laporan Genset..." else "🚀 Kirim Laporan Genset ke Cloud")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(24.dp)) {
            Text("Kembali")
        }
    }
}

@Composable
fun VideoPatrolScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas") ?: "Petugas" }

    var titleInput by remember { mutableStateOf("Video Keliling Inspeksi Fasilitas") }
    var noteInput by remember { mutableStateOf("") }
    var addressText by remember { mutableStateOf(LocationUtil.getFallbackPatrolArea(context)) }
    var gpsStatusText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var showGpsAlert by remember { mutableStateOf(false) }
    var pendingVideoFile by remember { mutableStateOf<File?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    fun refreshGps() {
        if (!LocationUtil.isGpsEnabled(context)) {
            gpsStatusText = "Layanan Lokasi (GPS) Dimatikan"
            showGpsAlert = true
            return
        }
        addressText = LocationUtil.getFallbackPatrolArea(context)
        LocationUtil.fetchPureGpsLocation(context) { statusText, coords, loc ->
            gpsStatusText = statusText
            coordsRaw = coords
            if (statusText.contains("Dimatikan", true)) showGpsAlert = true
            if (loc != null) {
                scope.launch {
                    addressText = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshGps() }

    GpsDisabledAlertDialog(
        showDialog = showGpsAlert,
        onDismiss = { showGpsAlert = false },
        onOpenSettings = { LocationUtil.openLocationSettings(context) }
    )

    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { recorded ->
        val vFile = pendingVideoFile
        if (recorded && vFile != null && vFile.exists() && vFile.length() > 0L) {
            isSubmitting = true
            Toast.makeText(context, "Memasang Timemark & Minimap pada Video...", Toast.LENGTH_SHORT).show()
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                val finalTitle = titleInput.ifBlank { "Video Keliling Inspeksi Fasilitas" }
                val finalNote = noteInput.ifBlank { "Patroli keliling fasilitas gudang" }

                withContext(Dispatchers.Default) {
                    applyTimemarkToVideoFile(
                        videoFile = vFile,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "$finalTitle • $finalNote"
                    )
                    MediaStorageUtil.saveVideoToPublicGallery(context, vFile, vFile.name)
                }

                val res = Uploader.sendVideoPatrol(context, finalTitle, officerName, finalNote, coordsRaw, vFile)
                isSubmitting = false
                if (res.first) {
                    MediaStorageUtil.recordVerificationLog(context, finalTitle, vFile.name, "TERUPLOAD KE CLOUD", coordsRaw)
                    Toast.makeText(context, "Video Patroli ber-Timemark berhasil diunggah!", Toast.LENGTH_SHORT).show()
                } else {
                    MediaStorageUtil.saveToOfflineQueue(context, finalTitle, officerName, finalNote, coordsRaw, vFile.absolutePath, finalAddress)
                    MediaStorageUtil.recordVerificationLog(context, finalTitle, vFile.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                    Toast.makeText(context, "Video disimpan ke Antrean Offline.", Toast.LENGTH_LONG).show()
                }
                onSuccess()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Rekam Video Patroli Keliling", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Judul / Area Video Patroli") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Catatan Situasi") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                LocationCoordinateBox(addressText, gpsStatusText) { refreshGps() }
            }
        }

        Button(
            onClick = {
                if (!LocationUtil.isGpsEnabled(context)) {
                    showGpsAlert = true
                    return@Button
                }
                val dir = File(context.cacheDir, "videos").apply { mkdirs() }
                val file = File(dir, "Video_Patroli_${System.currentTimeMillis()}.mp4")
                pendingVideoFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                videoLauncher.launch(uri)
            },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (isSubmitting) "⏳ Memasang Timemark & Mengunggah..." else "🎥 Mulai Rekam Video Patroli")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(24.dp)) {
            Text("Kembali")
        }
    }
}
