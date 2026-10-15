/**
 * SecPatrol Backend - Edisi Bahasa Indonesia Terkunci (v1.1.4-STABLE)
 * Mendukung 7 Tab Bahasa Indonesia + Proteksi DATABASE_USER + Anti-Double Input
 */
const CONFIG = {
  SPREADSHEET_ID: "1pCLV1YRYcW061kz_AsBdH7ecOauJgmdeqTR2hWUerJk",
  PHOTO_FOLDER_ID: "1OFj7ByexcByx1-AwmkL02Wg99ltflmm0",
  MAX_REQUEST_CHARS: 25000000
};

const SCHEMAS_ID = {
  DATABASE_USER: [
    "Username / NIP", "Password", "Nama Lengkap", "Shift Default", "Status", "Tipe Perangkat HP"
  ],
  SERAH_TERIMA: [
    "Waktu", "ID", "Penyerah", "Penerima", "Shift Lama", "Shift Baru", "Area", "Wajah", "Foto Drive"
  ],
  TITIK_PATROLI: [
    "Waktu", "Pos/Titik", "Petugas", "Catatan", "Alamat Geotag", "Koordinat GPS", "Foto Drive"
  ],
  LOGISTIK_INBOUND: [
    "Waktu", "Nopol Truck", "Nama Sopir", "No HP Driver", "Divisi", "Jenis Armada",
    "Ekspedisi", "No Segel", "Status Muatan", "Status Antrean", "Petugas",
    "Catatan", "Koordinat GPS", "Foto Drive", "Foto 2", "Foto 3"
  ],
  PENGECEKAN_GENSET: [
    "Waktu", "Status Genset", "Kondisi Oli Mesin", "Solar & Voltase", "Petugas",
    "Catatan Temuan", "Alamat Geotag", "Koordinat GPS", "Foto Drive", "Foto Dokumen HSE", "Video Drive"
  ],
  DOKUMENTASI_GUDANG: [
    "Waktu", "Area / Fasilitas", "Petugas", "Catatan", "Alamat Geotag",
    "Koordinat GPS", "Foto Drive", "Foto 2", "Foto 3"
  ],
  VIDEO_PATROLI: [
    "Waktu", "File Video", "Petugas", "Catatan", "Alamat Geotag", "Koordinat GPS", "Link Video Drive"
  ]
};

// ==========================================
// 1. MENU ADMIN & AUTO-MASKING PASSWORD DI SHEET
// ==========================================
function onOpen() {
  try {
    SpreadsheetApp.getUi()
      .createMenu("🛡️ Admin SecPatrol")
      .addItem("1. Siapkan 7 Tab Bahasa Indonesia & Hapus Tab Lama", "setupSheetBahasaIndonesia")
      .addSeparator()
      .addItem("🔒 Kunci & Sembunyikan DATABASE_USER", "lockAndHideUserSheet")
      .addItem("👁️ Tampilkan DATABASE_USER (Khusus Editor)", "showUserSheetForEditor")
      .addToUi();
  } catch (err) {}
}

/**
 * Trigger otomatis saat Editor mengetik password baru di kolom B tab DATABASE_USER.
 * Langsung mengubah teks biasa menjadi "🔒 TERKUNCI:..." agar tidak bisa diintip.
 */
function onEdit(e) {
  try {
    if (!e || !e.range) return;
    var sheet = e.range.getSheet();
    if (sheet.getName() !== "DATABASE_USER") return;

    var row = e.range.getRow();
    var col = e.range.getColumn();

    // Jika Editor mengedit Kolom A (NIP) atau Kolom B (Password) pada baris >= 2
    if (row >= 2 && (col === 1 || col === 2)) {
      var nip = String(sheet.getRange(row, 1).getValue() || "").trim().toUpperCase();
      var rawPass = String(sheet.getRange(row, 2).getValue() || "").trim();
      if (nip && rawPass && rawPass.indexOf("🔒 TERKUNCI:") !== 0) {
        sheet.getRange(row, 1).setValue(nip);
        sheet.getRange(row, 2).setValue("🔒 TERKUNCI:" + hashUserPassword_(nip, rawPass));
      }
    }
  } catch (err) {}
}

