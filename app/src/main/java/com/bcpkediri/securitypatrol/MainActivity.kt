package com.bcpkediri.securitypatrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

enum class AppScreen {
    LOGIN, HOME, SHOW_QR, SCAN_QR, SELFIE, CHECKPOINT,
    FACILITY_REPORT, INBOUND_TRUCK, GENSET_CHECK, VIDEO_PATROL,
    GALLERY, OFFLINE_QUEUE, SETTINGS
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedUser = getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE)
        savedUser.edit().remove("officer_pass").apply()
        MediaStorageUtil.autoCleanWeeklyCache(this)

        setContent {
            MaterialTheme {
                SecPatrolApp()
            }
        }
    }

    override fun onDestroy() {
        LocationUtil.stopCompass()
        super.onDestroy()
    }
}

@Composable
fun SecPatrolApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }

    var officerId by remember { mutableStateOf(spUser.getString("officer_id", "") ?: "") }
    var officerName by remember { mutableStateOf(spUser.getString("officer_name", "") ?: "") }
    var officerShift by remember { mutableStateOf(spUser.getString("officer_shift", "Shift Pagi") ?: "Shift Pagi") }
    var officerBranch by remember { mutableStateOf(spUser.getString("officer_branch", "") ?: "") }
    var isHandoverDone by remember { mutableStateOf(spUser.getBoolean("is_handover_done", false)) }

    val isDevMode = officerId.trim().equals("ADMIN-DEV", ignoreCase = true) ||
                    spUser.getBoolean("is_dev_mode", false)

    var currentScreen by remember {
        mutableStateOf(if (officerId.isNotBlank() && officerName.isNotBlank()) AppScreen.HOME else AppScreen.LOGIN)
    }

    var activeHandover by remember { mutableStateOf<Handover?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun checkPermissionsGranted(): Boolean {
        val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val loc = LocationUtil.isLocationPermissionGranted(context)
        return cam && loc
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!checkPermissionsGranted()) showPermissionDialog = true
    }

    LaunchedEffect(Unit) {
        if (!checkPermissionsGranted()) permissionLauncher.launch(requiredPermissions)
        if (LocationUtil.checkNetworkAvailable(context)) {
            scope.launch { syncPendingOfflineQueue(context) }
        }
    }

    fun navigateProtected(target: AppScreen) {
        if (!checkPermissionsGranted()) {
            showPermissionDialog = true
            return
        }
        currentScreen = target
    }

    fun refreshUserSession() {
        officerId = spUser.getString("officer_id", "") ?: ""
        officerName = spUser.getString("officer_name", "") ?: ""
        officerShift = spUser.getString("officer_shift", "Shift Pagi") ?: "Shift Pagi"
        officerBranch = spUser.getString("officer_branch", "") ?: ""
        isHandoverDone = spUser.getBoolean("is_handover_done", false)
    }

    BackHandler(enabled = currentScreen != AppScreen.HOME && currentScreen != AppScreen.LOGIN) {
        currentScreen = if (officerId.isBlank()) AppScreen.LOGIN else AppScreen.HOME
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Izin Diperlukan", fontWeight = FontWeight.Bold) },
            text = { Text("Aplikasi membutuhkan izin Kamera dan Lokasi (GPS) untuk mengambil foto bukti patroli ber-watermark.") },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        permissionLauncher.launch(requiredPermissions)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)
                ) { Text("Berikan Izin") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("Tutup") }
            }
        )
    }

    val headerBranch = if (officerBranch.isNotBlank()) {
        if (officerBranch.startsWith("Cabang", ignoreCase = true)) officerBranch else "Cabang $officerBranch"
    } else "Cabang Kediri"

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorCyprus)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (currentScreen != AppScreen.HOME && currentScreen != AppScreen.LOGIN) {
                        IconButton(
                            onClick = {
                                refreshUserSession()
                                currentScreen = if (officerId.isBlank()) AppScreen.LOGIN else AppScreen.HOME
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("←", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column {
                        Text("$headerBranch - Security Patrol", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        val subTitle = if (officerName.isNotBlank()) {
                            "$officerName ($officerId) • v${BuildConfig.VERSION_NAME}"
                        } else {
                            "Sistem Patroli Terpadu • v${BuildConfig.VERSION_NAME}"
                        }
                        Text(subTitle, color = ColorSand, fontSize = 11.sp)
                    }
                }
                IconButton(onClick = { currentScreen = AppScreen.SETTINGS }) {
                    Text("⚙️", fontSize = 18.sp)
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF6F5F0))
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                AppScreen.LOGIN -> OneTimeLoginScreen(
                    onLoginSuccess = {
                        refreshUserSession()
                        currentScreen = AppScreen.HOME
                    }
                )
                AppScreen.HOME -> HomeScreen(
                    officerName = officerName,
                    officerShift = officerShift,
                    isHandoverDone = isHandoverDone,
                    isDevMode = isDevMode,
                    onShowQr = {
                        activeHandover = Handover(
                            id = "HO-${System.currentTimeMillis()}",
                            giver = officerName,
                            receiver = "",
                            oldShift = officerShift,
                            newShift = "",
                            area = headerBranch,
                            created = System.currentTimeMillis()
                        )
                        navigateProtected(AppScreen.SHOW_QR)
                    },
                    onScanQr = { navigateProtected(AppScreen.SCAN_QR) },
                    onDirectSelfieDev = {
                        activeHandover = Handover(
                            id = "DEV-${System.currentTimeMillis()}",
                            giver = officerName,
                            receiver = officerName,
                            oldShift = officerShift,
                            newShift = officerShift,
                            area = headerBranch,
                            created = System.currentTimeMillis()
                        )
                        navigateProtected(AppScreen.SELFIE)
                    },
                    onCheckpoint = { navigateProtected(AppScreen.CHECKPOINT) },
                    onFacilityReport = { navigateProtected(AppScreen.FACILITY_REPORT) },
                    onInboundTruck = { navigateProtected(AppScreen.INBOUND_TRUCK) },
                    onGensetCheck = { navigateProtected(AppScreen.GENSET_CHECK) },
                    onVideoPatrol = { navigateProtected(AppScreen.VIDEO_PATROL) },
                    onOpenGallery = { currentScreen = AppScreen.GALLERY },
                    onOpenOfflineQueue = { currentScreen = AppScreen.OFFLINE_QUEUE },
                    onResetHandover = {
                        spUser.edit().putBoolean("is_handover_done", false).commit()
                        isHandoverDone = false
                        Toast.makeText(context, "Kunci shift direset.", Toast.LENGTH_SHORT).show()
                    },
                    onLogoutUser = {
                        spUser.edit().clear().commit()
                        refreshUserSession()
                        currentScreen = AppScreen.LOGIN
                    }
                )
                AppScreen.SHOW_QR -> {
                    val ho = activeHandover ?: Handover(
                        id = "HO-${System.currentTimeMillis()}",
                        giver = officerName,
                        receiver = "",
                        oldShift = officerShift,
                        newShift = "",
                        area = headerBranch,
                        created = System.currentTimeMillis()
                    )
                    ShowQrScreen(
                        h = ho,
                        onDone = {
                            spUser.edit().putBoolean("is_handover_done", false).commit()
                            isHandoverDone = false
                            currentScreen = AppScreen.HOME
                        }
                    )
                }
                AppScreen.SCAN_QR -> ScanQrScreen(
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
                AppScreen.SELFIE -> {
                    val ho = activeHandover
                    if (ho != null) {
                        SelfieScreen(
                            handover = ho,
                            onSuccess = {
                                spUser.edit().putBoolean("is_handover_done", true).commit()
                                isHandoverDone = true
                                currentScreen = AppScreen.HOME
                            },
                            onBack = { currentScreen = AppScreen.HOME }
                        )
                    } else {
                        currentScreen = AppScreen.HOME
                    }
                }
                AppScreen.CHECKPOINT -> CheckpointScreen(
                    onSuccess = { currentScreen = AppScreen.HOME },
                    onBack = { currentScreen = AppScreen.HOME }
                )
                AppScreen.FACILITY_REPORT -> FacilityReportScreen(
                    onSuccess = { currentScreen = AppScreen.HOME },
                    onBack = { currentScreen = AppScreen.HOME }
                )
                AppScreen.INBOUND_TRUCK -> InboundTruckReportScreen(
                    onSuccess = { currentScreen = AppScreen.HOME },
                    onBack = { currentScreen = AppScreen.HOME }
                )
                AppScreen.GENSET_CHECK -> GensetCheckScreen(
                    onSuccess = { currentScreen = AppScreen.HOME },
                    onBack = { currentScreen = AppScreen.HOME }
                )
                AppScreen.VIDEO_PATROL -> VideoPatrolScreen(
                    onSuccess = { currentScreen = AppScreen.HOME },
                    onBack = { currentScreen = AppScreen.HOME }
                )
                AppScreen.GALLERY -> GalleryReadOnlyScreen(
                    onBack = { currentScreen = AppScreen.HOME }
                )
                AppScreen.OFFLINE_QUEUE -> OfflineQueueScreen(
                    onBack = { currentScreen = AppScreen.HOME }
                )
                AppScreen.SETTINGS -> SettingsScreen(
                    onBack = {
                        refreshUserSession()
                        currentScreen = if (officerId.isBlank()) AppScreen.LOGIN else AppScreen.HOME
                    }
                )
            }
        }
    }
}
