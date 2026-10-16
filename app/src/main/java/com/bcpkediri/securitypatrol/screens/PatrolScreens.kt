package com.bcpkediri.securitypatrol

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.Size
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

private fun downloadMapTileBitmap(coordsText: String): Bitmap? {
    return try {
        val clean = coordsText.replace("°S", "").replace("°E", "").replace("S", "").replace("E", "")
        val parts = clean.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDouble()
        val lon = parts[1].trim().toDouble()
        val zoom = 18
        val factor = 2.0.pow(zoom.toDouble())
        val xTile = ((lon + 180.0) / 360.0 * factor).toInt()
        val latRad = Math.toRadians(lat)
        val yTile = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * factor).toInt()

        val url = URL("https://mt1.google.com/vt/lyrs=s&x=$xTile&y=$yTile&z=$zoom")
        val conn = (url.openConnection() as HttpURLConnection).apply {
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
        val srcBmp = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return
        val workingBmp = srcBmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(workingBmp)
        val w = workingBmp.width.toFloat()
        val h = workingBmp.height.toFloat()
        val scale = (min(w, h) / 1080f).coerceAtLeast(0.45f)

        // 1. Kotak Minimap Satelit di Pojok Kiri Atas
        val mapSide = (260f * scale).toInt()
        val mapX = 36f * scale
        val mapY = 36f * scale
        val mapBounds = RectF(mapX, mapY, mapX + mapSide, mapY + mapSide)

        val bgMapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35, 55, 50) }
        canvas.drawRoundRect(mapBounds, 24f * scale, 24f * scale, bgMapPaint)

        val satTile = downloadMapTileBitmap(coordsText)
        if (satTile != null) {
            val resizedTile = Bitmap.createScaledBitmap(satTile, mapSide, mapSide, true)
            val savedState = canvas.save()
            val clip = Path().apply { addRoundRect(mapBounds, 24f * scale, 24f * scale, Path.Direction.CW) }
            canvas.clipPath(clip)
            canvas.drawBitmap(resizedTile, mapX, mapY, null)
            canvas.restoreToCount(savedState)
        }

        val centerX = mapBounds.centerX()
        val centerY = mapBounds.centerY()
        val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(115, 46, 204, 113)
            style = Paint.Style.FILL
        }
        val beamRadius = mapSide * 0.42f
        val beamRect = RectF(centerX - beamRadius, centerY - beamRadius, centerX + beamRadius, centerY + beamRadius)
        canvas.drawArc(beamRect, LocationUtil.currentAzimuth - 120f, 60f, true, beamPaint)

        val paintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val paintBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(25, 118, 210) }
        canvas.drawCircle(centerX, centerY, 20f * scale, paintBlue)
        canvas.drawCircle(centerX, centerY, 9f * scale, paintWhite)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * scale
        }
        canvas.drawRoundRect(mapBounds, 24f * scale, 24f * scale, borderPaint)

        val wmMapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 26f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
        }
        canvas.drawText("Google", mapX + 16f * scale, mapBounds.bottom - 16f * scale, wmMapPaint)

        // 2. Pita Putih di Bagian Bawah
        val ribbonH = 74f * scale
        val ribbonTop = h - ribbonH - (18f * scale)
        val ribbonRect = RectF(36f * scale, ribbonTop, w - 36f * scale, h - 18f * scale)
        canvas.drawRoundRect(ribbonRect, 18f * scale, 18f * scale, paintWhite)

        val ribbonTxtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 30f * scale
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText(bottomBannerText.take(46), 54f * scale, ribbonRect.centerY() + (10f * scale), ribbonTxtPaint)

        // 3. Kotak Info BORWITA Security Patrol
        val boxH = 225f * scale
        val boxTop = ribbonTop - boxH - (10f * scale)
        val boxRect = RectF(36f * scale, boxTop, w - 36f * scale, ribbonTop - (6f * scale))
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(180, 25, 25, 25) }
        canvas.drawRoundRect(boxRect, 22f * scale, 22f * scale, boxPaint)

        val flagX = 58f * scale
        val flagY = boxTop + 24f * scale
        val flagW = 52f * scale
        val flagH = 34f * scale
        val paintRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(211, 47, 47) }
        canvas.drawRect(flagX, flagY, flagX + flagW, flagY + flagH / 2f, paintRed)
        canvas.drawRect(flagX, flagY + flagH / 2f, flagX + flagW, flagY + flagH, paintWhite)

        val companyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 31f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("BORWITA", flagX + flagW + 18f * scale, flagY + 28f * scale, companyPaint)

        val pillLeft = flagX + flagW + 190f * scale
        val pillRect = RectF(pillLeft, flagY - 2f * scale, pillLeft + 230f * scale, flagY + flagH + 4f * scale)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(46, 139, 87) }
        canvas.drawRoundRect(pillRect, 10f * scale, 10f * scale, pillPaint)

        val pillTxtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 22f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(badgeLabel.take(16), pillLeft + 16f * scale, flagY + 25f * scale, pillTxtPaint)

        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(235, 235, 235)
            textSize = 24f * scale
        }
        val dateStr = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale("id", "ID")).format(Date())
        var lineY = flagY + flagH + 40f * scale
        canvas.drawText(addressText.take(56), flagX, lineY, infoPaint)
        lineY += 38f * scale
        canvas.drawText("Lat/Long: $coordsText  •  Petugas: $officerName", flagX, lineY, infoPaint)
        lineY += 38f * scale
        canvas.drawText(dateStr, flagX, lineY, infoPaint)

        FileOutputStream(imageFile).use { stream ->
            workingBmp.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            stream.flush()
        }
        MediaStorageUtil.saveImageToPublicGallery(context, workingBmp, imageFile.name)
    } catch (_: Exception) {}
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

