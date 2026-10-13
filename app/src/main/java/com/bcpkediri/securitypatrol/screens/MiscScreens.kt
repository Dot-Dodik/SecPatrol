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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Autentikasi Petugas Security",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorCyprus
                )
                Text(
                    "Silakan masukkan Username / NIP dan Password yang telah didaftarkan oleh Admin di database.",
                    fontSize = 12.sp,
                    color = ColorTextMuted
                )

                OutlinedTextField(
                    value = usernameInput,
                    onValueChange = {
                        usernameInput = it.uppercase().trim()
                        errorMessage = ""
                    },
                    label = { Text("Username / NIP Petugas *") },
                    placeholder = { Text("Masukkan NIP Anda") },
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
                    placeholder = { Text("Masukkan Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Text(
                            text = if (passwordVisible) "Sembunyikan" else "Lihat",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ColorCyprus,
                            modifier = Modifier
                                .clickable { passwordVisible = !passwordVisible }
                                .padding(end = 12.dp)
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // MENU TAMBAHAN CABANG DI BAWAH PASSWORD
                OutlinedTextField(
                    value = branchInput,
                    onValueChange = {
                        branchInput = formatTitleCase(it)
                    },
                    label = { Text("Cabang Pos (Opsional)") },
                    placeholder = { Text("Contoh: Kediri atau Cabang Kediri") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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

                                spUser.edit()
                                    .putString("officer_id", usernameInput)
                                    .putString("officer_name", officerName)
                                    .putString("officer_shift", officerShift)
                                    .putString("officer_branch", formattedBranch)
                                    .putBoolean("is_dev_mode", false)
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
    val spConfig = remember { context.getSharedPreferences("secpatrol_config", Context.MODE_PRIVATE) }

    // DIBUAT KOSONG SECARA DEFAULT (Hanya terisi jika pernah diketik manual)
    var endpointInput by remember { mutableStateOf(spConfig.getString("apps_script_url", "") ?: "") }
    var branchInput by remember { mutableStateOf(spUser.getString("officer_branch", "") ?: "") }
    var testResult by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Pengaturan Sistem", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                Text("NAMA CABANG POS (HEADER APLIKASI):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                OutlinedTextField(
                    value = branchInput,
                    onValueChange = { branchInput = formatTitleCase(it) },
                    placeholder = { Text("Contoh: Kediri atau Cabang Kediri (Kosongkan = Default)") },
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
                Text(
                    text = "Catatan: Jika dikosongkan, aplikasi otomatis menggunakan server default utama yang sudah terpasang.",
                    fontSize = 11.sp,
                    color = ColorTextMuted
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
                            testResult = "Menguji respon endpoint Google Apps Script..."
                            scope.launch {
                                // Jika input kosong, otomatis tes URL default sistem
                                val urlToTest = endpointInput.ifBlank { Uploader.getEndpointUrl(context) }
                                val res = Uploader.testEndpoint(urlToTest)
                                isTesting = false
                                testResult = if (endpointInput.isBlank()) {
                                    "${res.second} (Menggunakan Server Default)"
                                } else {
                                    res.second
                                }
                            }
                        },
                        enabled = !isTesting,
                        colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isTesting) "Menguji..." else "Tes Koneksi")
                    }

                    Button(
                        onClick = {
                            // Simpan custom URL jika diisi, atau kosongkan jika ingin pakai default sistem
                            Uploader.setEndpointUrl(context, endpointInput.trim())
                            spUser.edit().putString("officer_branch", branchInput.trim()).commit()
                            Toast.makeText(context, "Pengaturan Cabang & URL disimpan!", Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Simpan")
                    }
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
                        isUploadingAll = true
                        scope.launch {
                            syncPendingOfflineQueue(context)
                            pendingList = loadPendingItems()
                            isUploadingAll = false
                            Toast.makeText(context, "Sinkronisasi antrean selesai!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isUploadingAll,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isUploadingAll) "Mengunggah..." else "Upload Semua")
                }
            }
        }

        if (pendingList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Semua laporan telah tersinkron ke Cloud!", color = ColorTextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pendingList) { item ->
                    val itemTitle = item.optString("title")
                        .ifBlank { item.optString("type") }
                        .ifBlank { item.optString("checkpoint", "Laporan") }

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
            ) {
                Text(if (isSyncing) "Syncing..." else "Sync Cloud")
            }
        }

        if (logList.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Belum ada riwayat dokumentasi patroli.", color = ColorTextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logList) { item ->
                    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.optString("title", "Patroli"), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ColorCyprus)
                            Text("Waktu: ${item.optString("timestamp", "-")}", fontSize = 11.sp, color = ColorTextMuted)
                            Text("Status: ${item.optString("status", "-")}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (item.optString("status").contains("CLOUD")) Color(0xFF2E7D32) else Color(0xFFD97706))
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali ke Beranda") }
    }
}

// SINKRONISASI CERDAS: DETEKSI OTOMATIS NAMA POS & ALAMAT GEOTAG
suspend fun syncPendingOfflineQueue(context: Context) = withContext(Dispatchers.IO) {
    val spQueue = context.getSharedPreferences("offline_reports_queue", Context.MODE_PRIVATE)
    val raw = spQueue.getString("pending_list", "[]") ?: "[]"
    if (raw == "[]" || raw.isBlank()) return@withContext

    try {
        val arr = JSONArray(raw)
        val remaining = JSONArray()
        val uploader = Uploader

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val rawType = o.optString("title")
                .ifBlank { o.optString("type") }
                .ifBlank { o.optString("checkpoint") }
                .ifBlank { o.optString("name", "") }

            val officer = o.optString("officer", "-")
            val note = o.optString("note", "-")
            val coords = o.optString("coords", "-")
            val filePath = o.optString("filePath", "")
            val file = File(filePath)

            if (!file.exists()) continue

            val posFromFile = if (file.name.contains("Pos_", ignoreCase = true)) {
                val match = Regex("Pos_\\d+", RegexOption.IGNORE_CASE).find(file.name)?.value
                match?.replace("_", " ") ?: ""
            } else ""

            val posFromNote = if (note.contains("pos", ignoreCase = true)) {
                val match = Regex("pos\\s*\\d+", RegexOption.IGNORE_CASE).find(note)?.value
                match?.split(" ")?.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } } ?: ""
            } else ""

            val type = when {
                rawType.isNotBlank() && rawType != "Titik Patroli" && rawType != "Laporan" -> rawType
                posFromFile.isNotBlank() -> posFromFile
                posFromNote.isNotBlank() -> posFromNote
                else -> rawType.ifBlank { "Pos 1" }
            }

            var address = o.optString("address", "").ifBlank { o.optString("geotag", "") }
            if (address.isBlank() || address == "-") {
                try {
                    val cleanCoords = coords.replace("°S", "").replace("°E", "").replace("S", "").replace("E", "")
                    val parts = cleanCoords.split(",")
                    if (parts.size == 2) {
                        val lat = parts[0].trim().toDouble()
                        val lon = parts[1].trim().toDouble()
                        address = LocationUtil.resolveAddress(context, lat, lon)
                    }
                } catch (_: Exception) {
                    address = "Jalan Mauni, Kecamatan Pesantren, Kota Kediri"
                }
            }

            val isHandover = type.contains("Serah", ignoreCase = true) ||
                             note.contains("Serah-terima", ignoreCase = true) ||
                             note.contains("Shift", ignoreCase = true)

            val isTruck = type.contains("Truck", ignoreCase = true) ||
                          note.contains("Truck", ignoreCase = true) ||
                          note.contains("Bongkar", ignoreCase = true)

            val isGenset = type.contains("Genset", ignoreCase = true)

            val isFacility = type.contains("Gudang", ignoreCase = true) ||
                             type.contains("Parkir", ignoreCase = true) ||
                             type.contains("Ruang", ignoreCase = true) ||
                             type.contains("Pintu", ignoreCase = true) ||
                             type.contains("Gembok", ignoreCase = true) ||
                             type.contains("Area", ignoreCase = true) ||
                             type.contains("Fasilitas", ignoreCase = true)

            val success = when {
                isHandover -> {
                    val ho = Handover(
                        id = "HO-${System.currentTimeMillis()}",
                        giver = officer,
                        receiver = officer,
                        oldShift = note.substringAfter("Serah-terima ").substringBefore(" ke ").ifBlank { "Shift Lama" },
                        newShift = note.substringAfter("ke ").ifBlank { "Shift Baru" },
                        area = "Gudang BCP Kediri",
                        created = System.currentTimeMillis()
                    )
                    uploader.send(context, ho, file, 1).first
                }
                isTruck -> {
                    val nopol = if (type.contains("Truck ")) type.substringAfter("Truck ").substringBefore(" [") else "TRUCK"
                    uploader.sendInbound(context, nopol, "-", "-", "-", "-", "-", "-", "Tersinkron", officer, note, coords, listOf(file)).first
                }
                isGenset -> {
                    uploader.sendGensetCheck(context, "Tersinkron", "Normal", "Normal", officer, note, address.ifBlank { "-" }, coords, file, null, null).first
                }
                isFacility -> {
                    uploader.sendFacilityMulti(context, type.ifBlank { "Area Parkir / Sekeliling" }, officer, note, address.ifBlank { "Jalan Mauni, Kecamatan Pesantren, Kota Kediri" }, coords, listOf(file)).first
                }
                else -> {
                    uploader.sendCheckpoint(context, type, officer, note, address.ifBlank { "Jalan Mauni, Kecamatan Pesantren, Kota Kediri" }, coords, file).first
                }
            }

            if (!success) {
                remaining.put(o)
            } else {
                MediaStorageUtil.recordVerificationLog(context, type, file.name, "TERUPLOAD KE CLOUD (SYNC)", coords)
            }
        }

        spQueue.edit().putString("pending_list", remaining.toString()).commit()
    } catch (_: Exception) {}
}