/**
 * Jalankan fungsi ini 1x dari editor Apps Script (Pilih "setupSheetBahasaIndonesia" -> Klik Run)
 */
function setupSheetBahasaIndonesia() {
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);

  // 1. Buat ke-7 Sheet Bahasa Indonesia beserta Header-nya
  Object.keys(SCHEMAS_ID).forEach(function(sheetName) {
    ensureIndonesianSheet_(ss, sheetName, SCHEMAS_ID[sheetName]);
  });

  // 2. Isi data awal DATABASE_USER jika masih kosong
  var userSheet = ss.getSheetByName("DATABASE_USER");
  if (userSheet && userSheet.getLastRow() < 2) {
    var defaultUsers = [
      ["BCP-EKO", "🔒 TERKUNCI:" + hashUserPassword_("BCP-EKO", "eko123"), "EKO SISWOYO", "Shift Malam", "AKTIF", "vivo V2109 (Android 13)"],
      ["BCP-ANDRE", "🔒 TERKUNCI:" + hashUserPassword_("BCP-ANDRE", "andre123"), "ANDRE DWI CAHYONO", "Shift Pagi", "AKTIF", "Xiaomi 22021211RG (Android 14)"],
      ["BCP-ALEX", "🔒 TERKUNCI:" + hashUserPassword_("BCP-ALEX", "alex123"), "ALEX PERDANA", "Shift Pagi", "AKTIF", "OPPO CPH1933 (Android 11)"],
      ["BCP-TEST", "🔒 TERKUNCI:" + hashUserPassword_("BCP-TEST", "Test123"), "TESTAROSA", "Shift Malam", "AKTIF", "-"],
      ["ADMIN-DEV", "🔒 TERKUNCI:" + hashUserPassword_("ADMIN-DEV", "dev123"), "Admin Uji", "Shift Pagi", "AKTIF", "Developer Device"]
    ];
    userSheet.getRange(2, 1, defaultUsers.length, 6).setValues(defaultUsers);
  }

  // 3. Hapus tab bahasa Inggris lama jika masih ada
  var oldEnglishTabs = ["TRUCK_VISITS", "STATUS_EVENTS", "DOCKS", "MAP_NODES", "USERS", "Sheet1"];
  oldEnglishTabs.forEach(function(oldName) {
    var oldSheet = ss.getSheetByName(oldName);
    if (oldSheet && ss.getSheets().length > 1) {
      ss.deleteSheet(oldSheet);
    }
  });

  // 4. Kunci proteksi edit pada DATABASE_USER untuk Owner/Editor saja
  lockDatabaseUserProtectionOnly_(userSheet);

  return "Berhasil! 7 Tab Bahasa Indonesia telah siap dan tab lama telah dihapus.";
}

function lockDatabaseUserProtectionOnly_(sheet) {
  if (!sheet) return;
  try {
    var protections = sheet.getProtections(SpreadsheetApp.ProtectionType.SHEET);
    protections.forEach(function(p) { p.remove(); });

    var protection = sheet.protect().setDescription("Khusus Admin / Editor SecPatrol");
    var me = Session.getEffectiveUser();
    protection.addEditor(me);
    protection.removeEditors(protection.getEditors());
    if (protection.canDomainEdit()) {
      protection.setDomainEdit(false);
    }
  } catch (err) {}
}

function lockAndHideUserSheet() {
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ss.getSheetByName("DATABASE_USER");
  if (!sheet) return "Tab DATABASE_USER belum ada.";

  // Enkripsi semua password yang masih berupa teks biasa
  if (sheet.getLastRow() >= 2) {
    var range = sheet.getRange(2, 1, sheet.getLastRow() - 1, 2);
    var values = range.getValues();
    for (var i = 0; i < values.length; i++) {
      var nip = String(values[i][0] || "").trim().toUpperCase();
      var pass = String(values[i][1] || "").trim();
      if (nip && pass && pass.indexOf("🔒 TERKUNCI:") !== 0) {
        sheet.getRange(i + 2, 1).setValue(nip);
        sheet.getRange(i + 2, 2).setValue("🔒 TERKUNCI:" + hashUserPassword_(nip, pass));
      }
    }
  }

  lockDatabaseUserProtectionOnly_(sheet);

  if (ss.getSheets().length > 1) {
    sheet.hideSheet();
  }
  return "Tab DATABASE_USER berhasil dienkripsi, dikunci, dan disembunyikan!";
}

