/**
 * SecPatrol Backend - Edisi Bahasa Indonesia Terkunci (v1.1.5-STABLE)
 * Fitur Lengkap:
 * 1. Otomatis membagi foto/video ke 7 Sub-Folder Google Drive & Direct Video Play
 * 2. Struktur 7 Tab Bahasa Indonesia + Proteksi & Sembunyikan DATABASE_USER
 * 3. Validasi ketat kolom Status wajib "AKTIF" (menolak akun kosong/nonaktif)
 * 4. Sinkronisasi otomatis Kolom F (Tipe Perangkat HP) saat login maupun kirim laporan
 * 5. Endpoint getActiveOfficers untuk Dropdown Nama Lengkap Security di HP
 * 6. Anti-Double Input (Idempotency Key & LockService)
 */

const CONFIG = {
  SPREADSHEET_ID: PropertiesService.getScriptProperties().getProperty("SPREADSHEET_ID") || "1pCLV1YRYcW061kz_AsBdH7ecOauJgmdeqTR2hWUerJk",
  PHOTO_FOLDER_ID: PropertiesService.getScriptProperties().getProperty("PHOTO_FOLDER_ID") || "1OFj7ByexcByx1-AwmkL02Wg99ltflmm0",
  MAX_REQUEST_CHARS: 25000000
};

const DRIVE_SUBFOLDERS = {
  HANDOVER: "Handover_Selfie",
  CHECKPOINT: "Patrol_Checkpoints",
  INBOUND: "Inbound_Truck",
  FACILITY: "Facility_Reports",
  GENSET_PHOTO: "Genset",
  GENSET_VIDEO: "Genset_Video",
  VIDEO_PATROL: "Video_Patroli"
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
// 1. MENU ADMIN SPREADSHEET & TRIGGER EDIT
// ==========================================
function onOpen() {
  try {
    SpreadsheetApp.getUi()
      .createMenu("🛡️ Admin SecPatrol")
      .addItem("1. Siapkan 7 Tab Sheet & 7 Sub-Folder Drive", "setupSheetBahasaIndonesia")
      .addItem("📁 Rapikan & Buat 7 Sub-Folder Foto/Video di Drive", "rapikanFolderDrive")
      .addSeparator()
      .addItem("🔒 Kunci & Sembunyikan DATABASE_USER", "lockAndHideUserSheet")
      .addItem("👁️ Tampilkan DATABASE_USER (Khusus Editor)", "showUserSheetForEditor")
      .addToUi();
  } catch (err) {
    console.error("onOpen error:", err);
  }
}

function onEdit(e) {
  try {
    if (!e || !e.range) return;
    var sheet = e.range.getSheet();
    if (sheet.getName() !== "DATABASE_USER") return;

    var row = e.range.getRow();
    var col = e.range.getColumn();
    if (row >= 2 && (col === 1 || col === 2)) {
      var nip = String(sheet.getRange(row, 1).getValue() || "").trim().toUpperCase();
      var rawPass = String(sheet.getRange(row, 2).getValue() || "").trim();
      if (nip && rawPass && rawPass.indexOf("🔒 TERKUNCI:") !== 0) {
        sheet.getRange(row, 1).setValue(nip);
        sheet.getRange(row, 2).setValue("🔒 TERKUNCI:" + hashUserPassword_(nip, rawPass));
      }
    }
    CacheService.getScriptCache().remove("ACTIVE_OFFICERS_CACHE");
  } catch (err) {
    console.error("onEdit error:", err);
  }
}

function rapikanFolderDrive() {
  var parentFolder = DriveApp.getFolderById(String(CONFIG.PHOTO_FOLDER_ID).trim());
  var folderMap = {};

  Object.keys(DRIVE_SUBFOLDERS).forEach(function(key) {
    var subName = DRIVE_SUBFOLDERS[key];
    var iter = parentFolder.getFoldersByName(subName);
    folderMap[subName] = iter.hasNext() ? iter.next() : parentFolder.createFolder(subName);
  });

  var files = parentFolder.getFiles();
  var movedCount = 0;
  while (files.hasNext()) {
    var file = files.next();
    var fname = String(file.getName() || "").toLowerCase();
    var mime = String(file.getMimeType() || "").toLowerCase();
    var targetSubName = null;

    if (fname.indexOf("selfie") !== -1 || fname.indexOf("ho_") !== -1 || fname.indexOf("handover") !== -1) {
      targetSubName = DRIVE_SUBFOLDERS.HANDOVER;
    } else if (fname.indexOf("genset") !== -1 && (fname.indexOf(".mp4") !== -1 || mime.indexOf("video") !== -1)) {
      targetSubName = DRIVE_SUBFOLDERS.GENSET_VIDEO;
    } else if (fname.indexOf("genset") !== -1) {
      targetSubName = DRIVE_SUBFOLDERS.GENSET_PHOTO;
    } else if (fname.indexOf(".mp4") !== -1 || mime.indexOf("video") !== -1 || fname.indexOf("video") !== -1) {
      targetSubName = DRIVE_SUBFOLDERS.VIDEO_PATROL;
    } else if (fname.indexOf("inbound") !== -1 || fname.indexOf("truck") !== -1) {
      targetSubName = DRIVE_SUBFOLDERS.INBOUND;
    } else if (fname.indexOf("fasilitas") !== -1 || fname.indexOf("gudang") !== -1 || fname.indexOf("parkir") !== -1) {
      targetSubName = DRIVE_SUBFOLDERS.FACILITY;
    } else if (fname.indexOf("pos") !== -1 || fname.indexOf("patroli") !== -1) {
      targetSubName = DRIVE_SUBFOLDERS.CHECKPOINT;
    }

    if (targetSubName && folderMap[targetSubName]) {
      try {
        file.moveTo(folderMap[targetSubName]);
        movedCount++;
      } catch (e) {}
    }
  }
  return "7 Sub-Folder Drive siap & " + movedCount + " file dirapikan.";
}

function setupSheetBahasaIndonesia() {
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  Object.keys(SCHEMAS_ID).forEach(function(sheetName) {
    ensureIndonesianSheet_(ss, sheetName, SCHEMAS_ID[sheetName]);
  });

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

  var oldEnglishTabs = ["TRUCK_VISITS", "STATUS_EVENTS", "DOCKS", "MAP_NODES", "USERS", "Sheet1"];
  oldEnglishTabs.forEach(function(oldName) {
    var oldSheet = ss.getSheetByName(oldName);
    if (oldSheet && ss.getSheets().length > 1) {
      ss.deleteSheet(oldSheet);
    }
  });

  lockDatabaseUserProtectionOnly_(userSheet);
  var driveMsg = rapikanFolderDrive();
  return "7 Tab Sheet siap & " + driveMsg;
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
    if (protection.canDomainEdit()) protection.setDomainEdit(false);
  } catch (err) {}
}

