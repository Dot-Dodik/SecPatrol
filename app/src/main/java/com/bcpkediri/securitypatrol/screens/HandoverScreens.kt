package com.bcpkediri.securitypatrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

// ==========================================
// 1. GENERATOR QR CODE MANDIRI (ZXING)
// ==========================================
object QrUtil {
    fun generateQrCode(content: String, width: Int = 500, height: Int = 500): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}

// ==========================================
// 2. KOMPONEN KAMERA SCANNER DENGAN KONTROL SENTER
// ==========================================
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun CameraScanView(
    modifier: Modifier = Modifier,
    isTorchOn: Boolean = false,
    onBarcodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastScannedTime by remember { mutableLongStateOf(0L) }
    var lastScannedValue by remember { mutableStateOf("") }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

    LaunchedEffect(isTorchOn, cameraControl) {
        try {
            cameraControl?.enableTorch(isTorchOn)
        } catch (_: Exception) {}
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            val executor = Executors.newSingleThreadExecutor()
            val scanner = BarcodeScanning.getClient()

            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    for (barcode in barcodes) {
                                        val raw = barcode.rawValue
                                        if (!raw.isNullOrBlank()) {
                                            val now = System.currentTimeMillis()
                                            if (now - lastScannedTime > 2000 || raw != lastScannedValue) {
                                                lastScannedTime = now
                                                lastScannedValue = raw
                                                ContextCompat.getMainExecutor(ctx).execute {
                                                    onBarcodeScanned(raw)
                                                }
                                            }
                                            break
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }

                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                    cameraControl = camera.cameraControl
                    if (camera.cameraInfo.hasFlashUnit()) {
                        cameraControl?.enableTorch(isTorchOn)
                    }
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = modifier.fillMaxSize()
    )
}

// ==========================================
// 3. LAYAR BUAT QR DENGAN DROPDOWN PILIHAN SHIFT
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateHandoverScreen(onComplete: (Handover) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val giverName = remember { spUser.getString("officer_name", "Petugas Shift Lama") ?: "Petugas Shift Lama" }

    val shiftOptions = listOf("Shift Pagi", "Shift Malam")
    var selectedOldShift by remember {
        val defaultShift = spUser.getString("officer_shift", "Shift Malam") ?: "Shift Malam"
        mutableStateOf(if (defaultShift in shiftOptions) defaultShift else "Shift Malam")
    }
    var selectedNewShift by remember {
        mutableStateOf(if (selectedOldShift.contains("Pagi")) "Shift Malam" else "Shift Pagi")
    }

    var expandedOldShift by remember { mutableStateOf(false) }
    var expandedNewShift by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf("Gudang BCP Kediri") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Buat QR Serah-Terima (Shift Lama)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = giverName,
                    onValueChange = {},
                    label = { Text("Petugas Penyerah (Anda)") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                ExposedDropdownMenuBox(
                    expanded = expandedOldShift,
                    onExpandedChange = { expandedOldShift = !expandedOldShift },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedOldShift,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Shift Lama Anda *", fontWeight = FontWeight.Bold) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedOldShift) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedOldShift,
                        onDismissRequest = { expandedOldShift = false }
                    ) {
                        shiftOptions.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s, fontWeight = FontWeight.SemiBold) },
                                onClick = {
                                    selectedOldShift = s
                                    selectedNewShift = if (s.contains("Pagi")) "Shift Malam" else "Shift Pagi"
                                    expandedOldShift = false
                                }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = expandedNewShift,
                    onExpandedChange = { expandedNewShift = !expandedNewShift },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedNewShift,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Shift Baru Tujuan *", fontWeight = FontWeight.Bold) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedNewShift) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedNewShift,
                        onDismissRequest = { expandedNewShift = false }
                    ) {
                        shiftOptions.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s, fontWeight = FontWeight.SemiBold) },
                                onClick = {
                                    selectedNewShift = s
                                    expandedNewShift = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = area,
                    onValueChange = { area = it },
                    label = { Text("Lokasi Pos / Gudang") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        Button(
            onClick = {
                val ho = Handover(
                    id = "HO-${System.currentTimeMillis()}",
                    giver = giverName,
                    receiver = "Menunggu Scan Rekan Shift Baru",
                    oldShift = selectedOldShift,
                    newShift = selectedNewShift,
                    area = area,
                    created = System.currentTimeMillis()
                )
                onComplete(ho)
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)
        ) {
            Text("Tampilkan QR Code untuk Discan Rekan")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
}

// ==========================================
// 4. LAYAR TAMPILKAN QR + REAL-TIME AUTO-LOCK & NOTIFIKASI
// ==========================================
@Composable
fun ShowQrScreen(h: Handover, onDone: () -> Unit) {
    val context = LocalContext.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val qrData = "HO|${h.id}|${h.giver}|${h.oldShift}|${h.newShift}|${h.area}"
    val qrBitmap = remember(qrData) { QrUtil.generateQrCode(qrData, 600, 600) }

    var isCompletedByReceiver by remember { mutableStateOf(false) }

    LaunchedEffect(h.id) {
        withContext(Dispatchers.IO) {
            while (!isCompletedByReceiver) {
                delay(3000)
                val isDone = Uploader.checkHandoverStatus(context, h.id)
                if (isDone) {
                    withContext(Dispatchers.Main) {
                        isCompletedByReceiver = true
                        spUser.edit().putBoolean("is_handover_done", false).commit()

                        try {
                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                vibrator?.vibrate(500)
                            }
                        } catch (_: Exception) {}
                    }
                    break
                }
            }
        }
    }

    if (isCompletedByReceiver) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text("Serah-Terima Berhasil!", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
            },
            text = {
                Text(
                    "QR Code Anda telah dipindai oleh rekan Shift Baru dan foto selfie serah-terima telah tersimpan di Cloud.\n\nTugas kerja Anda otomatis dikunci kembali dan dinas shift dinyatakan selesai. Selamat beristirahat!",
                    fontSize = 13.sp,
                    color = ColorTextDark
                )
            },
            confirmButton = {
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("OK, Selesai Bekerja")
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("QR Serah-Terima Siap Discan", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(250.dp).clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(modifier = Modifier.size(250.dp).clip(RoundedCornerShape(12.dp)).background(Color.LightGray), contentAlignment = Alignment.Center) {
                        Text("Memuat QR...", color = ColorTextMuted, fontSize = 12.sp)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(ColorSand.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = ColorCyprus)
                    Text("Menunggu rekan memindai QR & selfie...", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = ColorCyprus)
                }

                Text(
                    "Shift: ${h.oldShift} ➔ ${h.newShift}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorCyprus
                )
                Text(
                    "Layar ini akan otomatis memberi notifikasi dan mengunci aplikasi begitu rekan selesai memindai QR.",
                    fontSize = 11.sp,
                    color = ColorTextMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Button(
            onClick = {
                spUser.edit().putBoolean("is_handover_done", false).commit()
                Toast.makeText(context, "Shift Anda diserahkan. Tugas dikunci kembali. Selamat istirahat!", Toast.LENGTH_LONG).show()
                onDone()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
        ) {
            Text("🔒 Kunci Tugas & Selesai Bekerja (Manual)", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Kembali ke Beranda (Tetap Buka)")
        }
    }
}

// ==========================================
// 5. LAYAR SCAN QR DENGAN PROTEKSI IZIN KAMERA
// ==========================================
@Composable
fun ScanQrScreen(
    onScanned: (Handover) -> Unit,
    onSoloHandover: (Handover) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val myName = remember { spUser.getString("officer_name", "Petugas Shift Baru") ?: "Petugas Shift Baru" }
    val myShift = remember { spUser.getString("officer_shift", "Shift Pagi") ?: "Shift Pagi" }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pindai QR Serah-Terima Shift", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                CameraScanView { raw: String ->
                    if (raw.startsWith("HO|")) {
                        val p = raw.split("|")
                        if (p.size >= 6) {
                            val ho = Handover(
                                id = p[1],
                                giver = p[2],
                                receiver = myName,
                                oldShift = p[3],
                                newShift = p[4].ifBlank { myShift },
                                area = p[5],
                                created = System.currentTimeMillis()
                            )
                            onScanned(ho)
                        }
                    }
                }

                ScannerViewfinderOverlay(
                    modifier = Modifier.fillMaxSize(),
                    instructionText = "Posisikan QR Code rekan Shift Lama di dalam kotak"
                )
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Akses Kamera Dibutuhkan", fontWeight = FontWeight.Bold, color = ColorCyprus)
                    Text("Silakan aktifkan izin kamera untuk memindai QR Code.", fontSize = 12.sp, color = ColorTextMuted)
                    Button(
                        onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)
                    ) {
                        Text("Izinkan Akses Kamera")
                    }
                }
            }
        }

        Button(
            onClick = {
                val soloHo = Handover(
                    id = "SOLO-${System.currentTimeMillis()}",
                    giver = "Nihil (Mandiri)",
                    receiver = myName,
                    oldShift = "Shift Sebelumnya",
                    newShift = myShift,
                    area = "Gudang BCP Kediri",
                    created = System.currentTimeMillis()
                )
                onSoloHandover(soloHo)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Serah-Terima Mandiri (Solo / Tanpa Rekan)", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Batal / Kembali") }
    }
}