function showUserSheetForEditor() {
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ss.getSheetByName("DATABASE_USER");
  if (sheet) {
    sheet.showSheet();
    ss.setActiveSheet(sheet);
  }
}

// ==========================================
// 2. ENDPOINT GET & POST WEB APP
// ==========================================
function doGet(e) {
  var action = e && e.parameter ? String(e.parameter.action || "") : "";
  if (action === "ping" || !action) {
    return json_({
      status: "ok",
      message: "Server SecPatrol Bahasa Indonesia aktif dan terhubung."
    });
  }
  if (action === "checkHandoverStatus") {
    var hoId = String(e.parameter.handoverId || "").trim();
    return checkHandoverStatus_(hoId);
  }
  if (action === "getActiveInboundQueue") {
    return getActiveInboundQueue_();
  }
  return json_({status: "error", message: "Aksi GET tidak dikenali."});
}

function doPost(e) {
  try {
    var contents = (e && e.postData && e.postData.contents) || "{}";
    if (contents.length > CONFIG.MAX_REQUEST_CHARS) {
      return json_({status: "error", message: "Ukuran file terlalu besar."});
    }
    var body = JSON.parse(contents);
    var action = String(body.action || "");

    if (action === "verifyUser") return verifyUser_(body);

    // Anti-Double Input (Cegah kiriman ganda saat sinyal tidak stabil / klik Sync Cloud)
    var idempKey = cleanText_(body.idempotencyKey || body.fileName || "", 120);
    if (idempKey && isDuplicateRequest_(idempKey)) {
      return json_({
        status: "success",
        deduplicated: true,
        message: "Laporan sudah tercatat sebelumnya."
      });
    }

    switch (action) {
      case "handover":
        return saveHandover_(body, idempKey);
      case "checkpoint":
        return saveCheckpoint_(body, idempKey);
      case "facility_multi":
        return saveFacilityMulti_(body, idempKey);
      case "inbound":
        return saveInbound_(body, idempKey);
      case "genset":
        return saveGenset_(body, idempKey);
      case "video_patrol":
        return saveVideoPatrol_(body, idempKey);
      default:
        return json_({status: "error", message: "Aksi tidak dikenali: " + action});
    }
  } catch (err) {
    return json_({status: "error", message: "Gagal diproses server: " + err.message});
  }
}

// ==========================================
// 3. VERIFIKASI LOGIN PETUGAS (DATABASE_USER)
// ==========================================
function verifyUser_(body) {
  var username = String(body.username || "").trim().toUpperCase();
  var password = String(body.password || "").trim();
  var deviceInfo = cleanText_(body.deviceModel || body.device || "", 100);

  if (!username || !password) {
    return json_({status: "error", message: "Username/NIP dan Password wajib diisi!"});
  }

  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ensureIndonesianSheet_(ss, "DATABASE_USER", SCHEMAS_ID.DATABASE_USER);

  if (sheet.getLastRow() < 2) {
    setupSheetBahasaIndonesia();
  }

  var values = sheet.getRange(2, 1, sheet.getLastRow() - 1, 6).getValues();
  for (var i = 0; i < values.length; i++) {
    var row = values[i];
    var rowNip = String(row[0] || "").trim().toUpperCase();
    var storedPass = String(row[1] || "").trim();
    var rowName = String(row[2] || username).trim();
    var rowShift = String(row[3] || "Shift Pagi").trim();
    var rowStatus = String(row[4] || "AKTIF").trim().toUpperCase();

    if (rowNip === username) {
      if (rowStatus !== "AKTIF" && rowStatus !== "TRUE" && rowStatus !== "1") {
        return json_({status: "error", message: "Akun petugas sedang dinonaktifkan oleh Admin."});
      }

      var expectedLocked = "🔒 TERKUNCI:" + hashUserPassword_(rowNip, password);
      var isMatch = (storedPass === password) || (storedPass === expectedLocked);

      if (isMatch) {
        // Jika di sheet masih teks biasa, otomatis kunci menjadi hash saat petugas login
        if (storedPass === password) {
          sheet.getRange(i + 2, 2).setValue(expectedLocked);
        }
        if (deviceInfo) {
          sheet.getRange(i + 2, 6).setValue(safeSheetValue_(deviceInfo));
        }
        var token = Utilities.getUuid().replace(/-/g, "");
        return json_({
          status: "success",
          user: {
            id: rowNip,
            name: rowName,
            shift: rowShift
          },
          sessionToken: token
        });
      } else {
        return json_({status: "error", message: "Password yang Anda masukkan salah!"});
      }
    }
  }

  return json_({status: "error", message: "NIP / Username tidak ditemukan di DATABASE_USER."});
}

