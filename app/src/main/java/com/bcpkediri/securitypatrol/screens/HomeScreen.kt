package com.bcpkediri.securitypatrol

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

@Composable
fun HomeScreen(
    officerName: String,
    officerShift: String,
    isHandoverDone: Boolean,
    isDevMode: Boolean,
    onShowQr: () -> Unit,
    onScanQr: () -> Unit,
    onDirectSelfieDev: () -> Unit,
    onCheckpoint: () -> Unit,
    onFacilityReport: () -> Unit,
    onInboundTruck: () -> Unit,
    onGensetCheck: () -> Unit,
    onVideoPatrol: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenOfflineQueue: () -> Unit = {},
    onResetHandover: () -> Unit,
    onLogoutUser: () -> Unit
) {
    val context = LocalContext.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    var devBypassUnlock by remember { mutableStateOf(spUser.getBoolean("is_handover_done", isHandoverDone)) }
    val patrolUnlocked = isHandoverDone || devBypassUnlock

    var showGpsOffDialog by remember { mutableStateOf(!LocationUtil.isGpsEnabled(context)) }

    val pendingOfflineCount = remember {
        try {
            val spQueue = context.getSharedPreferences("offline_reports_queue", Context.MODE_PRIVATE)
            JSONArray(spQueue.getString("pending_list", "[]") ?: "[]").length()
        } catch (_: Exception) { 0 }
    }

    if (showGpsOffDialog) {
        AlertDialog(
            onDismissRequest = { showGpsOffDialog = false },
            title = { Text("⚠️ GPS / Lokasi Mati!", fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C)) },
            text = {
                Text("Layanan Lokasi (GPS) di HP Anda sedang dimatikan. Aktifkan GPS sekarang agar koordinat satelit dan Minimap pada foto patroli dapat terekam dengan akurat.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGpsOffDialog = false
                        LocationUtil.openLocationSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus)
                ) { Text("Nyalakan GPS") }
            },
            dismissButton = {
                TextButton(onClick = { showGpsOffDialog = false }) { Text("Nanti") }
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
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("1. Serah-Terima Shift", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ColorCyprus)
                        Text("Petugas: $officerName ($officerShift)", fontSize = 12.sp, color = ColorTextMuted)
                    }
                    Surface(
                        color = if (patrolUnlocked) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (patrolUnlocked) "SHIFT AKTIF" else "TERKUNCI",
                            color = if (patrolUnlocked) Color(0xFF2E7D32) else Color(0xFFE65100),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = if (patrolUnlocked) {
                        "Akses menu patroli telah terbuka. Tampilkan QR kepada petugas baru saat pergantian shift."
                    } else {
                        "Wajib Scan QR dari petugas shift sebelumnya dan ambil foto Selfie berdua untuk membuka menu patroli."
                    },
                    fontSize = 12.sp,
                    color = ColorTextDark
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onShowQr,
                        colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Tampilkan QR", fontSize = 12.sp) }

                    Button(
                        onClick = onScanQr,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Scan QR Masuk", fontSize = 12.sp) }
                }

                if (isDevMode) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("KONTROL PENGUJI (DEV MODE):", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val nextState = !patrolUnlocked
                                devBypassUnlock = nextState
                                spUser.edit().putBoolean("is_handover_done", nextState).commit()
                                if (!nextState) onResetHandover()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (patrolUnlocked) Color(0xFFB71C1C) else Color(0xFF2E7D32)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (patrolUnlocked) "🔒 Kunci Menu (Dev)" else "🔓 Buka Kunci (Dev)", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = onDirectSelfieDev,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📸 Tes Selfie Dev", fontSize = 11.sp, color = ColorCyprus)
                        }
                    }
                } else if (patrolUnlocked) {
                    TextButton(
                        onClick = {
                            devBypassUnlock = false
                            onResetHandover()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Akhiri / Kunci Kembali Shift", fontSize = 11.sp, color = Color(0xFFB71C1C))
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. Operasional Patroli & Logistik", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ColorCyprus)

                if (!patrolUnlocked) {
                    Text(
                        "🔒 Menu terkunci. Selesaikan Serah-Terima Shift (atau klik 'Buka Kunci (Dev)' di atas) untuk membuka tombol patroli.",
                        fontSize = 12.sp,
                        color = Color(0xFFD97706)
                    )
                }

                Button(
                    onClick = onCheckpoint,
                    enabled = patrolUnlocked,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("📍 Patroli Titik Pos (Scan QR Pos 1 - 6)") }

                Button(
                    onClick = onFacilityReport,
                    enabled = patrolUnlocked,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorCyprus),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("🏢 Dokumentasi Gudang & Fasilitas (Multi-Foto)") }

                Button(
                    onClick = onInboundTruck,
                    enabled = patrolUnlocked,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("🚛 Dokumentasi Truck Inbound (3 Tahap)") }

                Button(
                    onClick = onGensetCheck,
                    enabled = patrolUnlocked,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("⚡ Pengecekan Genset, Oli & Solar") }

                Button(
                    onClick = onVideoPatrol,
                    enabled = patrolUnlocked,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("🎥 Rekam Video Patroli Keliling") }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3. Riwayat & Antrean Tersimpan", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorTextDark)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenGallery,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("📂 Arsip Verifikasi", fontSize = 12.sp, color = ColorCyprus) }

                    Button(
                        onClick = onOpenOfflineQueue,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (pendingOfflineCount > 0) Color(0xFFD97706) else ColorCyprus
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("☁️ Antrean ($pendingOfflineCount)", fontSize = 12.sp) }
                }

                OutlinedButton(
                    onClick = onLogoutUser,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Keluar / Ganti Akun Petugas", fontSize = 12.sp, color = Color(0xFFB71C1C)) }
            }
        }

        Text(
            text = "SecPatrol • github.com/Dot-Dodik/SecPatrol-Beta",
            fontSize = 11.sp,
            color = ColorTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}