// BINGKAI BIDIK VIEWFINDER LASER
@Composable
fun ScannerViewfinderOverlay(
    modifier: Modifier = Modifier,
    instructionText: String
) {
    BoxWithConstraints(modifier = modifier) {
        val boxSize = 250.dp
        val verticalSpace = (maxHeight - boxSize) / 2
        val horizontalSpace = (maxWidth - boxSize) / 2

        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(verticalSpace).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = 0.7f)) {
                    Text(text = instructionText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }

            Row(modifier = Modifier.fillMaxWidth().height(boxSize)) {
                Box(modifier = Modifier.width(horizontalSpace).fillMaxHeight().background(Color.Black.copy(alpha = 0.55f)))

                Box(
                    modifier = Modifier.size(boxSize).clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 4.dp.toPx()
                        val len = 28.dp.toPx()
                        val w = size.width
                        val h = size.height
                        val col = Color(0xFF00E676)

                        drawLine(col, Offset(0f, 0f), Offset(len, 0f), stroke)
                        drawLine(col, Offset(0f, 0f), Offset(0f, len), stroke)
                        drawLine(col, Offset(w, 0f), Offset(w - len, 0f), stroke)
                        drawLine(col, Offset(w, 0f), Offset(w, len), stroke)
                        drawLine(col, Offset(0f, h), Offset(len, h), stroke)
                        drawLine(col, Offset(0f, h), Offset(0f, h - len), stroke)
                        drawLine(col, Offset(w, h), Offset(w - len, h), stroke)
                        drawLine(col, Offset(w, h), Offset(w, h - len), stroke)
                    }

                    val infiniteTransition = rememberInfiniteTransition(label = "laser")
                    val laserPos by infiniteTransition.animateFloat(
                        initialValue = 0.05f,
                        targetValue = 0.95f,
                        animationSpec = infiniteRepeatable(animation = tween(1700, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
                        label = "laser_y"
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).offset(y = boxSize * laserPos).background(Color(0xFF00E676)))
                }

                Box(modifier = Modifier.width(horizontalSpace).fillMaxHeight().background(Color.Black.copy(alpha = 0.55f)))
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.TopCenter) {
                Text(text = "Posisikan barcode tepat di dalam kotak", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

// ==========================================
// 6. LAYAR SELFIE DENGAN AUTO-ROTASI & PROTEKSI IZIN
// ==========================================
@Composable
fun SelfieScreen(
    handover: Handover,
    onSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }

    val officerId = remember { spUser.getString("officer_id", "") ?: "" }
    val isDevMode = remember(officerId) { officerId.trim().equals("ADMIN-DEV", ignoreCase = true) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val isSolo = remember(handover) {
        handover.id.startsWith("SOLO-") ||
        handover.giver.contains("Nihil", ignoreCase = true) ||
        handover.giver.equals(handover.receiver, ignoreCase = true)
    }
    val requiredFaces = if (isSolo) 1 else 2

    var detectedFaceCount by remember { mutableIntStateOf(0) }
    var locationText by remember { mutableStateOf("Mencari satelit...") }
    var addressText by remember { mutableStateOf("Mendeteksi alamat...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var isCapturing by remember { mutableStateOf(false) }

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        LocationUtil.fetchPureGpsLocation(context) { locText, raw, loc ->
            locationText = locText
            coordsRaw = raw
            if (loc != null) {
                scope.launch(Dispatchers.IO) {
                    val addr = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                    withContext(Dispatchers.Main) { addressText = addr }
                }
            }
        }
    }

    val faceDetector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Foto Selfie Serah-Terima Shift", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        val isFaceReady = isDevMode || (detectedFaceCount >= requiredFaces)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = if (isFaceReady) Color(0xFFE8F5E9) else Color(0xFFFFF3E0))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isSolo) "MODE SERAH-TERIMA MANDIRI (1 ORANG)" else "MODE SERAH-TERIMA BERPASANGAN (2 ORANG)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = ColorCyprus
                )
                Text(
                    text = if (isFaceReady) {
                        "✓ Wajah Terdeteksi: $detectedFaceCount orang (Target: $requiredFaces orang) — SIAP FOTO"
                    } else {
                        "⚠ Wajah Terdeteksi: $detectedFaceCount orang. Arahkan $requiredFaces wajah ke dalam kamera!"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isFaceReady) Color(0xFF2E7D32) else Color(0xFFD97706)
                )
            }
        }

        if (hasCameraPermission) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                AndroidView(
                    factory = { ctx ->
                        // MODE COMPATIBLE AGAR TIDAK HITAM DI POCO F4 / OPPO
                        val previewView = PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        val executor = Executors.newSingleThreadExecutor()

                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val capture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                    .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                                    .build()
                                imageCapture = capture

                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                analysis.setAnalyzer(executor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                        faceDetector.process(image)
                                            .addOnSuccessListener { faces ->
                                                detectedFaceCount = faces.size
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }

                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_FRONT_CAMERA,
                                    preview,
                                    capture,
                                    analysis
                                )
                            } catch (_: Exception) {}
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Akses Kamera Dibutuhkan", fontWeight = FontWeight.Bold, color = ColorCyprus)
                    Text("Silakan aktifkan izin kamera untuk mengambil foto selfie.", fontSize = 12.sp, color = ColorTextMuted)
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)
                    ) {
                        Text("Izinkan Akses Kamera")
                    }
                }
            }
        }

        Button(
            onClick = {
                val cap = imageCapture ?: return@Button
                isCapturing = true

                val dir = File(context.cacheDir, "handover").apply { if (!exists()) mkdirs() }
                val file = File(dir, "Selfie_HO_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

                cap.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            scope.launch(Dispatchers.Default) {
                                try {
                                    val exif = ExifInterface(file.absolutePath)
                                    val orientation = exif.getAttributeInt(
                                        ExifInterface.TAG_ORIENTATION,
                                        ExifInterface.ORIENTATION_UNDEFINED
                                    )
                                    var rotationDegrees = when (orientation) {
                                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                        else -> 0f
                                    }

                                    val rawBmp = BitmapFactory.decodeFile(file.absolutePath)
                                    if (rawBmp != null) {
                                        if (rotationDegrees == 0f && rawBmp.width > rawBmp.height) {
                                            rotationDegrees = 270f
                                        }

                                        val matrix = Matrix().apply {
                                            if (rotationDegrees != 0f) {
                                                postRotate(rotationDegrees)
                                            }
                                            postScale(-1f, 1f)
                                        }
                                        val fixedBmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)

                                        val fosTemp = FileOutputStream(file)
                                        fixedBmp.compress(Bitmap.CompressFormat.JPEG, 90, fosTemp)
                                        fosTemp.flush()
                                        fosTemp.close()
                                    }

                                    val marked = WatermarkUtil.applyTimemarkWatermark(
                                        filePath = file.absolutePath,
                                        checkpoint = "Serah-terima ${handover.oldShift} ke ${handover.newShift}",
                                        address = addressText,
                                        coordinates = coordsRaw
                                    )
                                    val fos = FileOutputStream(file)
                                    marked.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                                    fos.flush()
                                    fos.close()

                                    MediaStorageUtil.saveImageToPublicGallery(context, marked, "Selfie_HO_${handover.id}")

                                    val isOnline = LocationUtil.checkNetworkAvailable(context)
                                    val actualFaces = detectedFaceCount.coerceAtLeast(1)

                                    if (isOnline && Uploader.getEndpointUrl(context).isNotBlank()) {
                                        val res = Uploader.send(context, handover, file, actualFaces)
                                        withContext(Dispatchers.Main) {
                                            if (res.first) {
                                                spUser.edit().putBoolean("is_handover_done", true).commit()
                                                MediaStorageUtil.recordVerificationLog(context, "Serah-Terima Shift", file.name, "TERUPLOAD KE CLOUD", coordsRaw)
                                                Toast.makeText(context, "Serah-terima berhasil terunggah ke Cloud!", Toast.LENGTH_SHORT).show()
                                                onSuccess()
                                            } else {
                                                MediaStorageUtil.saveToOfflineQueue(context, "Serah-Terima Shift", handover.receiver, "Serah-terima ${handover.oldShift} ke ${handover.newShift}", coordsRaw, file.absolutePath, addressText)
                                                MediaStorageUtil.recordVerificationLog(context, "Serah-Terima Shift", file.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                                                spUser.edit().putBoolean("is_handover_done", true).commit()
                                                Toast.makeText(context, "Disimpan offline di HP.", Toast.LENGTH_LONG).show()
                                                onSuccess()
                                            }
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            MediaStorageUtil.saveToOfflineQueue(context, "Serah-Terima Shift", handover.receiver, "Serah-terima ${handover.oldShift} ke ${handover.newShift}", coordsRaw, file.absolutePath, addressText)
                                            MediaStorageUtil.recordVerificationLog(context, "Serah-Terima Shift", file.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                                            spUser.edit().putBoolean("is_handover_done", true).commit()
                                            Toast.makeText(context, "Disimpan offline di HP.", Toast.LENGTH_LONG).show()
                                            onSuccess()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isCapturing = false
                                        Toast.makeText(context, "Gagal proses foto: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            isCapturing = false
                            Toast.makeText(context, "Gagal jepret: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            },
            enabled = isFaceReady && !isCapturing && hasCameraPermission,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (isCapturing) "Menyimpan & Mengunggah..." else "Ambil Foto Selfie Serah-Terima")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Batal") }
    }
}
