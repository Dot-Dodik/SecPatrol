package com.bcpkediri.securitypatrol

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import java.util.*
import java.util.concurrent.Executors

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

/**
 * Menerapkan Timemark BORWITA + Minimap Satelit seragam seperti pada foto Selfie
 */
private fun applyStandardTimemarkWithMinimap(
    context: Context,
    imageFile: File,
    badgeLabel: String,
    officerName: String,
    coordsText: String,
    addressText: String,
    bottomBannerText: String
) {
    try {
        WatermarkUtil.applyTimemarkWatermark(
            file = imageFile,
            badgeText = badgeLabel,
            address = addressText,
            coords = coordsText,
            officer = officerName,
            bottomTitle = bottomBannerText,
            azimuth = LocationUtil.currentAzimuth
        )
        val bmp = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (bmp != null) {
            MediaStorageUtil.saveImageToPublicGallery(context, bmp, imageFile.name)
        }
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
                Text("Layanan Lokasi (GPS) sedang mati. Harap aktifkan GPS agar titik koordinat satelit dan Minimap pada foto terekam dengan benar.")
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var hasScanned by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        modifier = Modifier.fillMaxWidth().height(280.dp)
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
                            cameraProvider.bindToLifecycle(
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

            Surface(
                color = Color(0xAA000000),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            ) {
                Text(
                    "Arahkan kamera ke Barcode / QR Code Titik Pos",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            TextButton(
                onClick = onCloseScanner,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
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

    val checkpointOptions = listOf(
        "Pos 1",
        "Pos 2",
        "Pos 3",
        "Pos 4",
        "Pos 5 (Gerbang Utama)",
        "Pos 6 (Area Keliling)"
    )
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
                val finalNote = "$qrInfo${noteInput.ifBlank { "Situasi aman terkendali" }}"
                withContext(Dispatchers.IO) {
                    applyStandardTimemarkWithMinimap(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Patroli $selectedCheckpoint",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "$selectedCheckpoint • ${noteInput.ifBlank { "Aman Terkendali" }}"
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
                    Toast.makeText(context, "Gagal kirim ke Server (${res.second}). Disimpan di Antrean Offline.", Toast.LENGTH_LONG).show()
                }
                onSuccess()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Patroli Titik Pos (Pos 1 - Pos 6)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("1. PILIH ATAU SCAN QR TITIK POS:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)

                if (isScanningQr) {
                    QrCheckpointScannerBox(
                        onQrDetected = { qrValue ->
                            isScanningQr = false
                            scannedQrCode = qrValue
                            val matchedPos = checkpointOptions.firstOrNull { pos ->
                                qrValue.contains(pos.take(5), ignoreCase = true)
                            }
                            if (matchedPos != null) selectedCheckpoint = matchedPos
                            Toast.makeText(context, "QR Pos Terverifikasi: $qrValue", Toast.LENGTH_SHORT).show()
                        },
                        onCloseScanner = { isScanningQr = false }
                    )
                } else {
                    Button(
                        onClick = { isScanningQr = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (scannedQrCode.isNotBlank()) Color(0xFF2E7D32) else Color(0xFF00695C)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text(
                            if (scannedQrCode.isNotBlank()) "✅ QR Pos Terverifikasi ($scannedQrCode) - Scan Ulang"
                            else "📷 Scan QR Code Titik Pos (Pos 1 - 6)"
                        )
                    }
                }

                checkpointOptions.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedCheckpoint = option
                            if (isDevMode && scannedQrCode.isBlank()) {
                                scannedQrCode = "DEV-${option.take(5).uppercase().replace(" ", "")}"
                            }
                        }
                    ) {
                        RadioButton(
                            selected = selectedCheckpoint == option,
                            onClick = {
                                selectedCheckpoint = option
                                if (isDevMode && scannedQrCode.isBlank()) {
                                    scannedQrCode = "DEV-${option.take(5).uppercase().replace(" ", "")}"
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
                "⚠️ Silakan Scan QR Code Titik Pos terlebih dahulu untuk membuka kamera bukti patroli.",
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
                val file = File(dir, "${selectedCheckpoint.take(5).replace(" ", "_")}_${System.currentTimeMillis()}.jpg")
                pendingCaptureFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                cameraLauncher.launch(uri)
            },
            enabled = !isSubmitting && canTakePhoto,
            colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
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
        "Pintu Gudang & Gembok",
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
                    applyStandardTimemarkWithMinimap(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Fasilitas Gudang",
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
                        val finalNote = noteInput.ifBlank { "Kondisi fasilitas aman" }
                        val res = Uploader.sendFacilityMulti(context, selectedFacility, officerName, finalNote, finalAddress, coordsRaw, photoFiles.toList())
                        isSubmitting = false
                        val firstFile = photoFiles.first()
                        if (res.first) {
                            MediaStorageUtil.recordVerificationLog(context, selectedFacility, firstFile.name, "TERUPLOAD KE CLOUD", coordsRaw)
                            Toast.makeText(context, "Dokumentasi fasilitas terkirim ke Google Sheets!", Toast.LENGTH_SHORT).show()
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, selectedFacility, officerName, finalNote, coordsRaw, firstFile.absolutePath, finalAddress)
                            MediaStorageUtil.recordVerificationLog(context, selectedFacility, firstFile.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Gagal kirim (${res.second}). Disimpan di Antrean Offline.", Toast.LENGTH_LONG).show()
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
                    applyStandardTimemarkWithMinimap(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Inbound $cleanNopol",
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
                            applyStandardTimemarkWithMinimap(
                                context = context,
                                imageFile = destFile,
                                badgeLabel = "Inbound $cleanNopol",
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
                            Toast.makeText(context, "Gagal kirim (${res.second}). Disimpan ke Antrean Offline.", Toast.LENGTH_LONG).show()
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

    val statusList = listOf("Standby / Mati Normal", "Menyala (Running)", "Perlu Perbaikan")
    val oilList = listOf("Normal / Cukup", "Kurang / Perlu Tambah")
    val fuelList = listOf("Penuh (75-100%)", "Sedang (40-75%)", "Menipis (<40%)")

    var statusGenset by remember { mutableStateOf(statusList[0]) }
    var oilCheck by remember { mutableStateOf(oilList[0]) }
    var fuelInfo by remember { mutableStateOf(fuelList[0]) }
    var noteInput by remember { mutableStateOf("") }

    var addressText by remember { mutableStateOf(LocationUtil.getFallbackPatrolArea(context)) }
    var gpsStatusText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var showGpsAlert by remember { mutableStateOf(false) }
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
            isSubmitting = true
            scope.launch {
                val finalAddress = addressText.ifBlank { LocationUtil.getFallbackPatrolArea(context) }
                val finalNote = "Status: $statusGenset | Oli: $oilCheck | Solar: $fuelInfo. ${noteInput}".trim()
                withContext(Dispatchers.IO) {
                    applyStandardTimemarkWithMinimap(
                        context = context,
                        imageFile = file,
                        badgeLabel = "Cek Genset",
                        officerName = officerName,
                        coordsText = coordsRaw,
                        addressText = finalAddress,
                        bottomBannerText = "Genset: $statusGenset • Solar: $fuelInfo"
                    )
                }
                val res = Uploader.sendGensetCheck(context, statusGenset, oilCheck, fuelInfo, officerName, finalNote, finalAddress, coordsRaw, file, null, null)
                isSubmitting = false
                if (res.first) {
                    MediaStorageUtil.recordVerificationLog(context, "Pengecekan Genset", file.name, "TERUPLOAD KE CLOUD", coordsRaw)
                    Toast.makeText(context, "Laporan Genset terkirim!", Toast.LENGTH_SHORT).show()
                } else {
                    MediaStorageUtil.saveToOfflineQueue(context, "Pengecekan Genset", officerName, finalNote, coordsRaw, file.absolutePath, finalAddress)
                    MediaStorageUtil.recordVerificationLog(context, "Pengecekan Genset", file.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                    Toast.makeText(context, "Disimpan ke Antrean Offline.", Toast.LENGTH_LONG).show()
                }
                onSuccess()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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

                Text("CADANGAN BBM SOLAR:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                fuelList.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { fuelInfo = item }) {
                        RadioButton(selected = fuelInfo == item, onClick = { fuelInfo = item })
                        Text(item, fontSize = 13.sp, color = ColorTextDark)
                    }
                }

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text("Catatan Tambahan Genset") },
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
                val dir = File(context.cacheDir, "checkpoints").apply { mkdirs() }
                val file = File(dir, "Genset_${System.currentTimeMillis()}.jpg")
                pendingCaptureFile = file
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                cameraLauncher.launch(uri)
            },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (isSubmitting) "Memproses & Mengunggah..." else "📷 Ambil Foto Fisik Genset & Kirim")
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

    var titleInput by remember { mutableStateOf("Video Patroli Keliling") }
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
                val finalTitle = titleInput.ifBlank { "Video Patroli Keliling" }
                val finalNote = "Lokasi: $finalAddress ($coordsRaw) | ${noteInput.ifBlank { "Situasi keliling aman" }}"
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
            Text(if (isSubmitting) "Mengunggah Video..." else "🎥 Mulai Rekam Video Patroli")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(24.dp)) {
            Text("Kembali")
        }
    }
}
