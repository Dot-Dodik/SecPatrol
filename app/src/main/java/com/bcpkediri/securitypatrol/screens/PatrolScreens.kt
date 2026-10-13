package com.bcpkediri.securitypatrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

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

// Helper memformat huruf kapital di awal setiap kata
fun formatTitleCase(input: String): String {
    if (input.isBlank()) return ""
    return input.split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}

// PENAMAAN FOTO BERSIH SESUAI KATEGORI FASILITAS YANG DIPILIH
fun getCleanFacilityFileName(selectedCategory: String, customCategoryName: String): String {
    return when {
        selectedCategory.contains("Gembok", ignoreCase = true) -> "Gembok_Segel_Pintu_Gudang"
        selectedCategory.contains("Buka Gudang", ignoreCase = true) -> "Buka_Gudang"
        selectedCategory.contains("Dalam Gudang", ignoreCase = true) -> "Area_Dalam_Gudang"
        selectedCategory.contains("Sales", ignoreCase = true) -> "Ruang_Sales"
        selectedCategory.contains("Finance", ignoreCase = true) -> "Ruang_Finance"
        selectedCategory.contains("Parkir", ignoreCase = true) -> "Area_Parkir"
        else -> {
            val name = customCategoryName.ifBlank { "Fasilitas" }
            name.trim().replace("\\s+".toRegex(), "_").replace("[^a-zA-Z0-9_]".toRegex(), "")
        }
    }
}