// ==========================================
// 4. SIMPAN LAPORAN KE 6 TAB OPERASIONAL
// ==========================================
function saveHandover_(body, idempKey) {
  var links = saveBase64ImagesToDriveLinks_(extractPhotosArray_(body), body.fileName || "Selfie_HO.jpg");
  var hoId = cleanText_(body.handoverId, 80) || ("HO-" + Utilities.formatDate(new Date(), "GMT+7", "yyyyMMdd-HHmmss"));

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureIndonesianSheet_(ss, "SERAH_TERIMA", SCHEMAS_ID.SERAH_TERIMA);
    sheet.appendRow([
      formatWaktuWib_(),
      safeSheetValue_(hoId),
      safeSheetValue_(cleanText_(body.giver, 100) || "Shift Sebelumnya"),
      safeSheetValue_(cleanText_(body.receiver, 100) || "Petugas Shift Baru"),
      safeSheetValue_(cleanText_(body.oldShift, 50) || "Shift Lama"),
      safeSheetValue_(cleanText_(body.newShift, 50) || "Shift Baru"),
      safeSheetValue_(cleanText_(body.area, 120) || "Gudang BCP Kediri"),
      Number(body.faceCount || 2),
      links[0] || "-"
    ]);

    if (hoId) CacheService.getScriptCache().put("HO_DONE_" + hoId, "completed", 3600);
    markRequestCompleted_(idempKey);
    return json_({status: "success", message: "Serah-terima tersimpan."});
  } finally {
    lock.releaseLock();
  }
}

function saveCheckpoint_(body, idempKey) {
  var links = saveBase64ImagesToDriveLinks_(extractPhotosArray_(body), body.fileName || "Pos_Patroli.jpg");
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureIndonesianSheet_(ss, "TITIK_PATROLI", SCHEMAS_ID.TITIK_PATROLI);
    sheet.appendRow([
      formatWaktuWib_(),
      safeSheetValue_(cleanText_(body.checkpointName, 100) || "Pos 1"),
      safeSheetValue_(cleanText_(body.officerName, 100) || "-"),
      safeSheetValue_(cleanText_(body.note, 1000) || "Aman / Terkendali"),
      safeSheetValue_(cleanText_(body.address, 250) || "Area Patroli Security"),
      safeSheetValue_(cleanText_(body.coordinates, 120) || "-"),
      links[0] || "-"
    ]);

    markRequestCompleted_(idempKey);
    return json_({status: "success", message: "Titik patroli tersimpan."});
  } finally {
    lock.releaseLock();
  }
}

