package com.bcpkediri.securitypatrol

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

private fun generateQrBitmap(content: String, sizePx: Int = 640): Bitmap? {
    return try {
        val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val w = bitMatrix.width
        val h = bitMatrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun ShiftSelectorRow(
    title: String,
    selectedShift: String,
    onSelectShift: (String) -> Unit
) {
    val shifts = listOf("Shift Pagi", "Shift Malam")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shifts.forEach { shiftName ->
                val isSelected = selectedShift.equals(shiftName, ignoreCase = true)
                Button(
                    onClick = { onSelectShift(shiftName) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) ColorCyprus else Color(0xFFE0DDD5),
                        contentColor = if (isSelected) Color.White else ColorTextDark
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) {
                    Text(
                        text = if (isSelected) "✓ $shiftName" else shiftName,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun ShowQrScreen(h: Handover, onDone: () -> Unit) {
    val context = LocalContext.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }

    var selectedOldShift by remember {
        mutableStateOf(if (h.oldShift.contains("Malam", true)) "Shift Malam" else "Shift Pagi")
    }
    var selectedNewShift by remember {
        mutableStateOf(
            if (h.newShift.isNotBlank()) h.newShift
            else if (selectedOldShift == "Shift Pagi") "Shift Malam" else "Shift Pagi"
        )
    }

    val qrPayload = remember(h.id, h.giver, selectedOldShift, selectedNewShift, h.area) {
        JSONObject().apply {
            put("id", h.id)
            put("giver", h.giver)
            put("receiver", h.receiver)
            put("oldShift", selectedOldShift)
            put("newShift", selectedNewShift)
            put("area", h.area)
            put("created", h.created)
        }.toString()
    }

    val qrBitmap = remember(qrPayload) { generateQrBitmap(qrPayload) }

    LaunchedEffect(h.id) {
        while (true) {
            delay(3500)
            val completed = Uploader.checkHandoverStatus(context, h.id)
            if (completed) {
                try {
                    val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vib?.vibrate(500)
                    }
                } catch (_: Exception) {}
                Toast.makeText(context, "Rekan berhasil memindai QR & Selfie! Shift Anda selesai.", Toast.LENGTH_LONG).show()
                onDone()
                break
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("QR Serah-Terima Siap Discan", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShiftSelectorRow(
                    title = "SHIFT PENYERAH (SHIFT LAMA):",
                    selectedShift = selectedOldShift,
                    onSelectShift = {
                        selectedOldShift = it
                        selectedNewShift = if (it == "Shift Pagi") "Shift Malam" else "Shift Pagi"
                        spUser.edit().putString("officer_shift", it).commit()
                    }
                )

                ShiftSelectorRow(
                    title = "SHIFT PENERIMA (SHIFT BARU):",
                    selectedShift = selectedNewShift,
                    onSelectShift = { selectedNewShift = it }
                )

                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Handover",
                        modifier = Modifier.size(230.dp).padding(4.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFEDE9E0)
                ) {
                    Text(
                        "⏳ Menunggu rekan memindai QR & selfie...",
                        fontSize = 12.sp,
                        color = ColorTextDark,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Text(
                    text = "Shift: $selectedOldShift → $selectedNewShift",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorCyprus
                )

                Text(
                    text = "Layar ini akan otomatis memberi notifikasi dan mengunci aplikasi begitu rekan selesai memindai QR.",
                    fontSize = 11.sp,
                    color = ColorTextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }

        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("🔒 Kunci Tugas & Selesai Bekerja (Manual)")
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun ScanQrScreen(
    onScanned: (Handover) -> Unit,
    onSoloHandover: (Handover) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val receiverName = remember { spUser.getString("officer_name", "Petugas Baru") ?: "Petugas Baru" }
    val branch = remember { spUser.getString("officer_branch", "Cabang Kediri") ?: "Cabang Kediri" }

    var selectedOldShift by remember { mutableStateOf("Shift Malam") }
    var selectedNewShift by remember {
        mutableStateOf(spUser.getString("officer_shift", "Shift Pagi") ?: "Shift Pagi")
    }

    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var hasScanned by remember { mutableStateOf(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laserLine"
    )

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pindai QR Serah-Terima Shift", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        // Kotak Pilihan Shift Penyerah & Penerima (Untuk Scan QR maupun Serah-Terima Mandiri)
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShiftSelectorRow(
                    title = "PILIH SHIFT PENYERAH (SHIFT SEBELUMNYA):",
                    selectedShift = selectedOldShift,
                    onSelectShift = {
                        selectedOldShift = it
                        selectedNewShift = if (it == "Shift Pagi") "Shift Malam" else "Shift Pagi"
                    }
                )
                ShiftSelectorRow(
                    title = "PILIH SHIFT PENERIMA (SHIFT ANDA SEKARANG):",
                    selectedShift = selectedNewShift,
                    onSelectShift = {
                        selectedNewShift = it
                        spUser.edit().putString("officer_shift", it).commit()
                    }
                )
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(360.dp)
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
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(Size(1280, 720))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(cameraExecutor) { proxy ->
                                val mediaImg = proxy.image
                                if (mediaImg != null && !hasScanned) {
                                    val img = InputImage.fromMediaImage(mediaImg, proxy.imageInfo.rotationDegrees)
                                    scanner.process(img)
                                        .addOnSuccessListener { barcodes ->
                                            for (b in barcodes) {
                                                val raw = b.rawValue
                                                if (!raw.isNullOrBlank() && !hasScanned) {
                                                    hasScanned = true
                                                    val ho = try {
                                                        val obj = JSONObject(raw)
                                                        Handover(
                                                            id = obj.optString("id", "HO-${System.currentTimeMillis()}"),
                                                            giver = obj.optString("giver", "Shift Sebelumnya"),
                                                            receiver = receiverName,
                                                            oldShift = obj.optString("oldShift", selectedOldShift).ifBlank { selectedOldShift },
                                                            newShift = obj.optString("newShift", selectedNewShift).ifBlank { selectedNewShift },
                                                            area = obj.optString("area", branch),
                                                            created = obj.optLong("created", System.currentTimeMillis())
                                                        )
                                                    } catch (_: Exception) {
                                                        Handover(
                                                            id = raw.take(40),
                                                            giver = "Shift Sebelumnya",
                                                            receiver = receiverName,
                                                            oldShift = selectedOldShift,
                                                            newShift = selectedNewShift,
                                                            area = branch,
                                                            created = System.currentTimeMillis()
                                                        )
                                                    }
                                                    onScanned(ho)
                                                    break
                                                }
                                            }
                                        }
                                        .addOnCompleteListener { proxy.close() }
                                } else {
                                    proxy.close()
                                }
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraRef = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                            } catch (_: Exception) {}
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                )

                // Overlay Kotak Batasan + Sudut Hijau + Laser Hijau
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val boxSize = size.minDimension * 0.62f
                    val left = (size.width - boxSize) / 2f
                    val top = (size.height - boxSize) / 2f
                    val right = left + boxSize
                    val bottom = top + boxSize
                    val cornerLen = 36f
                    val strokeW = 7f
                    val greenColor = Color(0xFF00E676)

                    // Sudut Kiri Atas
                    drawLine(greenColor, Offset(left, top), Offset(left + cornerLen, top), strokeW)
                    drawLine(greenColor, Offset(left, top), Offset(left, top + cornerLen), strokeW)
                    // Sudut Kanan Atas
                    drawLine(greenColor, Offset(right, top), Offset(right - cornerLen, top), strokeW)
                    drawLine(greenColor, Offset(right, top), Offset(right, top + cornerLen), strokeW)
                    // Sudut Kiri Bawah
                    drawLine(greenColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW)
                    drawLine(greenColor, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW)
                    // Sudut Kanan Bawah
                    drawLine(greenColor, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW)
                    drawLine(greenColor, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW)

                    // Garis Laser Hijau Bergerak
                    val laserY = top + (boxSize * laserProgress)
                    drawLine(
                        color = greenColor,
                        start = Offset(left + 12f, laserY),
                        end = Offset(right - 12f, laserY),
                        strokeWidth = 5f
                    )
                }

                // Tombol Senter di Pojok Kanan Atas Kamera
                Button(
                    onClick = {
                        val nextTorch = !isTorchOn
                        isTorchOn = nextTorch
                        cameraRef?.cameraControl?.enableTorch(nextTorch)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTorchOn) Color(0xFFFFC107) else Color(0x99000000),
                        contentColor = if (isTorchOn) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Text(if (isTorchOn) "🔦 Senter ON" else "🔦 Senter", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "Posisikan barcode tepat di dalam kotak",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                )
            }
        }

        Button(
            onClick = {
                val soloHo = Handover(
                    id = "HO-SOLO-${System.currentTimeMillis()}",
                    giver = "Shift Sebelumnya ($selectedOldShift)",
                    receiver = receiverName,
                    oldShift = selectedOldShift,
                    newShift = selectedNewShift,
                    area = branch.ifBlank { "Gudang BCP Kediri" },
                    created = System.currentTimeMillis()
                )
                onSoloHandover(soloHo)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Serah-Terima Mandiri ($selectedOldShift → $selectedNewShift)")
        }

        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text("Batal / Kembali")
        }
    }
}

@Composable
fun SelfieScreen(
    handover: Handover,
    onSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", handover.receiver.ifBlank { "Petugas" }) ?: "Petugas" }

    var selectedOldShift by remember {
        mutableStateOf(if (handover.oldShift.contains("Malam", true)) "Shift Malam" else "Shift Pagi")
    }
    var selectedNewShift by remember {
        mutableStateOf(if (handover.newShift.contains("Malam", true)) "Shift Malam" else "Shift Pagi")
    }

    var addressText by remember { mutableStateOf(LocationUtil.getFallbackPatrolArea(context)) }
    var gpsStatusText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var isUploading by remember { mutableStateOf(false) }

    fun refreshLocation() {
        addressText = LocationUtil.getFallbackPatrolArea(context)
        LocationUtil.fetchPureGpsLocation(context) { statusText, coords, loc ->
            gpsStatusText = statusText
            coordsRaw = coords
            if (loc != null) {
                scope.launch {
                    addressText = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshLocation() }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingFile
        if (taken && file != null && file.exists()) {
            isUploading = true
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                val updatedHo = handover.copy(
                    receiver = officerName,
                    oldShift = selectedOldShift,
                    newShift = selectedNewShift
                )
                val bannerTitle = "Serah-terima $selectedOldShift ke $selectedNewShift"

                withContext(Dispatchers.IO) {
                    applyPhotoAndVideoTimemark(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Security Patrol",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = bannerTitle
                    )
                }

                spUser.edit().putString("officer_shift", selectedNewShift).commit()

                val res = Uploader.send(context, updatedHo, file, 2)
                isUploading = false
                if (res.first) {
                    MediaStorageUtil.recordVerificationLog(context, "Serah-Terima Shift", file.name, "TERUPLOAD KE CLOUD", coordsRaw)
                    Toast.makeText(context, "Serah-Terima ($selectedOldShift → $selectedNewShift) berhasil!", Toast.LENGTH_SHORT).show()
                } else {
                    MediaStorageUtil.saveToOfflineQueue(context, "Serah-Terima Shift", officerName, bannerTitle, coordsRaw, file.absolutePath, finalAddress)
                    MediaStorageUtil.recordVerificationLog(context, "Serah-Terima Shift", file.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                    Toast.makeText(context, "Disimpan ke Antrean Offline.", Toast.LENGTH_LONG).show()
                }
                onSuccess()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Verifikasi Selfie Serah-Terima", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Penyerah: ${handover.giver.ifBlank { "Shift Sebelumnya" }}", fontSize = 13.sp, color = ColorTextDark)
                Text("Penerima: $officerName", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)

                ShiftSelectorRow(
                    title = "SHIFT PENYERAH (LAMA):",
                    selectedShift = selectedOldShift,
                    onSelectShift = { selectedOldShift = it }
                )

                ShiftSelectorRow(
                    title = "SHIFT PENERIMA (BARU):",
                    selectedShift = selectedNewShift,
                    onSelectShift = { selectedNewShift = it }
                )

                Text("Lokasi: $addressText", fontSize = 12.sp, color = ColorTextDark)
                Text(gpsStatusText, fontSize = 11.sp, color = ColorCyprus, fontWeight = FontWeight.SemiBold)
            }
        }

        Button(
            onClick = {
                val dir = File(context.cacheDir, "handover").apply { mkdirs() }
                val file = File(dir, "Selfie_HO_${System.currentTimeMillis()}.jpg")
                pendingFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                cameraLauncher.launch(uri)
            },
            enabled = !isUploading,
            colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (isUploading) "Memproses & Mengunggah..." else "🤳 Ambil Foto Selfie ($selectedOldShift → $selectedNewShift)")
        }

        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text("Batal / Kembali")
        }
    }
}
