package com.bcpkediri.securitypatrol

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import org.json.JSONArray

@Composable
fun HomeScreen(onNavigate: (Page) -> Unit, onSwitchUser: () -> Unit) {
    val context = LocalContext.current
    val spUser = remember { context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE) }
    val spQueue = remember { context.getSharedPreferences("offline_reports_queue", Context.MODE_PRIVATE) }
    val isOnline = remember { LocationUtil.checkNetworkAvailable(context) }
    val hasCloudUrl = remember { Uploader.getEndpointUrl(context).isNotBlank() }
    val isDevMode = remember { spUser.getBoolean("is_dev_mode", false) }

    var officerName by remember { mutableStateOf(spUser.getString("officer_name", "-") ?: "-") }
    var officerShift by remember { mutableStateOf(spUser.getString("officer_shift", "-") ?: "-") }
    var isHandoverCompleted by remember { mutableStateOf(spUser.getBoolean("is_handover_done", false)) }

    var showEndWorkDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        officerName = spUser.getString("officer_name", "-") ?: "-"
        officerShift = spUser.getString("officer_shift", "-") ?: "-"
        isHandoverCompleted = spUser.getBoolean("is_handover_done", false)
    }

    val pendingCount = remember {
        val arrayStr = spQueue.getString("pending_list", "[]") ?: "[]"
        try { JSONArray(arrayStr).length() } catch (_: Exception) { 0 }
    }

    fun handleTaskClick(targetPage: Page) {
        val isDone = spUser.getBoolean("is_handover_done", false)
        if (!isDone) {
            Toast.makeText(
                context,
                "Silakan lakukan laporan serah terima shift terlebih dahulu sebelum memulai tugas!",
                Toast.LENGTH_LONG
            ).show()
        } else {
            onNavigate(targetPage)
        }
    }

    if (showEndWorkDialog) {
        AlertDialog(
            onDismissRequest = { showEndWorkDialog = false },
            title = { Text("Selesaikan Shift Kerja?", fontWeight = FontWeight.Bold, color = ColorCyprus) },
            text = { Text("Semua fitur tugas akan kembali terkunci. Petugas shift berikutnya wajib melakukan serah-terima untuk membuka tugas.") },
            confirmButton = {
                Button(
                    onClick = {
                        showEndWorkDialog = false
                        spUser.edit().putBoolean("is_handover_done", false).commit()
                        isHandoverCompleted = false
                        Toast.makeText(context, "Shift kerja selesai! Tugas berhasil dikunci kembali.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) {
                    Text("Ya, Kunci Tugas")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndWorkDialog = false }) {
                    Text("Batal", color = ColorTextMuted)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("PETUGAS AKTIF", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorTextMuted)
                        Text(officerName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ColorCyprus)
                        Text(officerShift, fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    }

                    TextButton(onClick = {
                        spUser.edit().putBoolean("is_handover_done", false).commit()
                        onSwitchUser()
                    }) {
                        Text("Ganti Akun", fontSize = 12.sp, color = ColorCyprus, fontWeight = FontWeight.Bold)
                    }
                }

                // KONTROL KHUSUS MASTER TESTER (BYPASS DEV)
                if (isDevMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val newState = !isHandoverCompleted
                                spUser.edit().putBoolean("is_handover_done", newState).commit()
                                isHandoverCompleted = newState
                                Toast.makeText(context, if (newState) "Tugas Terbuka (Bypass Dev)" else "Tugas Dikunci", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(if (isHandoverCompleted) "⚡ Kunci Tugas (Tes)" else "⚡ Buka Bebas (Dev)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showEndWorkDialog = true },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("🔒 Selesai Kerja", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (isHandoverCompleted) {
                    Button(
                        onClick = { showEndWorkDialog = true },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("🔒 Selesai Bekerja (Kunci Kembali Tugas)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (!isHandoverCompleted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🔒", fontSize = 20.sp)
                    Column {
                        Text("Tugas Masih Terkunci", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF856404))
                        Text("Selesaikan 'Serah-Terima Shift' di bawah untuk membuka fitur tugas.", fontSize = 11.sp, color = Color(0xFF856404))
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ColorSurfaceLight),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier.size(12.dp).clip(CircleShape)
                            .background(if (isOnline && hasCloudUrl) Color(0xFF2E7D32) else Color(0xFFD97706))
                    )
                    Column {
                        Text(
                            if (isOnline && hasCloudUrl) "Jaringan Online (Internet Siap)" else "Mode Offline / Lokal",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = ColorTextDark
                        )
                        Text(
                            if (pendingCount > 0) "$pendingCount laporan offline tersimpan" else "Semua laporan telah tersinkron",
                            fontSize = 11.sp,
                            color = ColorTextMuted
                        )
                    }
                }

                if (pendingCount > 0) {
                    Button(
                        onClick = { onNavigate(Page.OFFLINE_QUEUE) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Upload ($pendingCount)", fontSize = 12.sp)
                    }
                }
            }
        }

        Text("Serah-Terima Shift (Handover)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorTextDark)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MenuActionCard(
                title = "Buat QR Shift",
                subtitle = "Shift Lama (Penyerah)",
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Page.CREATE_HO) }
            )
            MenuActionCard(
                title = "Scan QR Shift",
                subtitle = "Shift Baru (Bisa Mandiri)",
                modifier = Modifier.weight(1f),
                onClick = { onNavigate(Page.SCAN_HO) }
            )
        }

        Text("Fitur Patroli & Dokumentasi Gudang", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ColorTextDark)

        SecondaryMenuCard(
            title = "Titik Patroli (Scan Barcode & Foto GPS)",
            desc = "Scan barcode rute pos gudang + hitung jarak lokasi & Timemark",
            isLocked = !isHandoverCompleted,
            onClick = { handleTaskClick(Page.CHECKPOINT) }
        )

        SecondaryMenuCard(
            title = "Buka/Tutup Gudang & Ruangan (Non-QR)",
            desc = "Foto segel pintu gudang, ruang sales, finance (bisa 1 - 3 foto)",
            isLocked = !isHandoverCompleted,
            onClick = { handleTaskClick(Page.FACILITY_REPORT) }
        )

        SecondaryMenuCard(
            title = "Logistik Truck Inbound (3 Tahap)",
            desc = "Catat antri bongkar, sedang bongkar, & selesai (ada antrean plat)",
            isLocked = !isHandoverCompleted,
            onClick = { handleTaskClick(Page.INBOUND_REPORT) }
        )

        SecondaryMenuCard(
            title = "Pengecekan Genset & Panel Listrik",
            desc = "Inspeksi level solar, voltase aki, oli, foto fisik, HSE & video",
            isLocked = !isHandoverCompleted,
            onClick = { handleTaskClick(Page.GENSET_REPORT) }
        )

        SecondaryMenuCard(
            title = "Rekam Video Patroli Fasilitas",
            desc = "Dokumentasi rekaman video bukti keliling inspeksi gudang",
            isLocked = !isHandoverCompleted,
            onClick = { handleTaskClick(Page.VIDEO_REPORT) }
        )

        SecondaryMenuCard(
            title = "Galeri & Arsip Patroli (Read-Only)",
            desc = "Lihat arsip foto serah-terima, pos, truck, genset & sync cloud",
            isLocked = false,
            onClick = { onNavigate(Page.GALLERY) }
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Dibuat oleh Gemini Vibe x Dot", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ColorTextMuted)
            Text("SecPatrol Warehouse Edition • v1.1.0-STABLE", fontSize = 10.sp, color = ColorTextMuted.copy(alpha = 0.8f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("www.github.com/Dodik-Dot/", fontSize = 9.sp, color = ColorTextMuted.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun MenuActionCard(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.height(130.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ColorCyprus),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text("❖", color = Color.White, fontSize = 16.sp)
            }
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun SecondaryMenuCard(title: String, desc: String, isLocked: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLocked) Color(0xFFE5E7EB) else ColorSurfaceLight
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isLocked) 0.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isLocked) {
                        Text("🔒", fontSize = 12.sp)
                    }
                    Text(
                        title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isLocked) ColorTextMuted else ColorTextDark
                    )
                }
                Text(desc, fontSize = 12.sp, color = ColorTextMuted)
            }
            Text(
                if (isLocked) "🔒" else "→",
                fontSize = 18.sp,
                color = if (isLocked) ColorTextMuted else ColorCyprus,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