function lockAndHideUserSheet() {
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ss.getSheetByName("DATABASE_USER");
  if (!sheet) return "Tab DATABASE_USER belum ada.";

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
  if (ss.getSheets().length > 1) sheet.hideSheet();
  return "Tab DATABASE_USER berhasil dikunci dan disembunyikan!";
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
// 2. HANDLER GET & POST WEB APP
// ==========================================
function doGet(e) {
  try {
    var action = e && e.parameter ? String(e.parameter.action || "") : "";
    if (action === "ping" || !action) {
      return json_({status: "ok", message: "Server SecPatrol Bahasa Indonesia aktif."});
    }
    if (action === "checkHandoverStatus") {
      var hoId = String(e.parameter.handoverId || "").trim();
      return checkHandoverStatus_(hoId);
    }
    if (action === "verifyUser") {
      var getPayload = {
        username: e.parameter.username,
        password: e.parameter.password,
        deviceModel: e.parameter.device || e.parameter.deviceModel || ""
      };
      syncUserDeviceColumn_(getPayload);
      return verifyUser_(getPayload);
    }
    if (action === "getActiveOfficers") {
      return getActiveOfficers_();
    }
    if (action === "getActiveInboundQueue") {
      return getActiveInboundQueue_();
    }
    return json_({status: "error", message: "Aksi GET tidak dikenali."});
  } catch (err) {
    return json_({status: "error", message: "Gagal memproses GET: " + err.message});
  }
}

function doPost(e) {
  try {
    var contents = (e && e.postData && e.postData.contents) || "{}";
    if (contents.length > CONFIG.MAX_REQUEST_CHARS) {
      return json_({status: "error", message: "Ukuran file terlalu besar."});
    }
    var body = JSON.parse(contents);
    var action = String(body.action || "");

    // Otomatis catat/perbarui Kolom F (Tipe Perangkat HP) di DATABASE_USER
    syncUserDeviceColumn_(body);

    if (action === "verifyUser") return verifyUser_(body);

    var idempKey = cleanText_(body.idempotencyKey || body.fileName || "", 120);
    if (idempKey && isDuplicateRequest_(idempKey)) {
      return json_({status: "success", deduplicated: true, message: "Laporan sudah tercatat sebelumnya."});
    }

    switch (action) {
      case "handover": return saveHandover_(body, idempKey);
      case "checkpoint": return saveCheckpoint_(body, idempKey);
      case "facility_multi": return saveFacilityMulti_(body, idempKey);
      case "inbound": return saveInbound_(body, idempKey);
      case "genset": return saveGenset_(body, idempKey);
      case "video_patrol": return saveVideoPatrol_(body, idempKey);
      default: return json_({status: "error", message: "Aksi tidak dikenali: " + action});
    }
  } catch (err) {
    return json_({status: "error", message: "Gagal diproses server: " + err.message});
  }
}

// ==========================================
// 3. VERIFIKASI LOGIN & DAFTAR SECURITY AKTIF
// ==========================================
function getActiveOfficersListInternal_() {
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ss.getSheetByName("DATABASE_USER");
  if (!sheet || sheet.getLastRow() < 2) return [];

  var values = sheet.getRange(2, 1, sheet.getLastRow() - 1, 5).getValues();
  var list = [];
  for (var i = 0; i < values.length; i++) {
    var nip = String(values[i][0] || "").trim().toUpperCase();
    var fullName = String(values[i][2] || "").trim();
    var shiftDef = String(values[i][3] || "Shift Pagi").trim();
    var status = String(values[i][4] || "").trim().toUpperCase();

    if (nip && fullName && (status === "AKTIF" || status === "ACTIVE" || status === "TRUE" || status === "1")) {
      list.push({
        nip: nip,
        name: fullName,
        shift: shiftDef
      });
    }
  }
  return list;
}

function getActiveOfficers_() {
  var cache = CacheService.getScriptCache();
  var cached = cache.get("ACTIVE_OFFICERS_CACHE");
  if (cached) {
    return ContentService.createTextOutput(cached).setMimeType(ContentService.MimeType.JSON);
  }
  var list = getActiveOfficersListInternal_();
  var out = JSON.stringify({ status: "success", officers: list });
  cache.put("ACTIVE_OFFICERS_CACHE", out, 60);
  return ContentService.createTextOutput(out).setMimeType(ContentService.MimeType.JSON);
}

function verifyUser_(body) {
  var username = String(body.username || "").trim().toUpperCase();
  var password = String(body.password || "").trim();
  var deviceInfo = cleanText_(body.deviceModel || body.device || "", 100);

  if (!username || !password) {
    return json_({status: "error", message: "Username/NIP dan Password wajib diisi!"});
  }

  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ensureIndonesianSheet_(ss, "DATABASE_USER", SCHEMAS_ID.DATABASE_USER);
  if (sheet.getLastRow() < 2) setupSheetBahasaIndonesia();

  var values = sheet.getRange(2, 1, sheet.getLastRow() - 1, 6).getValues();
  for (var i = 0; i < values.length; i++) {
    var row = values[i];
    var rowNip = String(row[0] || "").trim().toUpperCase();
    var storedPass = String(row[1] || "").trim();
    var rowName = String(row[2] || username).trim();
    var rowShift = String(row[3] || "").trim() || "Shift Pagi";
    // Wajib tertulis "AKTIF" (Jika kosong maka ditolak)
    var rowStatus = String(row[4] || "").trim().toUpperCase();

    if (rowNip === username) {
      if (rowStatus !== "AKTIF" && rowStatus !== "ACTIVE" && rowStatus !== "TRUE" && rowStatus !== "1") {
        return json_({
          status: "error",
          message: "Login Ditolak: Status akun (" + rowNip + ") belum AKTIF atau masih kosong di DATABASE_USER."
        });
      }

      var expectedLocked = "🔒 TERKUNCI:" + hashUserPassword_(rowNip, password);
      if (storedPass === password || storedPass === expectedLocked) {
        if (storedPass === password) sheet.getRange(i + 2, 2).setValue(expectedLocked);
        if (deviceInfo) sheet.getRange(i + 2, 6).setValue(safeSheetValue_(deviceInfo));
        return json_({
          status: "success",
          user: { id: rowNip, name: rowName, shift: rowShift },
          officers: getActiveOfficersListInternal_(),
          sessionToken: Utilities.getUuid().replace(/-/g, "")
        });
      } else {
        return json_({status: "error", message: "Password salah!"});
      }
    }
  }
  return json_({status: "error", message: "NIP / Username tidak ditemukan di DATABASE_USER."});
}

// ==========================================
// 4. SIMPAN LAPORAN KE 6 TAB & 7 SUB-FOLDER DRIVE
// ==========================================
function saveHandover_(body, idempKey) {
  var links = saveBase64ImagesToDriveSubfolder_(
    extractPhotosArray_(body),
    body.fileName || ("Selfie_HO_" + Date.now() + ".jpg"),
    DRIVE_SUBFOLDERS.HANDOVER
  );
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
      safeSheetValue_(cleanText_(body.oldShift, 50) || "Shift Malam"),
      safeSheetValue_(cleanText_(body.newShift, 50) || "Shift Pagi"),
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
  var links = saveBase64ImagesToDriveSubfolder_(
    extractPhotosArray_(body),
    body.fileName || ("Pos_Patroli_" + Date.now() + ".jpg"),
    DRIVE_SUBFOLDERS.CHECKPOINT
  );
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
  if (!nopol) return json_({status: "error", message: "Nomor Polisi wajib diisi."});

  var links = saveBase64ImagesToDriveSubfolder_(
    extractPhotosArray_(body),
    body.fileName || ("Truck_" + nopol.replace(/\s+/g, "_") + "_" + Date.now() + ".jpg"),
    DRIVE_SUBFOLDERS.INBOUND
  );
  var statusMuatan = cleanText_(body.status, 100) || "Truck Datang - Antri Bongkar";
  var isSelesai = statusMuatan.toLowerCase().indexOf("selesai") !== -1;
  var statusAntrean = isSelesai ? "SELESAI" : "OUTSTANDING";

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

    CacheService.getScriptCache().remove("ACTIVE_INBOUND_CACHE");
    markRequestCompleted_(idempKey);
    return json_({status: "success", message: "Data Logistik Inbound tersimpan."});
  } finally {
    lock.releaseLock();
  }
}

