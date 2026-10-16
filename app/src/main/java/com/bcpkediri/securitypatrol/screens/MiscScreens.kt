package com.bcpkediri.securitypatrol

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val syncMutex = Mutex()

@Composable
fun OneTimeLoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }

    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var branchInput by remember { mutableStateOf(spUser.getString("officer_branch", "") ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Autentikasi Petugas Security", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)
                Text("Masukkan Username / NIP dan Password yang terdaftar di DATABASE_USER.", fontSize = 12.sp, color = ColorTextMuted)

                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = {
                        usernameInput = it.uppercase().trim()
                        errorMessage = ""
                    },
                    label = { Text("Username / NIP Petugas *") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = {
                        passwordInput = it
                        errorMessage = ""
                    },
                    label = { Text("Password *") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Text(
                            text = if (passwordVisible) "Sembunyikan" else "Lihat",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorCyprus,
                            modifier = Modifier.clickable { passwordVisible = !passwordVisible }.padding(end = 12.dp)
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = branchInput,
                    onValueChange = { branchInput = formatTitleCase(it) },
                    label = { Text("Cabang Pos (Opsional)") },
                    placeholder = { Text("Contoh: Kediri atau Madiun") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        if (usernameInput.isBlank() || passwordInput.isBlank()) {
                            errorMessage = "Username dan Password wajib diisi!"
                            return@Button
                        }
                        val formattedBranch = branchInput.trim()
                        isLoading = true
                        errorMessage = ""
                        scope.launch {
                            val result = Uploader.verifyUserRegistration(context, usernameInput, passwordInput)
                            isLoading = false
                            if (result.first && result.third != null) {
                                val officerName = result.third!!.first
                                val officerShift = result.third!!.second
                                val isAdminDev = usernameInput.equals("ADMIN-DEV", ignoreCase = true)

                                spUser.edit()
                                    .putString("officer_id", usernameInput)
                                    .putString("officer_name", officerName)
                                    .putString("officer_shift", officerShift)
                                    .putString("officer_branch", formattedBranch)
                                    .putBoolean("is_dev_mode", isAdminDev)
                                    .putBoolean("is_handover_done", false)
                                    .commit()

                                Toast.makeText(context, "Selamat bertugas, $officerName!", Toast.LENGTH_SHORT).show()
                                onLoginSuccess()
                            } else {
                                errorMessage = result.second
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "Memverifikasi Kredensial..." else "Masuk & Verifikasi Akun")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val spSettings = remember { context.getSharedPreferences("secpatrol_settings", Context.MODE_PRIVATE) }

    var endpointInput by remember { mutableStateOf(spSettings.getString("endpoint_url", "") ?: "") }
    var branchInput by remember { mutableStateOf(spUser.getString("officer_branch", "") ?: "") }
    var selectedFontScale by remember { mutableStateOf(spSettings.getFloat("font_scale", 1.0f)) }
    var testResult by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val fontOptions = listOf(
        Pair("Kecil", 0.88f),
        Pair("Normal", 1.0f),
        Pair("Besar", 1.18f),
        Pair("Lansia / XL", 1.32f)
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Pengaturan Sistem", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        // 1. Kartu Pengaturan Ukuran Huruf (Font) untuk HP Resolusi Rendah / Petugas Senior
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("UKURAN HURUF / TAMPILAN TEKS:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                Text(
                    "Sesuaikan ukuran teks jika layar HP beresolusi kecil atau agar lebih jelas dibaca oleh petugas.",
                    fontSize = 11.sp,
                    color = ColorTextDark
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fontOptions.forEach { (label, scaleVal) ->
                        val isSelected = kotlin.math.abs(selectedFontScale - scaleVal) < 0.05f
                        Button(
                            onClick = {
                                selectedFontScale = scaleVal
                                spSettings.edit().putFloat("font_scale", scaleVal).commit()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) ColorCyprus else Color(0xFFE0DDD5),
                                contentColor = if (isSelected) Color.White else ColorTextDark
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }

        // 2. Kartu Cabang & Endpoint URL
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NAMA CABANG POS (HEADER APLIKASI):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                OutlinedTextField(
                    value = branchInput,
                    onValueChange = { branchInput = formatTitleCase(it) },
                    placeholder = { Text("Contoh: Kediri atau Madiun") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("URL GOOGLE APPS SCRIPT WEB APP (OPSIONAL):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                OutlinedTextField(
                    value = endpointInput,
                    onValueChange = {
                        endpointInput = it.trim()
                        testResult = ""
                    },
                    placeholder = { Text("Kosongkan = Gunakan Server Default Sistem") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                if (testResult.isNotBlank()) {
                    Text(
                        testResult,
                        fontSize = 12.sp,
                        color = if (testResult.startsWith("Berhasil")) Color(0xFF2E7D32) else Color.Red,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            isTesting = true
                            testResult = "Menguji respon endpoint..."
                            scope.launch {
                                val urlToTest = endpointInput.ifBlank { Uploader.getEndpointUrl(context) }
                                val res = Uploader.testEndpoint(urlToTest)
                                isTesting = false
                                testResult = res.second
                            }
                        },
                        enabled = !isTesting,
                        colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (isTesting) "Menguji..." else "Tes Koneksi") }

                    Button(
                        onClick = {
                            Uploader.setEndpointUrl(context, endpointInput.trim())
                            spUser.edit().putString("officer_branch", branchInput.trim()).commit()
                            spSettings.edit().putFloat("font_scale", selectedFontScale).commit()
                            Toast.makeText(context, "Pengaturan disimpan!", Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Simpan") }
                }
            }
        }

        // 3. Kartu Status Pembersih Cache Otomatis 7 Hari
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PEMBERSIH CACHE OTOMATIS (7 HARI):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                Text(
                    "✅ Aktif: Sistem otomatis menghapus file cache foto/video sementara yang sudah berumur lebih dari 7 hari tanpa mengganggu Antrean Offline.",
                    fontSize = 11.sp,
                    color = Color(0xFF2E7D32)
                )
                OutlinedButton(
                    onClick = {
                        context.getSharedPreferences("cache_cleaner_pref", Context.MODE_PRIVATE)
                            .edit().putLong("last_clean_timestamp", 0L).commit()
                        MediaStorageUtil.autoCleanWeeklyCache(context)
                        Toast.makeText(context, "Pembersihan cache dijalankan (Antrean Offline tetap aman)!", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🧹 Jalankan Pembersih Cache Sekarang", fontSize = 12.sp, color = ColorCyprus)
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
}

@Composable
fun OfflineQueueScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val spQueue = remember { context.getSharedPreferences("offline_reports_queue", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var isUploadingAll by remember { mutableStateOf(false) }

    fun loadPendingItems(): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        val raw = spQueue.getString("pending_list", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        } catch (_: Exception) {}
        return list
    }

    var pendingList by remember { mutableStateOf(loadPendingItems()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Antrean Offline (${pendingList.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
            if (pendingList.isNotEmpty()) {
                Button(
                    onClick = {
                        if (isUploadingAll) return@Button
                        isUploadingAll = true
                        scope.launch {
                            syncPendingOfflineQueue(context)
                            pendingList = loadPendingItems()
                            isUploadingAll = false
                            Toast.makeText(context, "Sinkronisasi selesai!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isUploadingAll,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    shape = RoundedCornerShape(8.dp)
                ) { Text(if (isUploadingAll) "Mengunggah..." else "Upload Semua") }
            }
        }

        if (pendingList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Semua laporan telah tersinkron ke Cloud!", color = ColorTextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pendingList) { item ->
                    val itemTitle = item.optString("title").ifBlank { item.optString("type", "Laporan") }
                    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(itemTitle, fontWeight = FontWeight.Bold, color = ColorCyprus, fontSize = 14.sp)
                            Text("Petugas: ${item.optString("officer", "-")} • ${item.optString("timestamp", "-")}", fontSize = 11.sp, color = ColorTextMuted)
                            Text(item.optString("note", "-"), fontSize = 12.sp, color = ColorTextDark)
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali ke Beranda") }
    }
}

@Composable
fun GalleryReadOnlyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }

    fun getLogs(): List<JSONObject> {
        val sp = context.getSharedPreferences("verification_logs", Context.MODE_PRIVATE)
        val raw = sp.getString("logs_array", "[]") ?: "[]"
        val list = mutableListOf<JSONObject>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        } catch (_: Exception) {}
        return list.reversed()
    }

    var logList by remember { mutableStateOf(getLogs()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Arsip Verifikasi Patroli", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
            Button(
                onClick = {
                    if (isSyncing) return@Button
                    isSyncing = true
                    scope.launch {
                        syncPendingOfflineQueue(context)
                        logList = getLogs()
                        isSyncing = false
                        Toast.makeText(context, "Arsip tersinkron!", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isSyncing,
                colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                shape = RoundedCornerShape(8.dp)
            ) { Text(if (isSyncing) "Syncing..." else "Sync Cloud") }
        }

        if (logList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Belum ada riwayat dokumentasi patroli.", color = ColorTextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logList) { item ->
                    val statusStr = item.optString("status", "-")
                    val isUploaded = statusStr.contains("CLOUD", ignoreCase = true)
                    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.optString("title", "Patroli"), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ColorCyprus)
                            Text("Waktu: ${item.optString("timestamp", "-")}", fontSize = 11.sp, color = ColorTextMuted)
                            Text("Status: $statusStr", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isUploaded) Color(0xFF2E7D32) else Color(0xFFD97706))
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali ke Beranda") }
    }
}

suspend fun syncPendingOfflineQueue(context: Context) = withContext(Dispatchers.IO) {
    if (!LocationUtil.checkNetworkAvailable(context)) return@withContext
    if (!syncMutex.tryLock()) return@withContext

    try {
        val spQueue = context.getSharedPreferences("offline_reports_queue", Context.MODE_PRIVATE)
        val raw = spQueue.getString("pending_list", "[]") ?: "[]"
        if (raw == "[]" || raw.isBlank()) return@withContext

        val arr = JSONArray(raw)
        val items = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) items.add(arr.getJSONObject(i))

        val remaining = mutableListOf<JSONObject>()
        val fallbackArea = LocationUtil.getFallbackPatrolArea(context)

        for (index in items.indices) {
            val o = items[index]
            val rawType = o.optString("title").ifBlank { o.optString("type") }.ifBlank { o.optString("checkpoint", "Pos 1") }
            val officer = o.optString("officer", "-")
            val note = o.optString("note", "-")
            val coords = o.optString("coords", "-")
            val filePath = o.optString("filePath", "")
            val file = File(filePath)

            if (!file.exists()) continue

            var address = o.optString("address", "")
            if (address.isBlank() || address == "-" || address.contains("Jalan Mauni", ignoreCase = true)) {
                address = try {
                    val parts = coords.replace("°S", "").replace("°E", "").split(",")
                    if (parts.size == 2) {
                        LocationUtil.resolveAddress(context, parts[0].trim().toDouble(), parts[1].trim().toDouble())
                    } else fallbackArea
                } catch (_: Exception) { fallbackArea }
            }

            val isHandover = rawType.contains("Serah", true) || note.contains("Serah-terima", true)
            val isTruck = rawType.contains("Truck", true) || note.contains("Bongkar", true)
            val isGenset = rawType.contains("Genset", true)
            val isVideo = file.name.endsWith(".mp4", true) || rawType.contains("Video", true)
            val isFacility = rawType.contains("Gudang", true) || rawType.contains("Parkir", true) ||
                             rawType.contains("Ruang", true) || rawType.contains("Pintu", true) ||
                             rawType.contains("Gembok", true) || rawType.contains("Fasilitas", true)

            val success = when {
                isHandover -> {
                    val ho = Handover(
                        id = "HO-${file.lastModified()}",
                        giver = officer,
                        receiver = officer,
                        oldShift = note.substringAfter("Serah-terima ").substringBefore(" ke ").ifBlank { "Shift Malam" },
                        newShift = note.substringAfter("ke ").ifBlank { "Shift Pagi" },
                        area = fallbackArea,
                        created = file.lastModified()
                    )
                    Uploader.send(context, ho, file, 2).first
                }
                isTruck -> {
                    val nopol = if (rawType.contains("Truck ")) rawType.substringAfter("Truck ").substringBefore(" [").trim() else "TRUCK"
                    val parsedDriver = note.substringBefore(" (").ifBlank { officer }
                    val parsedStatus = when {
                        rawType.contains("Selesai", true) -> "Truck Selesai Muat (Kosong & Bersih)"
                        rawType.contains("Sedang Bongkar", true) -> "Truck Sedang Bongkar Muat"
                        else -> "Truck Datang - Antri Bongkar"
                    }
                    Uploader.sendInbound(context, nopol, parsedDriver, "-", "-", "-", "-", "-", parsedStatus, officer, note, coords, listOf(file)).first
                }
                isGenset -> Uploader.sendGensetCheck(context, "Uji Pemanasan (Warming Up Rutin)", "Level Normal & Bening/Kuning", "Standby Normal", officer, note, address, coords, file, null, null).first
                isVideo -> Uploader.sendVideoPatrol(context, rawType, officer, note, coords, file).first
                isFacility -> Uploader.sendFacilityMulti(context, rawType, officer, note, address, coords, listOf(file)).first
                else -> Uploader.sendCheckpoint(context, rawType, officer, note, address, coords, file).first
            }

            if (!success) {
                remaining.add(o)
            } else {
                MediaStorageUtil.recordVerificationLog(context, rawType, file.name, "TERUPLOAD KE CLOUD (SYNC)", coords)
            }

            val currentPendingJson = JSONArray()
            remaining.forEach { currentPendingJson.put(it) }
            for (remIdx in (index + 1) until items.size) {
                currentPendingJson.put(items[remIdx])
            }
            spQueue.edit().putString("pending_list", currentPendingJson.toString()).commit()
        }
    } catch (_: Exception) {
    } finally {
        syncMutex.unlock()
    }
}