function saveInbound_(body, idempKey) {
  var nopol = cleanText_(body.nopol, 32).toUpperCase();
  if (!nopol) return json_({status: "error", message: "Nomor Polisi Truck wajib diisi."});

  var links = saveBase64ImagesToDriveLinks_(extractPhotosArray_(body), body.fileName || ("Inbound_" + nopol + ".jpg"));
  var statusMuatan = cleanText_(body.status, 100) || "Truck Datang - Antri Bongkar";
  var isSelesai = statusMuatan.toLowerCase().indexOf("selesai") !== -1;
  var statusAntrean = isSelesai ? "SELESAI" : "DALAM ANTREAN";

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureIndonesianSheet_(ss, "LOGISTIK_INBOUND", SCHEMAS_ID.LOGISTIK_INBOUND);

    if (isSelesai && sheet.getLastRow() >= 2) {
      var dataRange = sheet.getRange(2, 2, sheet.getLastRow() - 1, 9);
      var dataVals = dataRange.getValues();
      var cleanTarget = nopol.replace(/\s+/g, "");
      for (var i = 0; i < dataVals.length; i++) {
        var existingNopol = String(dataVals[i][0] || "").toUpperCase().replace(/\s+/g, "");
        if (existingNopol === cleanTarget && dataVals[i][8] !== "SELESAI") {
          sheet.getRange(i + 2, 10).setValue("SELESAI");
        }
      }
    }

    sheet.appendRow([
      formatWaktuWib_(),
      safeSheetValue_(nopol),
      safeSheetValue_(cleanText_(body.driverName, 100) || "-"),
      safeSheetValue_(cleanText_(body.driverPhone, 40) || "-"),
      safeSheetValue_(cleanText_(body.division, 100) || "-"),
      safeSheetValue_(cleanText_(body.vehicleType, 80) || "-"),
      safeSheetValue_(cleanText_(body.expedition, 100) || "-"),
      safeSheetValue_(cleanText_(body.sealNo, 80) || "-"),
      safeSheetValue_(statusMuatan),
      statusAntrean,
      safeSheetValue_(cleanText_(body.officerName, 100) || "-"),
      safeSheetValue_(cleanText_(body.note, 1000) || statusMuatan),
      safeSheetValue_(cleanText_(body.coordinates, 120) || "-"),
      links[0] || "-",
      links[1] || "-",
      links[2] || "-"
    ]);

    markRequestCompleted_(idempKey);
    return json_({status: "success", message: "Data Logistik Inbound tersimpan."});
  } finally {
    lock.releaseLock();
  }
}

function saveGenset_(body, idempKey) {
  var folder = DriveApp.getFolderById(CONFIG.PHOTO_FOLDER_ID);
  var fisikLinks = saveBase64ImagesToDriveLinks_([body.imageBase64], body.fileName || "Genset_Fisik.jpg");
  var hseLinks = body.hseDocBase64 ? saveBase64ImagesToDriveLinks_([body.hseDocBase64], "Genset_HSE.jpg") : [];
  var videoLink = "-";
  if (body.videoBase64) {
    var vFile = folder.createFile(Utilities.newBlob(Utilities.base64Decode(String(body.videoBase64)), "video/mp4", "Genset_Video_" + Date.now() + ".mp4"));
    videoLink = driveViewUrl_(vFile.getId());
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureIndonesianSheet_(ss, "PENGECEKAN_GENSET", SCHEMAS_ID.PENGECEKAN_GENSET);
    sheet.appendRow([
      formatWaktuWib_(),
      safeSheetValue_(cleanText_(body.statusGenset, 100) || "-"),
      safeSheetValue_(cleanText_(body.oilCheck, 100) || "-"),
      safeSheetValue_(cleanText_(body.fuelInfo, 100) || "-"),
      safeSheetValue_(cleanText_(body.officerName, 100) || "-"),
      safeSheetValue_(cleanText_(body.note, 1000) || "Kondisi genset normal"),
      safeSheetValue_(cleanText_(body.address, 250) || "Area Patroli Security"),
      safeSheetValue_(cleanText_(body.coordinates, 120) || "-"),
      fisikLinks[0] || "-",
      hseLinks[0] || "-",
      videoLink
    ]);

    markRequestCompleted_(idempKey);
    return json_({status: "success", message: "Pengecekan genset tersimpan."});
  } finally {
    lock.releaseLock();
  }
}

