package com.bcpkediri.securitypatrol

import androidx.compose.ui.graphics.Color

// ==========================================
// 1. PALET WARNA TEMA RESMI SECPATROL
// ==========================================
val ColorCyprus = Color(0xFF003831)
val ColorSand = Color(0xFFE8DFD8)
val ColorSurfaceLight = Color(0xFFFFFFFF)
val ColorTextDark = Color(0xFF1F2937)
val ColorTextMuted = Color(0xFF6B7280)

// ==========================================
// 2. MODEL DATA SERAH-TERIMA SHIFT
// ==========================================
data class Handover(
    val id: String,
    val giver: String,
    val receiver: String,
    val oldShift: String,
    val newShift: String,
    val area: String,
    val created: Long
)

// ==========================================
// 3. DAFTAR HALAMAN NAVIGASI (PAGE ENUM)
// ==========================================
enum class Page {
    HOME,
    LOGIN,
    SETTINGS,
    CREATE_HO,
    SHOW_HO,
    SCAN_HO,
    SELFIE_HO,
    CHECKPOINT,
    SCAN_BARCODE,
    FACILITY_REPORT,
    INBOUND_REPORT,
    GENSET_REPORT,
    VIDEO_REPORT,
    OFFLINE_QUEUE,
    GALLERY
}
