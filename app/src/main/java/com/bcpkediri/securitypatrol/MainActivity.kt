package com.bcpkediri.securitypatrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

enum class AppScreen {
    HOME,
    LOGIN,
    SETTINGS,
    CREATE_HANDOVER,
    SHOW_QR,
    SCAN_QR,
    SELFIE,
    CHECKPOINT_PATROL,
    SCAN_BARCODE,
    FACILITY_REPORT,
    INBOUND_TRUCK,
    GENSET_REPORT,
    VIDEO_PATROL,
    OFFLINE_QUEUE,
    GALLERY
}

fun formatHeaderBranchTitle(branch: String): String {
    val b = branch.trim()
    if (b.isBlank()) return "Security Patrol"
    val cleanBranch = if (b.startsWith("Cabang", ignoreCase = true)) b else "Cabang $b"
    return "$cleanBranch - Security Patrol"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedUser = getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE)
        savedUser.edit().remove("officer_pass").apply()
        if ((savedUser.getString("officer_id", "") ?: "").equals("ADMIN-DEV", ignoreCase = true)) {
            savedUser.edit().clear().apply()
        }

        MediaStorageUtil.autoCleanWeeklyCache(this)

        setContent {
            SecPatrolApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecPatrolApp() {
    val context = LocalContext.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }

    var officerId by remember { mutableStateOf(spUser.getString("officer_id", "") ?: "") }
    var officerName by remember { mutableStateOf(spUser.getString("officer_name", "") ?: "") }
    var officerShift by remember { mutableStateOf(spUser.getString("officer_shift", "Shift Pagi") ?: "Shift Pagi") }
    var officerBranch by remember { mutableStateOf(spUser.getString("officer_branch", "") ?: "") }
    var isHandoverDone by remember { mutableStateOf(spUser.getBoolean("is_handover_done", false)) }

    val isDevMode = officerId.trim().equals("ADMIN-DEV", ignoreCase = true)

    fun refreshUserData() {
        officerId = spUser.getString("officer_id", "") ?: ""
        officerName = spUser.getString("officer_name", "") ?: ""
        officerShift = spUser.getString("officer_shift", "Shift Pagi") ?: "Shift Pagi"
        officerBranch = spUser.getString("officer_branch", "") ?: ""
        isHandoverDone = spUser.getBoolean("is_handover_done", false)
    }

    var currentScreen by remember {
        mutableStateOf(if (officerId.isBlank()) AppScreen.LOGIN else AppScreen.HOME)
    }

    var activeHandover by remember { mutableStateOf<Handover?>(null) }
    var checkpointBarcodeScanned by remember { mutableStateOf("") }
    var isOnline by remember { mutableStateOf(LocationUtil.checkNetworkAvailable(context)) }

    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    fun checkPermissionsGranted(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    var hasAllPermissions by remember { mutableStateOf(checkPermissionsGranted()) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showGpsDisabledDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        hasAllPermissions = allGranted
        showPermissionDialog = !allGranted
    }

    LaunchedEffect(Unit) {
        if (!checkPermissionsGranted()) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LaunchedEffect(currentScreen) {
        refreshUserData()
        isOnline = LocationUtil.checkNetworkAvailable(context)
        hasAllPermissions = checkPermissionsGranted()
        if (!LocationUtil.isGpsEnabled(context)) {
            showGpsDisabledDialog = true
        }
    }

    BackHandler(enabled = currentScreen != AppScreen.HOME && currentScreen != AppScreen.LOGIN) {
        currentScreen = AppScreen.HOME
    }

    val headerTitle = formatHeaderBranchTitle(officerBranch)

    if (showGpsDisabledDialog) {
        AlertDialog(
            onDismissRequest = { showGpsDisabledDialog = false },
            title = {
                Text("Layanan Lokasi (GPS) Mati 🛰️", fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
            },
            text = {
                Text(
                    "Layanan Lokasi di HP Anda sedang nonaktif. Mohon aktifkan GPS agar koordinat pos dan geotag serah-terima dapat tercatat.",
                    fontSize = 13.sp,
                    color = ColorTextDark
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGpsDisabledDialog = false
                        LocationUtil.openLocationSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Aktifkan GPS")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showGpsDisabledDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Nanti Saja")
                }
            }
        )
    }

    if (showPermissionDialog && !hasAllPermissions) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Text("Izin Belum Diaktifkan 🛡️", fontWeight = FontWeight.Bold, color = ColorCyprus)
            },
            text = {
                Text(
                    "Aplikasi membutuhkan izin Kamera dan Lokasi untuk mengambil foto dan barcode scanner. Silakan aktifkan izin untuk melanjutkan.",
                    fontSize = 13.sp,
                    color = ColorTextDark
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        permissionLauncher.launch(requiredPermissions)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Aktifkan Izin")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Buka Pengaturan HP")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = headerTitle,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        val subText = if (currentScreen == AppScreen.LOGIN) {
                            "Registrasi Perangkat • v${BuildConfig.VERSION_NAME}"
                        } else {
                            "$officerName ($officerId) • v${BuildConfig.VERSION_NAME}"
                        }
                        Text(
                            text = subText,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                },
                navigationIcon = {
                    if (currentScreen != AppScreen.HOME && currentScreen != AppScreen.LOGIN) {
                        IconButton(onClick = { currentScreen = AppScreen.HOME }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { currentScreen = AppScreen.SETTINGS }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Pengaturan", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ColorCyprus
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFF8FAF9))
        ) {
            when (currentScreen) {
                AppScreen.LOGIN -> {
                    OneTimeLoginScreen(
                        onLoginSuccess = {
                            refreshUserData()
                            currentScreen = AppScreen.HOME
                        }
                    )
                }

                AppScreen.SETTINGS -> {
                    SettingsScreen(
                        onBack = {
                            refreshUserData()
                            currentScreen = if (officerId.isBlank()) AppScreen.LOGIN else AppScreen.HOME
                        }
                    )
                }

                AppScreen.HOME -> {
                    HomeScreen(
                        officerName = officerName,
                        officerShift = officerShift,
                        isHandoverDone = isHandoverDone,
                        isDevMode = isDevMode,
                        isOnline = isOnline,
                        onSwitchAccount = {
                            // Logout tuntas: Hapus sesi user dari memori
                            spUser.edit().clear().commit()
                            refreshUserData()
                            currentScreen = AppScreen.LOGIN
                        },
                        onToggleLock = {
                            val newStatus = !isHandoverDone
                            spUser.edit().putBoolean("is_handover_done", newStatus).commit()
                            isHandoverDone = newStatus
                            Toast.makeText(
                                context,
                                if (newStatus) "⚡ Tugas Dibuka (Dinas Aktif)" else "⚡ Tugas Dikunci",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onFinishDuty = {
                            spUser.edit().putBoolean("is_handover_done", false).commit()
                            isHandoverDone = false
                            Toast.makeText(context, "Shift Selesai! Seluruh tugas telah dikunci kembali.", Toast.LENGTH_LONG).show()
                        },
                        onNavigate = { screen ->
                            if (!hasAllPermissions && screen != AppScreen.GALLERY) {
                                showPermissionDialog = true
                            } else {
                                currentScreen = screen
                            }
                        }
                    )
                }

                AppScreen.CREATE_HANDOVER -> {
                    CreateHandoverScreen(
                        onComplete = { ho ->
                            activeHandover = ho
                            currentScreen = AppScreen.SHOW_QR
                        },
                        onBack = { currentScreen = AppScreen.HOME }
                    )
                }

                AppScreen.SHOW_QR -> {
                    activeHandover?.let { ho ->
                        ShowQrScreen(
                            h = ho,
                            onDone = {
                                refreshUserData()
                                currentScreen = AppScreen.HOME
                            }
                        )
                    } ?: run {
                        currentScreen = AppScreen.HOME
                    }
                }

                AppScreen.SCAN_QR -> {
                    ScanQrScreen(
                        onScanned = { ho ->
                            activeHandover = ho
                            currentScreen = AppScreen.SELFIE
                        },
                        onSoloHandover = { ho ->
                            activeHandover = ho
                            currentScreen = AppScreen.SELFIE
                        },
                        onBack = { currentScreen = AppScreen.HOME }
                    )
                }

                AppScreen.SELFIE -> {
                    activeHandover?.let { ho ->
                        SelfieScreen(
                            handover = ho,
                            onSuccess = {
                                refreshUserData()
                                currentScreen = AppScreen.HOME
                            },
                            onBack = { currentScreen = AppScreen.HOME }
                        )
                    } ?: run {
                        currentScreen = AppScreen.HOME
                    }
                }

                AppScreen.CHECKPOINT_PATROL -> {
                    CheckpointPatrolScreen(
                        initialCheckpointTag = checkpointBarcodeScanned,
                        onRequestScanBarcode = { currentScreen = AppScreen.SCAN_BARCODE },
                        onSuccess = {
                            checkpointBarcodeScanned = ""
                            currentScreen = AppScreen.HOME
                        },
                        onBack = {
                            checkpointBarcodeScanned = ""
                            currentScreen = AppScreen.HOME
                        }
                    )
                }

                AppScreen.SCAN_BARCODE -> {
                    CheckpointBarcodeScannerScreen(
                        onBarcodeScanned = { tag ->
                            checkpointBarcodeScanned = tag
                            currentScreen = AppScreen.CHECKPOINT_PATROL
                        },
                        onBack = { currentScreen = AppScreen.CHECKPOINT_PATROL }
                    )
                }

                AppScreen.FACILITY_REPORT -> {
                    FacilityReportScreen(
                        onSuccess = { currentScreen = AppScreen.HOME },
                        onBack = { currentScreen = AppScreen.HOME }
                    )
                }

                AppScreen.INBOUND_TRUCK -> {
                    InboundTruckReportScreen(
                        onSuccess = { currentScreen = AppScreen.HOME },
                        onBack = { currentScreen = AppScreen.HOME }
                    )
                }

                AppScreen.GENSET_REPORT -> {
                    GensetReportScreen(
                        onSuccess = { currentScreen = AppScreen.HOME },
                        onBack = { currentScreen = AppScreen.HOME }
                    )
                }

                AppScreen.VIDEO_PATROL -> {
                    VideoPatrolReportScreen(
                        onSuccess = { currentScreen = AppScreen.HOME },
                        onBack = { currentScreen = AppScreen.HOME }
                    )
                }

                AppScreen.OFFLINE_QUEUE -> {
                    OfflineQueueScreen(
                        onBack = { currentScreen = AppScreen.HOME }
                    )
                }

                AppScreen.GALLERY -> {
                    GalleryReadOnlyScreen(
                        onBack = { currentScreen = AppScreen.HOME }
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    officerName: String,
    officerShift: String,
    isHandoverDone: Boolean,
    isDevMode: Boolean,
    isOnline: Boolean,
    onSwitchAccount: () -> Unit,
    onToggleLock: () -> Unit,
    onFinishDuty: () -> Unit,
    onNavigate: (AppScreen) -> Unit
) {
    val canAccess = isHandoverDone
    var showLockedTaskDialog by remember { mutableStateOf(false) }

    if (showLockedTaskDialog) {
        AlertDialog(
            onDismissRequest = { showLockedTaskDialog = false },
            title = {
                Text("Tugas Belum Diaktifkan 🔒", fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
            },
            text = {
                Text(
                    "Fitur patroli ini belum diizinkan karena shift dinas Anda belum dibuka.\n\nSilakan lakukan Serah-Terima Shift terlebih dahulu untuk mengaktifkan tugas Anda.",
                    fontSize = 13.sp,
                    color = ColorTextDark
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLockedTaskDialog = false
                        onNavigate(AppScreen.SCAN_QR)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Serah-Terima Sekarang")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showLockedTaskDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Tutup")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("PETUGAS AKTIF", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                        Text(officerName.ifBlank { "Petugas Security" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
                        Text(officerShift, fontSize = 12.sp, color = ColorCyprus, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = onSwitchAccount) {
                        Text("Ganti Akun", fontSize = 12.sp, color = ColorCyprus, fontWeight = FontWeight.SemiBold)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isDevMode) {
                        Button(
                            onClick = onToggleLock,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHandoverDone) Color(0xFF6B21A8) else Color(0xFF2E7D32)
                            )
                        ) {
                            Text(
                                text = if (isHandoverDone) "⚡ Kunci Tugas (Tes)" else "⚡ Buka Tugas (Aktif)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Button(
                        onClick = onFinishDuty,
                        modifier = if (isDevMode) Modifier.weight(1f).height(44.dp) else Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) {
                        Text("🔒 Selesai Kerja", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isOnline) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF2E7D32) else Color(0xFFD97706))
                )
                Column {
                    Text(
                        text = if (isOnline) "Jaringan Online (Internet Siap)" else "Mode Offline (Internet Tidak Tersedia)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnline) Color(0xFF2E7D32) else Color(0xFFD97706)
                    )
                    Text(
                        text = if (isOnline) "Semua laporan terhubung langsung ke Cloud" else "Laporan akan disimpan lokal di antrean HP",
                        fontSize = 10.sp,
                        color = ColorTextMuted
                    )
                }
            }
        }

        Text("Serah-Terima Shift (Handover)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(105.dp)
                    .clickable { onNavigate(AppScreen.CREATE_HANDOVER) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ColorCyprus)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("❖", color = Color.White, fontSize = 16.sp)
                    Column {
                        Text("Buat QR Shift", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Shift Lama (Penyerah)", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                    }
                }
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(105.dp)
                    .clickable { onNavigate(AppScreen.SCAN_QR) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ColorCyprus)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("❖", color = Color.White, fontSize = 16.sp)
                    Column {
                        Text("Scan QR Shift", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Shift Baru (Bisa Mandiri)", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                    }
                }
            }
        }

        Text("Fitur Patroli & Dokumentasi Gudang", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ColorTextDark)

        val menuItems = listOf(
            Triple("Titik Patroli (Scan Barcode & Foto GPS)", "Scan barcode rute pos gudang + hitung jarak lokasi & Timemark", AppScreen.CHECKPOINT_PATROL),
            Triple("Buka/Tutup Gudang & Ruangan (Non-QR)", "Foto segel pintu gudang, ruang sales, finance (bisa 1 - 3 foto)", AppScreen.FACILITY_REPORT),
            Triple("Logistik Truck Inbound (3 Tahap)", "Catat antri bongkar, sedang bongkar, & selesai (ada antrean plat)", AppScreen.INBOUND_TRUCK),
            Triple("Pengecekan Genset & Panel Listrik", "Inspeksi level solar, voltase aki, oli, foto fisik, HSE & video", AppScreen.GENSET_REPORT),
            Triple("Rekam Video Patroli Fasilitas", "Dokumentasi rekaman video bukti keliling inspeksi gudang", AppScreen.VIDEO_PATROL),
            Triple("Galeri & Arsip Patroli (Read-Only)", "Lihat arsip foto serah-terima, pos, truck, genset & sync cloud", AppScreen.GALLERY)
        )

        menuItems.forEach { (title, subtitle, screen) ->
            val isItemAccessible = canAccess || screen == AppScreen.GALLERY

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isItemAccessible) {
                            onNavigate(screen)
                        } else {
                            showLockedTaskDialog = true
                        }
                    },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isItemAccessible) ColorSurfaceLight else Color(0xFFF3F4F6)
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isItemAccessible) ColorTextDark else ColorTextMuted
                        )
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = ColorTextMuted
                        )
                    }
                    Text(
                        text = if (isItemAccessible) "➔" else "🔒",
                        fontSize = 14.sp,
                        color = if (isItemAccessible) ColorTextMuted else Color(0xFFD97706),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text("Dibuat oleh Gemini Vibe x Dot", fontSize = 11.sp, color = ColorTextMuted)
            Text("SecPatrol Warehouse Edition • v${BuildConfig.VERSION_NAME}", fontSize = 10.sp, color = ColorTextMuted.copy(alpha = 0.8f))
            Text("[www.github.com/Dodik-Dot/](https://www.github.com/Dodik-Dot/)", fontSize = 10.sp, color = ColorTextMuted.copy(alpha = 0.6f))
        }
    }
}