function saveGenset_(body, idempKey) {
  var fisikLinks = saveBase64ImagesToDriveSubfolder_(
    [body.imageBase64],
    body.fileName || ("Genset_Fisik_" + Date.now() + ".jpg"),
    DRIVE_SUBFOLDERS.GENSET_PHOTO
  );
  var hseLinks = body.hseDocBase64
    ? saveBase64ImagesToDriveSubfolder_([body.hseDocBase64], "Genset_HSE_" + Date.now() + ".jpg", DRIVE_SUBFOLDERS.GENSET_PHOTO)
    : [];
  var videoLink = body.videoBase64
    ? saveVideoToDriveSubfolderPlayable_(body.videoBase64, "Genset_Video_" + Date.now() + ".mp4", DRIVE_SUBFOLDERS.GENSET_VIDEO)
    : "-";

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureIndonesianSheet_(ss, "PENGECEKAN_GENSET", SCHEMAS_ID.PENGECEKAN_GENSET);
    sheet.appendRow([
      formatWaktuWib_(),
      safeSheetValue_(cleanText_(body.statusGenset, 100) || "Uji Pemanasan (Warming Up Rutin)"),
      safeSheetValue_(cleanText_(body.oilCheck, 100) || "Level Normal & Bening/Kuning"),
      safeSheetValue_(cleanText_(body.fuelInfo, 100) || "Standby Normal"),
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
  var links = saveBase64ImagesToDriveSubfolder_(
    extractPhotosArray_(body),
    body.fileName || ("Fasilitas_" + Date.now() + ".jpg"),
    DRIVE_SUBFOLDERS.FACILITY
  );
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
  var vName = cleanFilename_(body.fileName || ("Video_Patroli_" + Date.now() + ".mp4"));
  var videoLink = body.videoBase64
    ? saveVideoToDriveSubfolderPlayable_(body.videoBase64, vName, DRIVE_SUBFOLDERS.VIDEO_PATROL)
    : "-";

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
    var cache = CacheService.getScriptCache();
    var cached = cache.get("ACTIVE_INBOUND_CACHE");
    if (cached) return ContentService.createTextOutput(cached).setMimeType(ContentService.MimeType.JSON);

    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ss.getSheetByName("LOGISTIK_INBOUND");
    if (!sheet || sheet.getLastRow() < 2) return json_({status: "success", data: []});

    var lastRow = sheet.getLastRow();
    var startRow = Math.max(2, lastRow - 80);
    var values = sheet.getRange(startRow, 1, (lastRow - startRow) + 1, 10).getValues();
    var latestByNopol = {};

    for (var i = 0; i < values.length; i++) {
      var r = values[i];
      var nopol = String(r[1] || "").trim().toUpperCase();
      if (!nopol || nopol === "TRUCK" || nopol === "TIBA") continue;
      var nopolKey = nopol.replace(/\s+/g, "");
      latestByNopol[nopolKey] = {
        nopol: nopol,
        driverName: String(r[2] || ""),
        driverPhone: String(r[3] || ""),
        division: String(r[4] || ""),
        vehicleType: String(r[5] || ""),
        expedition: String(r[6] || ""),
        sealNo: String(r[7] || ""),
        status: String(r[8] || "").trim(),
        queueState: String(r[9] || "").trim().toUpperCase()
      };
    }

    var activeList = [];
    Object.keys(latestByNopol).forEach(function(np) {
      var item = latestByNopol[np];
      if (item.queueState !== "SELESAI" && item.status.toLowerCase().indexOf("selesai") === -1) {
        activeList.push(item);
      }
    });

    var out = JSON.stringify({status: "success", data: activeList});
    cache.put("ACTIVE_INBOUND_CACHE", out, 15);
    return ContentService.createTextOutput(out).setMimeType(ContentService.MimeType.JSON);
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
// 6. HELPER DRIVE, DEVICE SYNC & UTILITAS
// ==========================================
function syncUserDeviceColumn_(body) {
  try {
    var devInfo = cleanText_(body.deviceModel || body.device || "", 100);
    if (!devInfo) return;

    var targetKey = String(body.username || body.officerId || body.officerName || body.receiver || "")
      .trim()
      .toUpperCase();
    if (!targetKey) return;

    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ss.getSheetByName("DATABASE_USER");
    if (!sheet || sheet.getLastRow() < 2) return;

    var values = sheet.getRange(2, 1, sheet.getLastRow() - 1, 6).getValues();
    for (var i = 0; i < values.length; i++) {
      var rowNip = String(values[i][0] || "").trim().toUpperCase();
      var rowName = String(values[i][2] || "").trim().toUpperCase();
      var currentDev = String(values[i][5] || "").trim();

      if (rowNip === targetKey || rowName === targetKey) {
        if (currentDev !== devInfo) {
          sheet.getRange(i + 2, 6).setValue(safeSheetValue_(devInfo));
        }
        break;
      }
    }
  } catch (e) {}
}

function getOrCreateTargetSubfolder_(subfolderName) {
  var parentFolder = DriveApp.getFolderById(String(CONFIG.PHOTO_FOLDER_ID).trim());
  if (!subfolderName) return parentFolder;
  var subIter = parentFolder.getFoldersByName(subfolderName);
  return subIter.hasNext() ? subIter.next() : parentFolder.createFolder(subfolderName);
}

function saveBase64ImagesToDriveSubfolder_(photos, defaultName, subfolderName) {
  if (!photos || !photos.length) return [];
  var targetFolder = getOrCreateTargetSubfolder_(subfolderName);
  var baseName = cleanFilename_(defaultName);

  return photos.map(function(encoded, idx) {
    var cleanB64 = String(encoded || "").trim().replace(/^data:image\/[a-zA-Z0-9.+-]+;base64,/, "");
    if (!cleanB64) return "-";
    var suffix = photos.length > 1 ? "_" + (idx + 1) : "";
    var name = baseName.replace(/\.(jpg|jpeg|png|webp)$/i, "") + suffix + ".jpg";
    var file = targetFolder.createFile(Utilities.newBlob(Utilities.base64Decode(cleanB64), "image/jpeg", name));
    try {
      file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
    } catch (e) {}
    return driveViewUrl_(file.getId());
  });
}

function saveVideoToDriveSubfolderPlayable_(base64Video, fileName, subfolderName) {
  var targetFolder = getOrCreateTargetSubfolder_(subfolderName);
  var cleanBase64 = String(base64Video || "").trim().replace(/^data:video\/[a-zA-Z0-9.+-]+;base64,/, "");
  if (!cleanBase64) return "-";
  var blob = Utilities.newBlob(Utilities.base64Decode(cleanBase64), "video/mp4", cleanFilename_(fileName));
  var vFile = targetFolder.createFile(blob);
  try {
    vFile.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  } catch (e) {}
  return driveViewUrl_(vFile.getId());
}

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
  var str = String(val == null ? "" : val).trim();
  if (/^-[0-9]/.test(str)) return str;
  return /^[=+\-@]/.test(str) ? "'" + str : str;
}

function cleanText_(val, max) {
  return String(val == null ? "" : val).replace(/[\u0000-\u001F\u007F]/g, " ").trim().slice(0, max);
}

function cleanFilename_(val) {
  return String(val || "File.jpg").split(/[\\/]/).pop().replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 80) || "File.jpg";
}

function json_(val) {
  return ContentService.createTextOutput(JSON.stringify(val)).setMimeType(ContentService.MimeType.JSON);
}