function saveFacilityMulti_(body, idempKey) {
  var links = saveBase64ImagesToDriveLinks_(extractPhotosArray_(body), body.fileName || "Fasilitas.jpg");
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureIndonesianSheet_(ss, "DOKUMENTASI_GUDANG", SCHEMAS_ID.DOKUMENTASI_GUDANG);
    sheet.appendRow([
      formatWaktuWib_(),
      safeSheetValue_(cleanText_(body.reportName, 120) || "Area Parkir / Sekeliling"),
      safeSheetValue_(cleanText_(body.officerName, 100) || "-"),
      safeSheetValue_(cleanText_(body.note, 1000) || "Kondisi aman & rapi"),
      safeSheetValue_(cleanText_(body.address, 250) || "Area Patroli Security"),
      safeSheetValue_(cleanText_(body.coordinates, 120) || "-"),
      links[0] || "-",
      links[1] || "-",
      links[2] || "-"
    ]);

    markRequestCompleted_(idempKey);
    return json_({status: "success", message: "Dokumentasi gudang tersimpan."});
  } finally {
    lock.releaseLock();
  }
}

function saveVideoPatrol_(body, idempKey) {
  var folder = DriveApp.getFolderById(CONFIG.PHOTO_FOLDER_ID);
  var videoLink = "-";
  if (body.videoBase64) {
    var vName = cleanFilename_(body.fileName || ("Video_Patroli_" + Date.now() + ".mp4"));
    var vFile = folder.createFile(Utilities.newBlob(Utilities.base64Decode(String(body.videoBase64)), "video/mp4", vName));
    videoLink = driveViewUrl_(vFile.getId());
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureIndonesianSheet_(ss, "VIDEO_PATROLI", SCHEMAS_ID.VIDEO_PATROLI);
    sheet.appendRow([
      formatWaktuWib_(),
      safeSheetValue_(cleanText_(body.title, 120) || "Video Keliling Inspeksi Fasilitas"),
      safeSheetValue_(cleanText_(body.officerName, 100) || "-"),
      safeSheetValue_(cleanText_(body.note, 1000) || "Patroli keliling fasilitas gudang"),
      safeSheetValue_(cleanText_(body.address, 250) || "Area Patroli Security"),
      safeSheetValue_(cleanText_(body.coordinates, 120) || "-"),
      videoLink
    ]);

    markRequestCompleted_(idempKey);
    return json_({status: "success", message: "Video patroli tersimpan."});
  } finally {
    lock.releaseLock();
  }
}

// ==========================================
// 5. CEK ANTREAN INBOUND & STATUS HANDOVER
// ==========================================
function getActiveInboundQueue_() {
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ss.getSheetByName("LOGISTIK_INBOUND");
    if (!sheet || sheet.getLastRow() < 2) return json_({status: "success", data: []});

    var values = sheet.getRange(2, 1, sheet.getLastRow() - 1, 10).getValues();
    var latestByNopol = {};

    for (var i = 0; i < values.length; i++) {
      var r = values[i];
      var nopol = String(r[1] || "").trim().toUpperCase();
      if (!nopol || nopol === "TRUCK") continue;
      var statusMuatan = String(r[8] || "").trim();
      var statusAntrean = String(r[9] || "").trim().toUpperCase();

      latestByNopol[nopol] = {
        nopol: nopol,
        driverName: String(r[2] || ""),
        driverPhone: String(r[3] || ""),
        division: String(r[4] || ""),
        vehicleType: String(r[5] || ""),
        expedition: String(r[6] || ""),
        sealNo: String(r[7] || ""),
        status: statusMuatan,
        queueState: statusAntrean
      };
    }

    var activeList = [];
    Object.keys(latestByNopol).forEach(function(np) {
      var item = latestByNopol[np];
      if (item.queueState !== "SELESAI" && item.status.toLowerCase().indexOf("selesai") === -1) {
        activeList.push(item);
      }
    });

    return json_({status: "success", data: activeList});
  } catch (err) {
    return json_({status: "success", data: []});
  }
}