// ==========================================
// MODUL 1: BUKA/TUTUP GUDANG & RUANGAN
// ==========================================
@Composable
fun FacilityReportScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas Security") ?: "Petugas Security" }

    val categories = listOf(
        "Gembok / Segel Pintu Gudang (Tutup)",
        "Buka Gudang / Pelepasan Segel Pintu",
        "Area Dalam Gudang",
        "Ruang Sales",
        "Ruang Finance",
        "Area Parkir / Sekeliling",
        "Lainnya (Ketik Manual)"
    )
    var selectedCategory by remember { mutableStateOf(categories[0]) }
    var customCategoryName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    var locationText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var addressText by remember { mutableStateOf("Mendeteksi alamat geotag...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var isLocating by remember { mutableStateOf(false) }

    val photoFiles = remember { mutableStateListOf<File>() }
    val photoBitmaps = remember { mutableStateListOf<Bitmap>() }

    var tempFile by remember { mutableStateOf<File?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    fun refreshLocation() {
        isLocating = true
        LocationUtil.fetchPureGpsLocation(context) { locText, raw, loc ->
            locationText = locText
            coordsRaw = raw
            isLocating = false
            if (loc != null) {
                scope.launch(Dispatchers.IO) {
                    val addr = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                    withContext(Dispatchers.Main) { addressText = addr }
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshLocation() }

    val activeReportName = if (selectedCategory.contains("Lainnya")) {
        customCategoryName.ifBlank { "Area Khusus" }
    } else selectedCategory

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempFile != null && tempFile!!.exists()) {
            isLoading = true
            scope.launch(Dispatchers.Default) {
                try {
                    val marked = WatermarkUtil.applyTimemarkWatermark(
                        filePath = tempFile!!.absolutePath,
                        checkpoint = activeReportName,
                        address = addressText,
                        coordinates = coordsRaw
                    )
                    val fos = FileOutputStream(tempFile!!)
                    marked.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                    fos.flush()
                    fos.close()

                    // NAMA FILE DISESUAIKAN DENGAN KATEGORI BERSIH
                    val cleanPrefix = getCleanFacilityFileName(selectedCategory, customCategoryName)
                    val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
                    MediaStorageUtil.saveImageToPublicGallery(context, marked, "${cleanPrefix}_${photoFiles.size + 1}_$dateStr")

                    withContext(Dispatchers.Main) {
                        photoFiles.add(tempFile!!)
                        photoBitmaps.add(marked)
                        isLoading = false
                        Toast.makeText(context, "Foto ${photoFiles.size} berhasil diambil!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        Toast.makeText(context, "Gagal watermark: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun takeNewPhoto() {
        if (photoFiles.size >= 3) {
            Toast.makeText(context, "Maksimal 3 foto tercapai!", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(context.cacheDir, "checkpoints").apply { if (!exists()) mkdirs() }
        val cleanPrefix = getCleanFacilityFileName(selectedCategory, customCategoryName)
        val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
        val file = File(dir, "${cleanPrefix}_${photoFiles.size + 1}_$dateStr.jpg").apply { if (!exists()) createNewFile() }
        tempFile = file
        cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
    }

    if (previewIndex != null && previewIndex!! in photoBitmaps.indices) {
        val bmp = photoBitmaps[previewIndex!!]
        Dialog(onDismissRequest = { previewIndex = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.96f)) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                    Text("Tinjau Foto (${previewIndex!! + 1} dari ${photoBitmaps.size})", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 10.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                    Button(onClick = { previewIndex = null }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)) {
                        Text("Tutup Pratinjau", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Buka/Tutup Gudang & Ruangan", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PILIH AREA / FASILITAS:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                categories.forEach { cat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selectedCategory = cat }.padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = selectedCategory == cat, onClick = { selectedCategory = cat })
                        Text(cat, fontSize = 13.sp, color = ColorTextDark, fontWeight = if (selectedCategory == cat) FontWeight.Bold else FontWeight.Normal)
                    }
                }

                if (selectedCategory.contains("Lainnya")) {
                    OutlinedTextField(
                        value = customCategoryName,
                        onValueChange = { customCategoryName = formatTitleCase(it) },
                        label = { Text("Nama Area / Ruangan Manual *") },
                        placeholder = { Text("Contoh: Ruang Server") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                    )
                }

                OutlinedTextField(value = officerName, onValueChange = {}, label = { Text("Petugas Security") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), readOnly = true)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan / Temuan Kondisi") },
                    placeholder = { Text("Contoh: Segel aman tidak terputus, AC dan lampu padam") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )

                Text("LOKASI & KOORDINAT:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ColorSand.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(addressText, fontSize = 12.sp, color = ColorTextDark, fontWeight = FontWeight.Medium)
                        Text(locationText, fontSize = 11.sp, color = ColorCyprus, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { refreshLocation() }, enabled = !isLocating, modifier = Modifier.size(40.dp).clip(CircleShape).background(ColorCyprus)) {
                        Text(if (isLocating) "⏳" else "🎯", fontSize = 18.sp, color = Color.White)
                    }
                }
            }
        }

        if (photoBitmaps.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Foto Terkumpul (${photoBitmaps.size}/3 Foto)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)
                        TextButton(onClick = {
                            photoFiles.clear()
                            photoBitmaps.clear()
                        }) {
                            Text("Hapus Semua", fontSize = 11.sp, color = Color.Red)
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(photoBitmaps) { idx, bmp ->
                            Box(modifier = Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)).clickable { previewIndex = idx }) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Box(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.Red.copy(alpha = 0.85f)).clickable {
                                        photoFiles.removeAt(idx)
                                        photoBitmaps.removeAt(idx)
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Box(modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("Foto ${idx + 1}", color = Color.White, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (photoFiles.size < 3) {
            Button(
                onClick = { takeNewPhoto() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                enabled = !isLoading
            ) {
                Text(if (photoFiles.isEmpty()) "Ambil Foto Kondisi (Foto 1 dari 3)" else "Tambah Foto Lagi (${photoFiles.size + 1} dari 3)")
            }
        }

        if (photoFiles.isNotEmpty()) {
            Button(
                onClick = {
                    isLoading = true
                    scope.launch {
                        val isOnline = LocationUtil.checkNetworkAvailable(context)
                        val noteToSend = note.ifBlank { "Kondisi aman & rapi" }

                        if (isOnline && Uploader.getEndpointUrl(context).isNotBlank()) {
                            val res = Uploader.sendFacilityMulti(context, activeReportName, officerName, noteToSend, addressText, coordsRaw, photoFiles.toList())
                            if (res.first) {
                                MediaStorageUtil.recordVerificationLog(context, activeReportName, photoFiles[0].name, "TERUPLOAD KE CLOUD", coordsRaw)
                                Toast.makeText(context, "${photoFiles.size} foto fasilitas terunggah ke Cloud!", Toast.LENGTH_SHORT).show()
                            } else {
                                MediaStorageUtil.saveToOfflineQueue(context, activeReportName, officerName, noteToSend, coordsRaw, photoFiles[0].absolutePath, addressText)
                                MediaStorageUtil.recordVerificationLog(context, activeReportName, photoFiles[0].name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                                Toast.makeText(context, "Disimpan offline: ${res.second}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, activeReportName, officerName, noteToSend, coordsRaw, photoFiles[0].absolutePath, addressText)
                            MediaStorageUtil.recordVerificationLog(context, activeReportName, photoFiles[0].name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Disimpan offline di HP.", Toast.LENGTH_LONG).show()
                        }
                        isLoading = false
                        onSuccess()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                enabled = !isLoading
            ) {
                Text("Simpan & Kirim (${photoFiles.size} Foto)")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
}

// ==========================================
// MODUL 2: LOGISTIK TRUCK INBOUND
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboundTruckReportScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val spQueue = remember { context.getSharedPreferences("inbound_outstanding_queue", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas Security") ?: "Petugas Security" }

    fun loadOutstanding(): List<InboundQueueItem> {
        val list = mutableListOf<InboundQueueItem>()
        val raw = spQueue.getString("outstanding_list", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val np = o.optString("nopol").trim().uppercase()
                if (np.isNotBlank() && np != "TRUCK") {
                    list.add(
                        InboundQueueItem(
                            nopol = np,
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
            }
        } catch (_: Exception) {}
        return list
    }

    fun saveOutstanding(list: List<InboundQueueItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            if (item.nopol.isNotBlank() && item.nopol != "TRUCK") {
                val o = JSONObject().apply {
                    put("nopol", item.nopol)
                    put("driverName", item.driverName)
                    put("driverPhone", item.driverPhone)
                    put("division", item.division)
                    put("vehicleType", item.vehicleType)
                    put("expedition", item.expedition)
                    put("sealNo", item.sealNo)
                    put("status", item.status)
                }
                arr.put(o)
            }
        }
        spQueue.edit().putString("outstanding_list", arr.toString()).commit()
    }

    var outstandingList by remember { mutableStateOf(loadOutstanding()) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var isSyncingQueue by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    var nopol by remember { mutableStateOf("") }
    var driverName by remember { mutableStateOf("") }
    var driverPhone by remember { mutableStateOf("") }
    var division by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("") }
    var expedition by remember { mutableStateOf("") }
    var sealNo by remember { mutableStateOf("") }

    val statuses = listOf(
        "Truck Datang - Antri Bongkar",
        "Truck Sedang Bongkar Muat",
        "Truck Selesai Muat (Kosong & Bersih)"
    )
    var selectedStatus by remember { mutableStateOf(statuses[0]) }
    var note by remember { mutableStateOf("") }

    var locationText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var addressText by remember { mutableStateOf("Mendeteksi alamat geotag...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var isLocating by remember { mutableStateOf(false) }

    val photoFiles = remember { mutableStateListOf<File>() }
    val photoBitmaps = remember { mutableStateListOf<Bitmap>() }

    var tempCameraFile by remember { mutableStateOf<File?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    fun refreshLocation() {
        isLocating = true
        LocationUtil.fetchPureGpsLocation(context) { locText, raw, loc ->
            locationText = locText
            coordsRaw = raw
            isLocating = false
            if (loc != null) {
                scope.launch(Dispatchers.IO) {
                    val addr = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                    withContext(Dispatchers.Main) { addressText = addr }
                }
            }
        }
    }

    fun syncCloudQueue() {
        isSyncingQueue = true
        scope.launch {
            val cloudItems = Uploader.fetchActiveInboundQueue(context)
            val validCloud = cloudItems.filter { it.nopol.isNotBlank() && it.nopol != "TRUCK" }
            if (validCloud.isNotEmpty()) {
                saveOutstanding(validCloud)
                outstandingList = validCloud
                Toast.makeText(context, "${validCloud.size} antrean aktif disinkronkan!", Toast.LENGTH_SHORT).show()
            } else {
                saveOutstanding(emptyList())
                outstandingList = emptyList()
                Toast.makeText(context, "Tidak ada antrean truk aktif di server.", Toast.LENGTH_SHORT).show()
            }
            isSyncingQueue = false
        }
    }

    LaunchedEffect(Unit) {
        refreshLocation()
        outstandingList = loadOutstanding()
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempCameraFile != null && tempCameraFile!!.exists()) {
            isLoading = true
            scope.launch(Dispatchers.Default) {
                try {
                    val descTitle = "Truck $nopol [${selectedStatus.split(" - ").last()}]"
                    val marked = WatermarkUtil.applyTimemarkWatermark(
                        filePath = tempCameraFile!!.absolutePath,
                        checkpoint = descTitle,
                        address = addressText,
                        coordinates = coordsRaw
                    )
                    val fos = FileOutputStream(tempCameraFile!!)
                    marked.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                    fos.flush()
                    fos.close()

                    val safeNopol = nopol.ifBlank { "Truck" }.replace("[^a-zA-Z0-9]".toRegex(), "_")
                    val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
                    MediaStorageUtil.saveImageToPublicGallery(context, marked, "Truck_${safeNopol}_$dateStr")

                    withContext(Dispatchers.Main) {
                        photoFiles.add(tempCameraFile!!)
                        photoBitmaps.add(marked)
                        isLoading = false
                        Toast.makeText(context, "Foto ${photoFiles.size} berhasil diambil!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        Toast.makeText(context, "Gagal watermark: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val dir = File(context.cacheDir, "checkpoints").apply { if (!exists()) mkdirs() }
                val safeNopol = nopol.ifBlank { "Truck" }.replace("[^a-zA-Z0-9]".toRegex(), "_")
                val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
                val destFile = File(dir, "Truck_${safeNopol}_$dateStr.jpg").apply { if (!exists()) createNewFile() }

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val rawBmp = BitmapFactory.decodeFile(destFile.absolutePath, opts)
                if (rawBmp != null) {
                    photoFiles.add(destFile)
                    photoBitmaps.add(rawBmp)
                    Toast.makeText(context, "Foto galeri (${photoFiles.size}/3) dimuat!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal mengambil foto: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun updateLocalQueueNow(newStatus: String) {
        val current = loadOutstanding().toMutableList()
        current.removeAll { it.nopol == nopol }
        if (!newStatus.contains("Selesai") && nopol.isNotBlank() && nopol != "TRUCK") {
            current.add(
                InboundQueueItem(
                    nopol = nopol,
                    driverName = driverName,
                    driverPhone = driverPhone,
                    division = division,
                    vehicleType = vehicleType,
                    expedition = expedition,
                    sealNo = sealNo,
                    status = newStatus
                )
            )
        }
        saveOutstanding(current)
        outstandingList = current
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Bersihkan Antrean Truk?", fontWeight = FontWeight.Bold, color = Color(0xFFC62828)) },
            text = {
                Text(
                    "Tindakan ini akan mengosongkan antrean truk lokal yang menggantung. Anda dapat mengambil kembali antrean aktif dari server dengan menekan 'Sinkron'.",
                    fontSize = 13.sp,
                    color = ColorTextDark
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        spQueue.edit().putString("outstanding_list", "[]").commit()
                        outstandingList = emptyList()
                        nopol = ""
                        driverName = ""
                        driverPhone = ""
                        division = ""
                        vehicleType = ""
                        expedition = ""
                        sealNo = ""
                        note = ""
                        showClearConfirmDialog = false
                        Toast.makeText(context, "Antrean truk berhasil dikosongkan!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Hapus Semua")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearConfirmDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Batal")
                }
            }
        )
    }

    if (previewIndex != null && previewIndex!! in photoBitmaps.indices) {
        val bmp = photoBitmaps[previewIndex!!]
        Dialog(onDismissRequest = { previewIndex = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.96f)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tinjau Foto Truk (${previewIndex!! + 1} dari ${photoBitmaps.size})", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 10.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                    Button(onClick = { previewIndex = null }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)) {
                        Text("Tutup Pratinjau", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Dokumentasi Truck Inbound", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (outstandingList.isNotEmpty()) {
                    TextButton(
                        onClick = { showClearConfirmDialog = true },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("🗑️ Bersihkan", fontSize = 11.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(
                    onClick = { syncCloudQueue() },
                    enabled = !isSyncingQueue,
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Text(if (isSyncingQueue) "Sinkron..." else "🔄 Sinkron", fontSize = 11.sp, color = ColorCyprus, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = if (outstandingList.isEmpty()) {
                            "Belum ada antrean (Ketik Truk Baru)"
                        } else if (nopol.isNotBlank()) {
                            "Terpilih: $nopol (${selectedStatus.split(" - ").last()})"
                        } else {
                            "Pilih Truk Dalam Antrean (${outstandingList.size} Unit)"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("PILIH ANTREAN OUTSTANDING:", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF2E7D32),
                            unfocusedBorderColor = Color(0xFF81C784),
                            focusedContainerColor = Color(0xFFF1F8E9),
                            unfocusedContainerColor = Color(0xFFF1F8E9)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        if (outstandingList.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Tidak ada antrean truk saat ini", color = ColorTextMuted) },
                                onClick = { dropdownExpanded = false }
                            )
                        } else {
                            outstandingList.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.nopol, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ColorCyprus)
                                                Text("${item.driverName} (${item.expedition}) • ${item.status}", fontSize = 11.sp, color = ColorTextMuted)
                                            }
                                            IconButton(
                                                onClick = {
                                                    val current = loadOutstanding().toMutableList()
                                                    current.removeAll { it.nopol == item.nopol }
                                                    saveOutstanding(current)
                                                    outstandingList = current
                                                    if (nopol == item.nopol) {
                                                        nopol = ""
                                                        driverName = ""
                                                    }
                                                    Toast.makeText(context, "Plat ${item.nopol} dihapus", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Text("✕", color = Color(0xFFC62828), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    },
                                    onClick = {
                                        nopol = item.nopol
                                        driverName = item.driverName
                                        driverPhone = item.driverPhone
                                        division = item.division
                                        vehicleType = item.vehicleType
                                        expedition = item.expedition
                                        sealNo = item.sealNo
                                        selectedStatus = item.status
                                        dropdownExpanded = false
                                        Toast.makeText(context, "Data plat ${item.nopol} dimuat!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }

                if (nopol.isNotBlank()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = "✕ Reset / Input Truk Baru",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD97706),
                            modifier = Modifier.clickable {
                                nopol = ""
                                driverName = ""
                                driverPhone = ""
                                division = ""
                                vehicleType = ""
                                expedition = ""
                                sealNo = ""
                                note = ""
                                selectedStatus = statuses[0]
                                photoFiles.clear()
                                photoBitmaps.clear()
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = nopol,
                    onValueChange = { nopol = it.uppercase() },
                    label = { Text("Nomor Polisi Truck *") },
                    placeholder = { Text("Contoh: AG 8671 AN") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = driverName,
                        onValueChange = { driverName = formatTitleCase(it) },
                        label = { Text("Nama Sopir / Driver *") },
                        placeholder = { Text("Pak Mulyono") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            keyboardType = KeyboardType.Text
                        )
                    )

                    OutlinedTextField(
                        value = driverPhone,
                        onValueChange = { input -> driverPhone = input.filter { it.isDigit() } },
                        label = { Text("No HP / WA Driver *") },
                        placeholder = { Text("08123456789") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = division,
                        onValueChange = { division = formatTitleCase(it) },
                        label = { Text("Divisi") },
                        placeholder = { Text("P&G / Reckitt") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                    )

                    OutlinedTextField(
                        value = vehicleType,
                        onValueChange = { vehicleType = formatTitleCase(it) },
                        label = { Text("Jenis Kendaraan") },
                        placeholder = { Text("CDD / Box") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                    )
                }

                OutlinedTextField(
                    value = expedition,
                    onValueChange = { expedition = formatTitleCase(it) },
                    label = { Text("Ekspedisi / Asal Barang") },
                    placeholder = { Text("Contoh: CJ Logistics / Bahana") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                OutlinedTextField(
                    value = sealNo,
                    onValueChange = { sealNo = it.uppercase() },
                    label = { Text("Nomor Segel Plastik (Jika Ada)") },
                    placeholder = { Text("Contoh: BCP310539") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Text("STATUS TAHAPAN KONDISI TRUCK:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                statuses.forEach { st ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            selectedStatus = st
                            if (nopol.isNotBlank()) updateLocalQueueNow(st)
                        }.padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selectedStatus == st,
                            onClick = {
                                selectedStatus = st
                                if (nopol.isNotBlank()) updateLocalQueueNow(st)
                            }
                        )
                        Text(
                            text = st,
                            fontSize = 13.sp,
                            color = ColorTextDark,
                            fontWeight = if (selectedStatus == st) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                if (nopol.isNotBlank() && !selectedStatus.contains("Selesai")) {
                    OutlinedButton(
                        onClick = {
                            updateLocalQueueNow(selectedStatus)
                            Toast.makeText(context, "Status plat $nopol diperbarui di antrean!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("📌 Simpan / Update Antrean Truk Ini")
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan / Keterangan Muatan") },
                    placeholder = { Text("Contoh: Segel aman utuh, muatan lengkap") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )

                Text("LOKASI & KOORDINAT:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ColorSand.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(addressText, fontSize = 12.sp, color = ColorTextDark, fontWeight = FontWeight.Medium)
                        Text(locationText, fontSize = 11.sp, color = ColorCyprus, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { refreshLocation() }, enabled = !isLocating, modifier = Modifier.size(40.dp).clip(CircleShape).background(ColorCyprus)) {
                        Text(if (isLocating) "⏳" else "🎯", fontSize = 18.sp, color = Color.White)
                    }
                }
            }
        }

        if (photoBitmaps.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Foto Truk Terkumpul (${photoBitmaps.size}/3)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)
                        TextButton(
                            onClick = {
                                photoFiles.clear()
                                photoBitmaps.clear()
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Hapus Semua", fontSize = 11.sp, color = Color.Red)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (i in 0 until 3) {
                            if (i < photoBitmaps.size) {
                                val bmp = photoBitmaps[i]
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(95.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.DarkGray)
                                        .clickable { previewIndex = i }
                                ) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color.Red.copy(alpha = 0.85f))
                                            .clickable {
                                                photoFiles.removeAt(i)
                                                photoBitmaps.removeAt(i)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✕", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.65f))
                                            .padding(vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Foto ${i + 1}", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(95.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFF3F4F6)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+ Slot ${i + 1}", color = ColorTextMuted.copy(alpha = 0.6f), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (photoFiles.size < 3) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (nopol.isBlank()) {
                            Toast.makeText(context, "Mohon isi Nomor Polisi Truck terlebih dahulu", Toast.LENGTH_SHORT).show()
                        } else {
                            val dir = File(context.cacheDir, "checkpoints").apply { if (!exists()) mkdirs() }
                            val safeNopol = nopol.replace("[^a-zA-Z0-9]".toRegex(), "_")
                            val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
                            val file = File(dir, "Truck_${safeNopol}_${photoFiles.size + 1}_$dateStr.jpg").apply { if (!exists()) createNewFile() }
                            tempCameraFile = file
                            cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                    enabled = !isLoading
                ) {
                    Text("📷 Kamera (${photoFiles.size + 1}/3)", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        if (nopol.isBlank()) {
                            Toast.makeText(context, "Mohon isi Nomor Polisi Truck terlebih dahulu", Toast.LENGTH_SHORT).show()
                        } else {
                            galleryLauncher.launch("image/*")
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    enabled = !isLoading
                ) {
                    Text("🖼 Galeri (${photoFiles.size + 1}/3)", fontSize = 11.sp)
                }
            }
        }

        if (photoFiles.isNotEmpty()) {
            Button(
                onClick = {
                    isLoading = true
                    scope.launch {
                        val isOnline = LocationUtil.checkNetworkAvailable(context)
                        val noteToSend = note.ifBlank { selectedStatus }
                        val titleLog = "Truck $nopol [${vehicleType.ifBlank { "Armada" }}] ($selectedStatus)"

                        if (isOnline && Uploader.getEndpointUrl(context).isNotBlank()) {
                            val res = Uploader.sendInbound(
                                context,
                                nopol,
                                driverName.ifBlank { "-" },
                                driverPhone.ifBlank { "-" },
                                division.ifBlank { "-" },
                                vehicleType.ifBlank { "-" },
                                expedition.ifBlank { "-" },
                                sealNo.ifBlank { "-" },
                                selectedStatus,
                                officerName,
                                noteToSend,
                                coordsRaw,
                                photoFiles.toList()
                            )
                            if (res.first) {
                                MediaStorageUtil.recordVerificationLog(context, titleLog, photoFiles[0].name, "TERUPLOAD KE CLOUD", coordsRaw)
                                Toast.makeText(context, "${photoFiles.size} foto truk berhasil terkirim ke Cloud!", Toast.LENGTH_SHORT).show()
                            } else {
                                MediaStorageUtil.saveToOfflineQueue(context, titleLog, officerName, "$driverName ($driverPhone) - $noteToSend", coordsRaw, photoFiles[0].absolutePath, addressText)
                                MediaStorageUtil.recordVerificationLog(context, titleLog, photoFiles[0].name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                                Toast.makeText(context, "Disimpan offline: ${res.second}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, titleLog, officerName, "$driverName ($driverPhone) - $noteToSend", coordsRaw, photoFiles[0].absolutePath, addressText)
                            MediaStorageUtil.recordVerificationLog(context, titleLog, photoFiles[0].name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Disimpan offline di HP.", Toast.LENGTH_LONG).show()
                        }

                        updateLocalQueueNow(selectedStatus)
                        isLoading = false
                        onSuccess()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                enabled = !isLoading
            ) {
                Text("Simpan & Kirim Laporan (${photoFiles.size} Foto)")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
}

// ==========================================
// MODUL 3: PENGECEKAN GENSET & PANEL LISTRIK
// ==========================================
@Composable
fun GensetReportScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas Security") ?: "Petugas Security" }

    val statusOptions = listOf("Standby Otomatis (Aman)", "Beroperasi / Nyala (Genset Hidup)", "Uji Pemanasan (Warming Up Rutin)", "Kendala / Butuh Perbaikan Teknisi")
    var selectedGensetStatus by remember { mutableStateOf(statusOptions[0]) }

    val oilOptions = listOf("Level Normal & Bening/Kuning", "Level Rendah (Perlu Tambah)", "Oli Keruh/Hitam Pekat", "Ada Rembesan/Kebocoran Oli")
    var selectedOilCheck by remember { mutableStateOf(oilOptions[0]) }

    var fuelAndVolt by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    var locationText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var addressText by remember { mutableStateOf("Mendeteksi alamat geotag...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var isLocating by remember { mutableStateOf(false) }

    var originalFile by remember { mutableStateOf<File?>(null) }
    var hseDocFile by remember { mutableStateOf<File?>(null) }
    var videoFile by remember { mutableStateOf<File?>(null) }

    var watermarkedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hseDocBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun refreshLocation() {
        isLocating = true
        LocationUtil.fetchPureGpsLocation(context) { locText, raw, loc ->
            locationText = locText
            coordsRaw = raw
            isLocating = false
            if (loc != null) {
                scope.launch(Dispatchers.IO) {
                    val addr = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                    withContext(Dispatchers.Main) { addressText = addr }
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshLocation() }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && originalFile != null && originalFile!!.exists()) {
            isLoading = true
            scope.launch(Dispatchers.Default) {
                try {
                    val marked = WatermarkUtil.applyTimemarkWatermark(
                        filePath = originalFile!!.absolutePath,
                        checkpoint = "Genset: ${selectedGensetStatus.split(" ")[0]}",
                        address = addressText,
                        coordinates = coordsRaw
                    )
                    val fos = FileOutputStream(originalFile!!)
                    marked.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                    fos.flush()
                    fos.close()

                    val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
                    MediaStorageUtil.saveImageToPublicGallery(context, marked, "Genset_$dateStr")

                    withContext(Dispatchers.Main) {
                        watermarkedBitmap = marked
                        isLoading = false
                        Toast.makeText(context, "Foto fisik genset tersimpan!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        Toast.makeText(context, "Gagal watermark: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val hseDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && hseDocFile != null && hseDocFile!!.exists()) {
            isLoading = true
            scope.launch(Dispatchers.Default) {
                try {
                    val marked = WatermarkUtil.applyTimemarkWatermark(
                        filePath = hseDocFile!!.absolutePath,
                        checkpoint = "Dokumen HSE Genset",
                        address = addressText,
                        coordinates = coordsRaw
                    )
                    val fos = FileOutputStream(hseDocFile!!)
                    marked.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                    fos.flush()
                    fos.close()

                    val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
                    MediaStorageUtil.saveImageToPublicGallery(context, marked, "Genset_HSE_$dateStr")

                    withContext(Dispatchers.Main) {
                        hseDocBitmap = marked
                        isLoading = false
                        Toast.makeText(context, "Foto Dokumen HSE tersimpan!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        Toast.makeText(context, "Gagal watermark HSE: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success && videoFile != null && videoFile!!.exists()) {
            val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
            MediaStorageUtil.saveVideoToPublicGallery(context, videoFile!!, "Genset_Video_$dateStr")
            Toast.makeText(context, "Video genset tersimpan di HP!", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Pengecekan Genset & Panel", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("STATUS KONDISI GENSET:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                statusOptions.forEach { opt ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selectedGensetStatus = opt }.padding(vertical = 4.dp)) {
                        RadioButton(selected = selectedGensetStatus == opt, onClick = { selectedGensetStatus = opt })
                        Text(opt, fontSize = 13.sp, color = ColorTextDark, fontWeight = if (selectedGensetStatus == opt) FontWeight.Bold else FontWeight.Normal)
                    }
                }

                Text("KONDISI / LEVEL OLI MESIN:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                oilOptions.forEach { oil ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { selectedOilCheck = oil }.padding(vertical = 4.dp)) {
                        RadioButton(selected = selectedOilCheck == oil, onClick = { selectedOilCheck = oil })
                        Text(oil, fontSize = 12.sp, color = ColorTextDark, fontWeight = if (selectedOilCheck == oil) FontWeight.Bold else FontWeight.Normal)
                    }
                }

                OutlinedTextField(
                    value = fuelAndVolt,
                    onValueChange = { fuelAndVolt = it },
                    label = { Text("Indikator Solar & Voltase Aki") },
                    placeholder = { Text("Contoh: Solar 80%, Aki 24.5V Standby") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                OutlinedTextField(value = officerName, onValueChange = {}, label = { Text("Petugas Security") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), readOnly = true)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan / Temuan Panel Genset") },
                    placeholder = { Text("Contoh: Ruang genset terkunci, tidak ada kebocoran oli") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )

                Text("LOKASI & KOORDINAT:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ColorSand.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(addressText, fontSize = 12.sp, color = ColorTextDark, fontWeight = FontWeight.Medium)
                        Text(locationText, fontSize = 11.sp, color = ColorCyprus, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { refreshLocation() }, enabled = !isLocating, modifier = Modifier.size(40.dp).clip(CircleShape).background(ColorCyprus)) {
                        Text(if (isLocating) "⏳" else "🎯", fontSize = 18.sp, color = Color.White)
                    }
                }
            }
        }

        watermarkedBitmap?.let { bmp ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("1. Foto Fisik Genset Siap", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                }
            }
        }

        hseDocBitmap?.let { bmpHse ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("2. Foto Dokumen HSE Siap", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
                    Image(bitmap = bmpHse.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                }
            }
        }

        if (videoFile != null && videoFile!!.exists()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("📹", fontSize = 20.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("3. Video Genset Siap Disertakan", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ColorCyprus)
                        Text(videoFile!!.name, fontSize = 11.sp, color = ColorTextMuted)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val dir = File(context.cacheDir, "checkpoints").apply { if (!exists()) mkdirs() }
                    val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
                    val file = File(dir, "Genset_$dateStr.jpg").apply { if (!exists()) createNewFile() }
                    originalFile = file
                    cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                enabled = !isLoading
            ) {
                Text(if (watermarkedBitmap != null) "Foto Ulang Fisik" else "1. Foto Fisik GPS", fontSize = 12.sp)
            }

            Button(
                onClick = {
                    val dir = File(context.cacheDir, "videos").apply { if (!exists()) mkdirs() }
                    val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
                    val file = File(dir, "Genset_Video_$dateStr.mp4").apply { if (!exists()) createNewFile() }
                    videoFile = file
                    videoLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                enabled = !isLoading
            ) {
                Text(if (videoFile != null) "Rekam Ulang Vid" else "2. Video Mesin", fontSize = 12.sp)
            }
        }

        Button(
            onClick = {
                val dir = File(context.cacheDir, "checkpoints").apply { if (!exists()) mkdirs() }
                val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
                val file = File(dir, "Genset_HSE_$dateStr.jpg").apply { if (!exists()) createNewFile() }
                hseDocFile = file
                hseDocLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
            enabled = !isLoading
        ) {
            Text(if (hseDocBitmap != null) "Foto Ulang Dokumen HSE" else "📄 Foto Dokumen Log Sheet HSE (Bulanan)", fontSize = 12.sp)
        }

        if (watermarkedBitmap != null) {
            Button(
                onClick = {
                    isLoading = true
                    scope.launch {
                        val isOnline = LocationUtil.checkNetworkAvailable(context)
                        val noteToSend = note.ifBlank { "Kondisi genset normal" }
                        val fuelInfo = fuelAndVolt.ifBlank { "Standby Normal" }

                        if (isOnline && Uploader.getEndpointUrl(context).isNotBlank()) {
                            val res = Uploader.sendGensetCheck(
                                context,
                                selectedGensetStatus,
                                selectedOilCheck,
                                fuelInfo,
                                officerName,
                                noteToSend,
                                addressText,
                                coordsRaw,
                                originalFile!!,
                                hseDocFile,
                                videoFile
                            )
                            if (res.first) {
                                MediaStorageUtil.recordVerificationLog(context, "Inspeksi Genset", originalFile!!.name, "TERUPLOAD KE CLOUD", coordsRaw)
                                Toast.makeText(context, "Laporan genset & media lengkap terunggah!", Toast.LENGTH_SHORT).show()
                            } else {
                                MediaStorageUtil.saveToOfflineQueue(context, "Inspeksi Genset", officerName, "$selectedGensetStatus - Oli: $selectedOilCheck - $noteToSend", coordsRaw, originalFile!!.absolutePath, addressText)
                                MediaStorageUtil.recordVerificationLog(context, "Inspeksi Genset", originalFile!!.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                                Toast.makeText(context, "Disimpan offline: ${res.second}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, "Inspeksi Genset", officerName, "$selectedGensetStatus - Oli: $selectedOilCheck - $noteToSend", coordsRaw, originalFile!!.absolutePath, addressText)
                            MediaStorageUtil.recordVerificationLog(context, "Inspeksi Genset", originalFile!!.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Disimpan offline di HP.", Toast.LENGTH_LONG).show()
                        }
                        isLoading = false
                        onSuccess()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                enabled = !isLoading
            ) {
                Text(if (isLoading) "Mengunggah Data & Media..." else "Simpan & Kirim Semua Laporan Genset")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
}

// ==========================================
// MODUL 4: FORM TITIK PATROLI (DOKUMENTASI CHECKPOINT)
// ==========================================
@Composable
fun CheckpointPatrolScreen(
    initialCheckpointTag: String,
    onRequestScanBarcode: () -> Unit,
    onSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }

    var checkpointName by remember { mutableStateOf(cleanScanName(initialCheckpointTag)) }
    var targetCoord by remember { mutableStateOf(extractTargetCoord(initialCheckpointTag)) }
    val officerName = remember { spUser.getString("officer_name", "Petugas Security") ?: "Petugas Security" }
    var note by remember { mutableStateOf("") }

    var locationText by remember { mutableStateOf("Mencari koordinat satelit...") }
    var addressText by remember { mutableStateOf("Mendeteksi alamat geotag...") }
    var coordsRaw by remember { mutableStateOf("-") }
    var distanceInfo by remember { mutableStateOf("") }
    var isLocating by remember { mutableStateOf(false) }

    var originalFile by remember { mutableStateOf<File?>(null) }
    var watermarkedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(initialCheckpointTag) {
        if (initialCheckpointTag.isNotBlank()) {
            checkpointName = cleanScanName(initialCheckpointTag)
            targetCoord = extractTargetCoord(initialCheckpointTag)
        }
    }

    fun startHybridGpsTracking() {
        isLocating = true
        LocationUtil.fetchPureGpsLocation(context) { locText, raw, loc ->
            locationText = locText
            coordsRaw = raw
            isLocating = false

            if (loc != null) {
                if (targetCoord != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(loc.latitude, loc.longitude, targetCoord!!.first, targetCoord!!.second, results)
                    distanceInfo = "Jarak ke Titik Pos: ±${results[0].toInt()} meter"
                }

                scope.launch(Dispatchers.IO) {
                    val addr = LocationUtil.resolveAddress(context, loc.latitude, loc.longitude)
                    withContext(Dispatchers.Main) { addressText = addr }
                }
            }
        }
    }

    LaunchedEffect(Unit) { startHybridGpsTracking() }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && originalFile != null && originalFile!!.exists()) {
            isLoading = true
            scope.launch(Dispatchers.Default) {
                try {
                    val marked = WatermarkUtil.applyTimemarkWatermark(
                        filePath = originalFile!!.absolutePath,
                        checkpoint = checkpointName.ifBlank { "Titik Patroli" },
                        address = addressText,
                        coordinates = coordsRaw
                    )
                    val fos = FileOutputStream(originalFile!!)
                    marked.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                    fos.flush()
                    fos.close()

                    val safePos = checkpointName.replace("[^a-zA-Z0-9]".toRegex(), "_")
                    val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
                    MediaStorageUtil.saveImageToPublicGallery(context, marked, "${safePos}_$dateStr")

                    withContext(Dispatchers.Main) {
                        watermarkedBitmap = marked
                        isLoading = false
                        Toast.makeText(context, "Foto tersimpan di Galeri!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        Toast.makeText(context, "Gagal watermark: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun startCapture() {
        try {
            val dir = File(context.cacheDir, "checkpoints").apply { if (!exists()) mkdirs() }
            val safePos = checkpointName.ifBlank { "Pos" }.replace("[^a-zA-Z0-9]".toRegex(), "_")
            val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
            val file = File(dir, "${safePos}_$dateStr.jpg").apply { if (!exists()) createNewFile() }
            originalFile = file
            cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        } catch (e: Exception) {
            Toast.makeText(context, "Error kamera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Perekaman Titik Patroli", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = checkpointName, onValueChange = { checkpointName = it }, label = { Text("Nama Titik / Pos Barcode") }, placeholder = { Text("Scan Barcode Pos Dulu") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp))
                    Button(onClick = onRequestScanBarcode, modifier = Modifier.height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus), shape = RoundedCornerShape(10.dp)) {
                        Text("Scan Pos", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                if (distanceInfo.isNotBlank()) {
                    Text(distanceInfo, color = Color(0xFF2E7D32), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedTextField(value = officerName, onValueChange = {}, label = { Text("Petugas Security") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), readOnly = true)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan Temuan Pos") },
                    placeholder = { Text("Ketik temuan pos di sini (opsional)...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )

                Text("LOKASI & KOORDINAT:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ColorSand.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(addressText, fontSize = 12.sp, color = ColorTextDark, fontWeight = FontWeight.Medium)
                        Text(locationText, fontSize = 11.sp, color = ColorCyprus, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { startHybridGpsTracking() }, enabled = !isLocating, modifier = Modifier.size(40.dp).clip(CircleShape).background(ColorCyprus)) {
                        Text(if (isLocating) "⏳" else "🎯", fontSize = 18.sp, color = Color.White)
                    }
                }
            }
        }

        watermarkedBitmap?.let { bmp ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Foto Berhasil Dibuat", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                }
            }
        }

        val canTakePhoto = checkpointName.isNotBlank() && !isLoading
        Button(
            onClick = { startCapture() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
            enabled = canTakePhoto
        ) {
            Text(if (checkpointName.isBlank()) "Wajib Scan Barcode Pos Dulu" else if (watermarkedBitmap != null) "Ambil Ulang Foto Pos" else "Ambil Foto Pos (Watermark GPS)")
        }

        if (watermarkedBitmap != null) {
            Button(
                onClick = {
                    isLoading = true
                    scope.launch {
                        val isOnline = LocationUtil.checkNetworkAvailable(context)
                        val noteToSend = note.ifBlank { "Aman / Terkendali" }
                        if (isOnline && Uploader.getEndpointUrl(context).isNotBlank()) {
                            val res = Uploader.sendCheckpoint(context, checkpointName, officerName, noteToSend, addressText, coordsRaw, originalFile!!)
                            if (res.first) {
                                MediaStorageUtil.recordVerificationLog(context, checkpointName, originalFile!!.name, "TERUPLOAD KE CLOUD", coordsRaw)
                                Toast.makeText(context, "Laporan berhasil terunggah ke Cloud!", Toast.LENGTH_SHORT).show()
                            } else {
                                MediaStorageUtil.saveToOfflineQueue(context, checkpointName, officerName, noteToSend, coordsRaw, originalFile!!.absolutePath, addressText)
                                MediaStorageUtil.recordVerificationLog(context, checkpointName, originalFile!!.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                                Toast.makeText(context, "Disimpan offline: ${res.second}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, checkpointName, officerName, noteToSend, coordsRaw, originalFile!!.absolutePath, addressText)
                            MediaStorageUtil.recordVerificationLog(context, checkpointName, originalFile!!.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Disimpan offline di HP.", Toast.LENGTH_LONG).show()
                        }
                        isLoading = false
                        onSuccess()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                enabled = !isLoading
            ) {
                Text(if (isLoading) "Menyimpan Data..." else "Simpan & Kirim Laporan")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
}

// ==========================================
// MODUL 5: SCANNER BARCODE TITIK PATROLI (DENGAN SENTER & FRAME LASER)
// ==========================================
@Composable
fun CheckpointBarcodeScannerScreen(
    onBarcodeScanned: (String) -> Unit = {},
    onScanned: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val handleScan = if (onBarcodeScanned != onScanned && onBarcodeScanned != {}) onBarcodeScanned else onScanned
    CheckpointBarcodeScannerContent(onResult = handleScan, onBack = onBack)
}

@Composable
fun CheckpointBarcodeScannerContent(
    onResult: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var isTorchOn by remember { mutableStateOf(false) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Scan Barcode Pos Ronda",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = ColorTextDark
            )

            Button(
                onClick = { isTorchOn = !isTorchOn },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTorchOn) Color(0xFFFBC02D) else Color(0xFF374151)
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isTorchOn) "🔦 Senter ON" else "🔦 Senter OFF",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTorchOn) Color.Black else Color.White
                )
            }
        }

        if (hasCameraPermission) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                CameraScanView(isTorchOn = isTorchOn) { rawValue ->
                    val cleanTag = cleanScanName(rawValue)
                    if (cleanTag.isNotBlank()) {
                        val sp = context.getSharedPreferences("checkpoint_cooldown", Context.MODE_PRIVATE)
                        val lastScan = sp.getLong("last_scan_$cleanTag", 0L)
                        val now = System.currentTimeMillis()
                        val diffMinutes = (now - lastScan) / (1000 * 60)

                        if (diffMinutes < 30) {
                            Toast.makeText(
                                context,
                                "$cleanTag baru saja dipindai $diffMinutes mnt lalu. Harap tunggu jeda 30 mnt.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            sp.edit().putLong("last_scan_$cleanTag", now).apply()
                            onResult(rawValue)
                        }
                    }
                }

                ScannerViewfinderOverlay(
                    modifier = Modifier.fillMaxSize(),
                    instructionText = "Posisikan barcode pos tepat di dalam kotak"
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)
                ) {
                    Text("Izinkan Akses Kamera")
                }
            }
        }

        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Batal / Kembali")
        }
    }
}

// ==========================================
// MODUL 6: REKAM VIDEO PATROLI FASILITAS
// ==========================================
@Composable
fun VideoPatrolReportScreen(onSuccess: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val officerName = remember { spUser.getString("officer_name", "Petugas Security") ?: "Petugas Security" }

    var title by remember { mutableStateOf("Video Keliling Inspeksi Fasilitas") }
    var note by remember { mutableStateOf("") }
    var videoFile by remember { mutableStateOf<File?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var coordsRaw by remember { mutableStateOf("-") }

    LaunchedEffect(Unit) {
        LocationUtil.fetchPureGpsLocation(context) { _, raw, _ ->
            coordsRaw = raw
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        if (success && videoFile != null && videoFile!!.exists()) {
            val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm", Locale.getDefault()).format(Date())
            MediaStorageUtil.saveVideoToPublicGallery(context, videoFile!!, "Patroli_Video_$dateStr")
            Toast.makeText(context, "Video patroli tersimpan di Galeri!", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Rekam Video Patroli Fasilitas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Judul Inspeksi / Area") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                OutlinedTextField(value = officerName, onValueChange = {}, label = { Text("Petugas Security") }, readOnly = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan Inspeksi") },
                    placeholder = { Text("Kondisi pagar, pintu, dan area keliling...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )

                if (videoFile != null && videoFile!!.exists()) {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = ColorSand.copy(alpha = 0.5f))) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("📹", fontSize = 20.sp)
                            Column {
                                Text("Video Siap Terlampir", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ColorCyprus)
                                Text(videoFile!!.name, fontSize = 10.sp, color = ColorTextMuted)
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val dir = File(context.cacheDir, "videos").apply { if (!exists()) mkdirs() }
                val dateStr = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
                val file = File(dir, "Patroli_Video_$dateStr.mp4").apply { if (!exists()) createNewFile() }
                videoFile = file
                videoLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
            enabled = !isLoading
        ) {
            Text(if (videoFile != null) "Rekam Ulang Video" else "Mulai Rekam Video Patroli")
        }

        if (videoFile != null && videoFile!!.exists()) {
            Button(
                onClick = {
                    isLoading = true
                    scope.launch {
                        val isOnline = LocationUtil.checkNetworkAvailable(context)
                        val noteToSend = note.ifBlank { "Patroli keliling fasilitas gudang" }
                        if (isOnline && Uploader.getEndpointUrl(context).isNotBlank()) {
                            val res = Uploader.sendVideoPatrol(context, title, officerName, noteToSend, coordsRaw, videoFile!!)
                            if (res.first) {
                                MediaStorageUtil.recordVerificationLog(context, title, videoFile!!.name, "TERUPLOAD KE CLOUD", coordsRaw)
                                Toast.makeText(context, "Video patroli sukses terunggah ke Cloud!", Toast.LENGTH_SHORT).show()
                            } else {
                                MediaStorageUtil.saveToOfflineQueue(context, title, officerName, noteToSend, coordsRaw, videoFile!!.absolutePath)
                                MediaStorageUtil.recordVerificationLog(context, title, videoFile!!.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                                Toast.makeText(context, "Disimpan offline: ${res.second}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            MediaStorageUtil.saveToOfflineQueue(context, title, officerName, noteToSend, coordsRaw, videoFile!!.absolutePath)
                            MediaStorageUtil.recordVerificationLog(context, title, videoFile!!.name, "TERSIMPAN LOKAL (OFFLINE)", coordsRaw)
                            Toast.makeText(context, "Disimpan offline di HP.", Toast.LENGTH_LONG).show()
                        }
                        isLoading = false
                        onSuccess()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                enabled = !isLoading
            ) {
                Text(if (isLoading) "Mengunggah Video..." else "Simpan & Kirim Video Patroli")
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
}

fun cleanScanName(raw: String): String {
    if (raw.isBlank()) return ""
    val trimmed = raw.trim()
    if (trimmed.contains("|")) return trimmed.substringBefore("|").trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        val uri = android.net.Uri.parse(trimmed)
        val last = uri.lastPathSegment
        if (!last.isNullOrBlank()) "Pos $last" else trimmed
    } else trimmed
}

fun extractTargetCoord(raw: String): Pair<Double, Double>? {
    return try {
        if (raw.contains("|")) {
            val part = raw.substringAfter("|").trim()
            val latLon = part.split(",")
            if (latLon.size == 2) Pair(latLon[0].trim().toDouble(), latLon[1].trim().toDouble()) else null
        } else null
    } catch (_: Exception) { null }
}