// =========================================================================
// 1. LAYAR TITIK POS PATROLI (DINAMIS DARI QR + PREVIEW/RETAKE + JEDA 30 MENIT)
// =========================================================================
@Composable
fun CheckpointScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val spCooldown = remember { context.getSharedPreferences("checkpoint_cooldown_prefs", Context.MODE_PRIVATE) }

    val officerName = remember { spUser.getString("officer_name", "Petugas") ?: "Petugas" }
    val isDevMode = remember {
        (spUser.getString("officer_id", "") ?: "").equals("ADMIN-DEV", true) ||
        spUser.getBoolean("is_dev_mode", false)
    }

    var scannedPosName by remember { mutableStateOf("") }
    var isScanningQr by remember { mutableStateOf(false) }
    var cooldownErrorMsg by remember { mutableStateOf("") }
    var noteInput by remember { mutableStateOf("") }

    var addressText by remember { mutableStateOf(LocationUtil.getFallbackPatrolArea(context)) }
    var gpsStatusText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var showGpsAlert by remember { mutableStateOf(false) }

    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var capturedPhotoFile by remember { mutableStateOf<File?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessingPhoto by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    val colorTealScan = Color(0xFF00695C)
    val cooldownDurationMs = 30L * 60L * 1000L // 30 Menit

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
            isProcessingPhoto = true
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                val posLabel = scannedPosName.ifBlank { "Titik Pos Patroli" }
                val bmp = withContext(Dispatchers.IO) {
                    applyPhotoAndVideoTimemark(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "$posLabel • ${noteInput.ifBlank { "Aman / Terkendali" }}"
                    )
                    BitmapFactory.decodeFile(file.absolutePath)
                }
                capturedPhotoFile = file
                previewBitmap = bmp
                isProcessingPhoto = false
            }
        }
    }

    fun launchCheckpointCamera() {
        if (!LocationUtil.isGpsEnabled(context)) {
            showGpsAlert = true
            return
        }
        val cleanName = scannedPosName.ifBlank { "Pos_Patroli" }.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = File(context.cacheDir, "checkpoints").apply { mkdirs() }
        val file = File(dir, "${cleanName}_${System.currentTimeMillis()}.jpg")
        pendingCaptureFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraLauncher.launch(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Titik Pos Patroli", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("STATUS LOKASI TITIK POS (HASIL SCAN QR):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)

                if (scannedPosName.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFE8F5E9),
                        border = BorderStroke(1.dp, Color(0xFF2E7D32)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("✅ TITIK POS TERVERIFIKASI QR:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            Text(scannedPosName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF8E1),
                        border = BorderStroke(1.dp, Color(0xFFFFB300)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Belum ada QR Titik Pos yang dipindai.\nTekan tombol 'Scan Barcode / QR Titik Pos' di bagian bawah untuk memunculkan nama/nomor pos.",
                            fontSize = 12.sp,
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                if (cooldownErrorMsg.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFFFEBEE),
                        border = BorderStroke(1.dp, Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = cooldownErrorMsg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB71C1C),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (isScanningQr) {
                    QrCheckpointScannerBox(
                        onQrDetected = { rawQr ->
                            isScanningQr = false
                            val normalizedKey = rawQr.trim().uppercase()
                            val lastScanMs = spCooldown.getLong("last_scan_$normalizedKey", 0L)
                            val elapsedMs = System.currentTimeMillis() - lastScanMs

                            if (!isDevMode && lastScanMs > 0L && elapsedMs < cooldownDurationMs) {
                                val remainMinutes = ((cooldownDurationMs - elapsedMs) / 60000L).toInt() + 1
                                scannedPosName = ""
                                capturedPhotoFile = null
                                previewBitmap = null
                                cooldownErrorMsg = "⏳ Titik '$rawQr' sudah dipatroli. Harap tunggu $remainMinutes menit lagi (Aturan jeda 30 menit)."
                                Toast.makeText(context, "Tunggu $remainMinutes menit lagi untuk titik ini!", Toast.LENGTH_LONG).show()
                            } else {
                                cooldownErrorMsg = ""
                                scannedPosName = rawQr.trim()
                                capturedPhotoFile = null
                                previewBitmap = null
                                Toast.makeText(context, "Titik Pos Terdeteksi: $scannedPosName", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onCloseScanner = { isScanningQr = false }
                    )
                }

                // KOTAK PREVIEW PHOTO (Muncul setelah QR discan / foto diambil)
                if (isProcessingPhoto) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⏳ Memproses Timemark & Minimap Foto...", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)
                    }
                } else if (previewBitmap != null) {
                    Text("PREVIEW FOTO BUKTI PATROLI:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "Preview Foto Patroli",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
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

        // =========================================================
        // TOMBOL AKSI DI BAGIAN BAWAH (SCAN QR -> AMBIL FOTO -> PREVIEW/RETAKE/KIRIM)
        // =========================================================
        Button(
            onClick = { isScanningQr = !isScanningQr },
            enabled = !isSubmitting && !isProcessingPhoto,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (scannedPosName.isNotBlank()) Color(0xFF004D40) else colorTealScan
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(
                if (scannedPosName.isNotBlank()) "📷 Scan Ulang Barcode / Ganti Titik Pos"
                else "📷 Scan Barcode / QR Titik Pos"
            )
        }

        if (isDevMode && scannedPosName.isBlank()) {
            OutlinedButton(
                onClick = {
                    cooldownErrorMsg = ""
                    scannedPosName = "Pos Uji (ADMIN-DEV)"
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔓 Mode ADMIN-DEV: Buka Kamera Tanpa Scan QR", fontSize = 12.sp, color = ColorCyprus)
            }
        }

        // Tombol Ambil Foto hanya muncul setelah QR berhasil discan
        if (scannedPosName.isNotBlank() && capturedPhotoFile == null) {
            Button(
                onClick = { launchCheckpointCamera() },
                enabled = !isSubmitting && !isProcessingPhoto,
                colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("📸 Ambil Foto Bukti Patroli ($scannedPosName)")
            }
        }

        // Menu Retake Foto & Kirim ke Cloud muncul setelah foto diambil
        if (scannedPosName.isNotBlank() && capturedPhotoFile != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { launchCheckpointCamera() },
                    enabled = !isSubmitting && !isProcessingPhoto,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("🔄 Retake Foto", fontWeight = FontWeight.Bold, color = ColorCyprus)
                }

                Button(
                    onClick = {
                        val fileToUpload = capturedPhotoFile ?: return@Button
                        isSubmitting = true
                        scope.launch {
                            val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                            val finalNote = "[QR: $scannedPosName] ${noteInput.ifBlank { "Aman / Terkendali" }}"

                            // Simpan waktu scan 30 menit untuk titik pos ini
                            spCooldown.edit()
                                .putLong("last_scan_${scannedPosName.trim().uppercase()}", System.currentTimeMillis())
                                .commit()

                            val res = if (LocationUtil.checkNetworkAvailable(context)) {
                                Uploader.sendCheckpoint(context, scannedPosName, officerName, finalNote, finalAddress, coordsRaw, fileToUpload)
                            } else {
                                Pair(false, "Perangkat sedang offline")
                            }

                            isSubmitting = false
                            if (res.first) {
                                MediaStorageUtil.recordVerificationLog(context, scannedPosName, fileToUpload.name, "TERUPLOAD KE CLOUD", coordsRaw)
                                Toast.makeText(context, "Laporan $scannedPosName terkirim ke Cloud!", Toast.LENGTH_SHORT).show()
                            } else {
                                MediaStorageUtil.saveToOfflineQueue(context, scannedPosName, officerName, finalNote, coordsRaw, fileToUpload.absolutePath, finalAddress)
                                MediaStorageUtil.recordVerificationLog(context, scannedPosName, fileToUpload.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                                Toast.makeText(context, "Disimpan ke Antrean Offline (Kirim saat klik Sync Cloud).", Toast.LENGTH_LONG).show()
                            }
                            onSuccess()
                        }
                    },
                    enabled = !isSubmitting && !isProcessingPhoto,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.3f).height(48.dp)
                ) {
                    Text(if (isSubmitting) "Mengirim..." else "🚀 Kirim ke Cloud")
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(24.dp)) {
            Text("Kembali")
        }
    }
}

// =========================================================================
// 2. LAYAR DOKUMENTASI GUDANG & FASILITAS
// =========================================================================
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

// =========================================================================
// 3. LAYAR TRUCK INBOUND (DENGAN TOMBOL BERSIHKAN JIKA ADA ANTREAN NYANTOL)
// =========================================================================
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
    var showClearInboundConfirm by remember { mutableStateOf(false) }

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

    fun resetInboundForm() {
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
            saveLocalQueue(remote)
            if (remote.isEmpty()) resetInboundForm()
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

    if (showClearInboundConfirm) {
        AlertDialog(
            onDismissRequest = { showClearInboundConfirm = false },
            title = { Text("Bersihkan Antrean Truck?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Gunakan fitur ini jika ada antrean truk yang nyantol padahal semua proses bongkar muat sudah selesai.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearInboundConfirm = false
                        saveLocalQueue(emptyList())
                        resetInboundForm()
                        Toast.makeText(context, "Daftar antrean outstanding berhasil dibersihkan!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                ) { Text("Ya, Bersihkan") }
            },
            dismissButton = {
                TextButton(onClick = { showClearInboundConfirm = false }) { Text("Batal") }
            }
        )
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
            Text("Dokumentasi Truck Inbound", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // Tombol Bersihkan HANYA muncul jika ada antrean
                if (queueList.isNotEmpty()) {
                    Text(
                        text = "🗑️ Bersihkan",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB71C1C),
                        modifier = Modifier.clickable { showClearInboundConfirm = true }.padding(4.dp)
                    )
                }
                Text(
                    text = if (isSyncingQueue) "⏳..." else "🔄 Sinkron",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorCyprus,
                    modifier = Modifier.clickable { syncCloudQueue() }.padding(4.dp)
                )
            }
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
                                resetInboundForm()
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
                        if (queueList.isNotEmpty()) {
                            Divider()
                            DropdownMenuItem(
                                text = { Text("🗑️ Bersihkan Semua Antrean Nyantol", color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold) },
                                onClick = {
                                    dropdownExpanded = false
                                    showClearInboundConfirm = true
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

// =========================================================================
// 4. LAYAR PENGECEKAN GENSET (3 TOMBOL MEDIA + OPSI WARMING UP)
// =========================================================================
@Composable
fun GensetCheckScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas") ?: "Petugas" }

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

    val videoGensetLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { recorded ->
        val vf = tempCaptureVideoGenset
        if (recorded && vf != null && vf.exists() && vf.length() > 0L) {
            isProcessingMedia = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    MediaStorageUtil.saveVideoToPublicGallery(context, vf, vf.name)
                }
                videoGensetFile = vf
                isProcessingMedia = false
                Toast.makeText(context, "Video Genset siap!", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

        Text("DOKUMENTASI GENSET (3 MEDIA):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)

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
            Text(if (videoGensetFile != null) "✅ 2. Video Genset Tersimpan (Rekam Ulang)" else "🎥 2. Rekam Video Genset")
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
                            Toast.makeText(context, "Laporan Genset terkirim ke Google Sheets!", Toast.LENGTH_SHORT).show()
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

// =========================================================================
// 5. LAYAR REKAM VIDEO PATROLI KELILING
// =========================================================================
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
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                val finalTitle = titleInput.ifBlank { "Video Keliling Inspeksi Fasilitas" }
                val finalNote = "[$finalAddress] ${noteInput.ifBlank { "Patroli keliling fasilitas gudang" }}"

                withContext(Dispatchers.IO) {
                    MediaStorageUtil.saveVideoToPublicGallery(context, vFile, vFile.name)
                }

                val res = Uploader.sendVideoPatrol(context, finalTitle, officerName, finalNote, coordsRaw, vFile)
                isSubmitting = false
                if (res.first) {
                    MediaStorageUtil.recordVerificationLog(context, finalTitle, vFile.name, "TERUPLOAD KE CLOUD", coordsRaw)
                    Toast.makeText(context, "Video Patroli berhasil diunggah!", Toast.LENGTH_SHORT).show()
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
            Text(if (isSubmitting) "⏳ Mengunggah Video..." else "🎥 Mulai Rekam Video Patroli")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(24.dp)) {
            Text("Kembali")
        }
    }
}