function checkHandoverStatus_(hoId) {
  if (!hoId) return json_({status: "pending"});
  if (CacheService.getScriptCache().get("HO_DONE_" + hoId) === "completed") {
    return json_({status: "completed"});
  }
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ss.getSheetByName("SERAH_TERIMA");
  if (sheet && sheet.getLastRow() >= 2) {
    var ids = sheet.getRange(2, 2, sheet.getLastRow() - 1, 1).getValues();
    for (var i = ids.length - 1; i >= 0; i--) {
      if (String(ids[i][0] || "").trim() === hoId) {
        return json_({status: "completed"});
      }
    }
  }
  return json_({status: "pending"});
}

// ==========================================
// 6. HELPER & UTILITAS
// ==========================================
function ensureIndonesianSheet_(ss, name, headers) {
  var sheet = ss.getSheetByName(name) || ss.insertSheet(name);
  if (sheet.getLastRow() === 0 || sheet.getLastColumn() === 0) {
    var headerRange = sheet.getRange(1, 1, 1, headers.length);
    headerRange.setValues([headers]);
    headerRange.setBackground("#004741");
    headerRange.setFontColor("#FFFFFF");
    headerRange.setFontWeight("bold");
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function hashUserPassword_(nip, plainPassword) {
  var raw = "SECPATROL_SALT_" + String(nip).trim().toUpperCase() + ":" + String(plainPassword).trim();
  var digest = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, raw, Utilities.Charset.UTF_8);
  return Utilities.base64EncodeWebSafe(digest).slice(0, 28);
}

function formatWaktuWib_() {
  return Utilities.formatDate(new Date(), "GMT+7", "yyyy-MM-dd HH:mm:ss");
}

function driveViewUrl_(fileId) {
  return "https://drive.google.com/file/d/" + fileId + "/view?usp=drivesdk";
}

function extractPhotosArray_(body) {
  var photos = Array.isArray(body.imagesBase64) ? body.imagesBase64 : (body.imageBase64 ? [body.imageBase64] : []);
  return photos.map(function(v) {
    return String(v || "").trim().replace(/^data:image\/[a-zA-Z0-9.+-]+;base64,/, "");
  }).filter(Boolean).slice(0, 3);
}

function saveBase64ImagesToDriveLinks_(photos, defaultName) {
  if (!photos || !photos.length) return [];
  var folder = DriveApp.getFolderById(CONFIG.PHOTO_FOLDER_ID);
  var baseName = cleanFilename_(defaultName);
  return photos.map(function(encoded, idx) {
    var suffix = photos.length > 1 ? "_" + (idx + 1) : "";
    var name = baseName.replace(/\.(jpg|jpeg|png|webp)$/i, "") + suffix + ".jpg";
    var file = folder.createFile(Utilities.newBlob(Utilities.base64Decode(encoded), "image/jpeg", name));
    return driveViewUrl_(file.getId());
  });
}

function isDuplicateRequest_(idempKey) {
  var cacheKey = "IDEMP_" + digestText_(idempKey).slice(0, 40);
  return CacheService.getScriptCache().get(cacheKey) === "DONE";
}

function markRequestCompleted_(idempKey) {
  if (!idempKey) return;
  var cacheKey = "IDEMP_" + digestText_(idempKey).slice(0, 40);
  CacheService.getScriptCache().put(cacheKey, "DONE", 21600);
}

function digestText_(text) {
  return Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(text), Utilities.Charset.UTF_8));
}

function safeSheetValue_(val) {
  var str = String(val == null ? "" : val);
  return /^[=+\-@]/.test(str) ? "'" + str : str;
}

function cleanText_(val, max) {
  return String(val == null ? "" : val).replace(/[\u0000-\u001F\u007F]/g, " ").trim().slice(0, max);
}

function cleanFilename_(val) {
  return String(val || "Foto.jpg").split(/[\\/]/).pop().replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 80) || "Foto.jpg";
}

function json_(val) {
  return ContentService.createTextOutput(JSON.stringify(val)).setMimeType(ContentService.MimeType.JSON);
}
