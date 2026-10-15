const CONFIG = {
  SPREADSHEET_ID: "1pCLV1YRYcW061kz_AsBdH7ecOauJgmdeqTR2hWUerJk",
  PHOTO_FOLDER_ID: "1OFj7ByexcByx1-AwmkL02Wg99ltflmm0",
  API_ENABLED: true,
  PASSWORD_HASH_ROUNDS: 4096,
  SESSION_TTL_SECONDS: 21600,
  LOGIN_LOCK_SECONDS: 300,
  LOGIN_MAX_FAILURES: 8,
  MAX_REQUEST_CHARS: 25000000
};

const SCHEMAS = {
  USERS: ["user_id", "email", "display_name", "role", "site_id", "active", "username", "shift", "password_salt", "password_hash"],
  TRUCK_VISITS: ["visit_id", "site_id", "nopol", "driver_name", "driver_phone", "vendor", "division", "vehicle_type", "po_do", "seal_no", "cargo_summary", "coordinates", "status", "dock_id", "arrival_at_server", "queue_at_server", "unload_started_at_server", "unload_finished_at_server", "exit_at_server", "gate_officer_id", "notes", "photo_drive_file_id"],
  STATUS_EVENTS: ["event_id", "visit_id", "from_status", "to_status", "event_at_server", "actor_id", "source", "reason", "idempotency_key"],
  DOCKS: ["dock_id", "site_id", "dock_name", "map_node_id", "active", "maintenance_note"],
  MAP_NODES: ["node_id", "site_id", "label", "node_type", "x_m", "y_m", "rotation_deg", "connected_to", "restricted"],
  SERAH_TERIMA: ["timestamp", "handover_id", "giver", "receiver", "old_shift", "new_shift", "area", "face_count", "photo_drive_file_id", "status"],
  TITIK_PATROLI: ["timestamp", "checkpoint_name", "officer_name", "note", "address", "coordinates", "photo_drive_file_id"],
  DOKUMENTASI_GUDANG: ["timestamp", "report_name", "officer_name", "note", "address", "coordinates", "photo_drive_file_ids"],
  LOGISTIK_INBOUND: ["timestamp", "visit_id", "nopol", "driver_name", "driver_phone", "division", "vehicle_type", "expedition", "seal_no", "status", "officer_name", "note", "coordinates", "photo_drive_file_ids"],
  PENGECEKAN_GENSET: ["timestamp", "status_genset", "oil_check", "fuel_info", "officer_name", "note", "address", "coordinates", "photo_fisik_id", "photo_hse_id", "video_id"],
  VIDEO_PATROLI: ["timestamp", "title", "officer_name", "note", "coordinates", "video_drive_file_id"]
};

function doGet(e) {
  var action = e && e.parameter ? String(e.parameter.action || "") : "";
  if (action === "ping" || !action) {
    return json_({status: "ok", apiEnabled: CONFIG.API_ENABLED, message: "Server SecPatrol aktif."});
  }
  if (action === "checkHandoverStatus") {
    var hoId = String(e.parameter.handoverId || "").trim();
    var done = hoId && CacheService.getScriptCache().get("HO_DONE_" + hoId) === "completed";
    return json_({status: done ? "completed" : "pending"});
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
      return json_({status: "error", message: "Ukuran permintaan terlalu besar."});
    }
    var body = JSON.parse(contents);
    var action = String(body.action || "");

    if (action === "verifyUser") return verifyUser_(body);

    var idempKey = cleanText_(body.idempotencyKey || body.fileName || "", 120);
    if (idempKey && isDuplicateRequest_(idempKey)) {
      return json_({status: "success", deduplicated: true, message: "Laporan sudah tercatat sebelumnya."});
    }

    var session = authenticate_(body.sessionToken);

    switch (action) {
      case "inbound": return saveInbound_(body, session, idempKey);
      case "handover": return saveHandover_(body, idempKey);
      case "checkpoint": return saveCheckpoint_(body, idempKey);
      case "facility_multi": return saveFacilityMulti_(body, idempKey);
      case "genset": return saveGenset_(body, idempKey);
      case "video_patrol": return saveVideoPatrol_(body, idempKey);
      default: return json_({status: "error", message: "Aksi tidak dikenali: " + action});
    }
  } catch (err) {
    return json_({status: "error", message: "Gagal diproses: " + err.message});
  }
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

function authenticate_(token) {
  token = String(token || "").trim();
  if (!token || token.length > 256) return null;
  var raw = CacheService.getScriptCache().get("SESSION_" + digestText_(token));
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (err) { return null; }
}

function verifyUser_(body) {
  var username = String(body.username || "").trim().toUpperCase();
  var password = String(body.password || "");
  if (!username || !password) return json_({status: "error", message: "Kredensial wajib diisi."});

  var cache = CacheService.getScriptCache();
  var lockKey = "LOGIN_FAIL_" + digestText_(username).slice(0, 24);
  var failures = Number(cache.get(lockKey) || 0);
  if (failures >= CONFIG.LOGIN_MAX_FAILURES) {
    return json_({status: "error", message: "Terlalu banyak percobaan login. Tunggu 5 menit."});
  }

  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ss.getSheetByName("USERS");
  if (!sheet || sheet.getLastRow() < 2) return failedLogin_(cache, lockKey, failures);

  var headers = headerMap_(sheet);
  var values = sheet.getRange(2, 1, sheet.getLastRow() - 1, sheet.getLastColumn()).getValues();
  for (var i = 0; i < values.length; i++) {
    var row = values[i];
    var rowUser = String(cell_(row, headers, "username") || cell_(row, headers, "user_id") || "").trim().toUpperCase();
    if (rowUser !== username) continue;

    var active = String(cell_(row, headers, "active") || "").trim().toUpperCase();
    var isActive = active === "TRUE" || active === "AKTIF" || active === "ACTIVE" || active === "1";
    var salt = String(cell_(row, headers, "password_salt") || "");
    var expectedHash = String(cell_(row, headers, "password_hash") || "");

    if (isActive && salt && expectedHash && constantTimeEquals_(hashPassword_(password, salt), expectedHash)) {
      cache.remove(lockKey);
      var userId = String(cell_(row, headers, "user_id") || username).trim();
      var role = String(cell_(row, headers, "role") || "SECURITY").trim();
      var siteId = String(cell_(row, headers, "site_id") || "KEDIRI").trim();
      var token = Utilities.getUuid().replace(/-/g, "") + Utilities.getUuid().replace(/-/g, "");
      cache.put("SESSION_" + digestText_(token), JSON.stringify({userId: userId, role: role, siteId: siteId}), CONFIG.SESSION_TTL_SECONDS);

      return json_({
        status: "success",
        user: {
          id: userId,
          name: String(cell_(row, headers, "display_name") || username).trim(),
          shift: String(cell_(row, headers, "shift") || "Shift Pagi").trim()
        },
        sessionToken: token
      });
    }
    return failedLogin_(cache, lockKey, failures);
  }
  return failedLogin_(cache, lockKey, failures);
}

function failedLogin_(cache, key, failures) {
  cache.put(key, String(failures + 1), CONFIG.LOGIN_LOCK_SECONDS);
  return json_({status: "error", message: "Username/NIP atau Password salah."});
}

function saveInbound_(body, session, idempKey) {
  var nopol = cleanText_(body.nopol, 32).toUpperCase();
  var driverName = cleanText_(body.driverName, 100) || "-";
  var status = cleanText_(body.status, 80);
  if (!nopol) return json_({status: "error", message: "Nopol wajib diisi."});

  var fileIds = saveBase64ImagesToDrive_(extractPhotosArray_(body), body.fileName || ("Inbound_" + nopol + ".jpg"));
  var visitId = Utilities.getUuid();
  var now = new Date();
  var actorId = (session && session.userId) ? session.userId : cleanText_(body.officerName, 80);
  var siteId = (session && session.siteId) ? session.siteId : "KEDIRI";

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var truckSheet = ensureSheetSchema_(ss, "TRUCK_VISITS", SCHEMAS.TRUCK_VISITS);
    var headers = headerMap_(truckSheet);
    var row = new Array(truckSheet.getLastColumn()).fill("");
    setCell_(row, headers, "visit_id", visitId);
    setCell_(row, headers, "site_id", siteId);
    setCell_(row, headers, "nopol", safeSheetValue_(nopol));
    setCell_(row, headers, "driver_name", safeSheetValue_(driverName));
    setCell_(row, headers, "driver_phone", safeSheetValue_(cleanText_(body.driverPhone, 40)));
    setCell_(row, headers, "vendor", safeSheetValue_(cleanText_(body.expedition, 100)));
    setCell_(row, headers, "division", safeSheetValue_(cleanText_(body.division, 120)));
    setCell_(row, headers, "vehicle_type", safeSheetValue_(cleanText_(body.vehicleType, 80)));
    setCell_(row, headers, "seal_no", safeSheetValue_(cleanText_(body.sealNo, 80)));
    setCell_(row, headers, "coordinates", safeSheetValue_(cleanText_(body.coordinates, 120)));
    setCell_(row, headers, "status", safeSheetValue_(status));
    setCell_(row, headers, "arrival_at_server", now);
    if (status.indexOf("Antri") !== -1) setCell_(row, headers, "queue_at_server", now);
    if (status.indexOf("Sedang Bongkar") !== -1) setCell_(row, headers, "unload_started_at_server", now);
    if (status.indexOf("Selesai") !== -1) setCell_(row, headers, "unload_finished_at_server", now);
    setCell_(row, headers, "gate_officer_id", safeSheetValue_(actorId));
    setCell_(row, headers, "notes", safeSheetValue_(cleanText_(body.note, 1000)));
    setCell_(row, headers, "photo_drive_file_id", fileIds.join(","));
    truckSheet.appendRow(row);

    var eventSheet = ensureSheetSchema_(ss, "STATUS_EVENTS", SCHEMAS.STATUS_EVENTS);
    var eHeaders = headerMap_(eventSheet);
    var eRow = new Array(eventSheet.getLastColumn()).fill("");
    setCell_(eRow, eHeaders, "event_id", Utilities.getUuid());
    setCell_(eRow, eHeaders, "visit_id", visitId);
    setCell_(eRow, eHeaders, "from_status", "");
    setCell_(eRow, eHeaders, "to_status", safeSheetValue_(status));
    setCell_(eRow, eHeaders, "event_at_server", now);
    setCell_(eRow, eHeaders, "actor_id", safeSheetValue_(actorId));
    setCell_(eRow, eHeaders, "source", "ANDROID_APP");
    setCell_(eRow, eHeaders, "reason", "Update status inbound");
    setCell_(eRow, eHeaders, "idempotency_key", idempKey || visitId);
    eventSheet.appendRow(eRow);

    var logSheet = ensureSheetSchema_(ss, "LOGISTIK_INBOUND", SCHEMAS.LOGISTIK_INBOUND);
    var lHeaders = headerMap_(logSheet);
    var lRow = new Array(logSheet.getLastColumn()).fill("");
    setCell_(lRow, lHeaders, "timestamp", now);
    setCell_(lRow, lHeaders, "visit_id", visitId);
    setCell_(lRow, lHeaders, "nopol", safeSheetValue_(nopol));
    setCell_(lRow, lHeaders, "driver_name", safeSheetValue_(driverName));
    setCell_(lRow, lHeaders, "driver_phone", safeSheetValue_(cleanText_(body.driverPhone, 40)));
    setCell_(lRow, lHeaders, "division", safeSheetValue_(cleanText_(body.division, 100)));
    setCell_(lRow, lHeaders, "vehicle_type", safeSheetValue_(cleanText_(body.vehicleType, 80)));
    setCell_(lRow, lHeaders, "expedition", safeSheetValue_(cleanText_(body.expedition, 100)));
    setCell_(lRow, lHeaders, "seal_no", safeSheetValue_(cleanText_(body.sealNo, 80)));
    setCell_(lRow, lHeaders, "status", safeSheetValue_(status));
    setCell_(lRow, lHeaders, "officer_name", safeSheetValue_(cleanText_(body.officerName, 100)));
    setCell_(lRow, lHeaders, "note", safeSheetValue_(cleanText_(body.note, 1000)));
    setCell_(lRow, lHeaders, "coordinates", safeSheetValue_(cleanText_(body.coordinates, 120)));
    setCell_(lRow, lHeaders, "photo_drive_file_ids", fileIds.join(","));
    logSheet.appendRow(lRow);

    markRequestCompleted_(idempKey);
    return json_({status: "success", visitId: visitId});
  } finally {
    lock.releaseLock();
  }
}

function saveHandover_(body, idempKey) {
  var fileIds = saveBase64ImagesToDrive_(extractPhotosArray_(body), body.fileName || "Selfie_HO.jpg");
  var hoId = cleanText_(body.handoverId, 80);
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureSheetSchema_(ss, "SERAH_TERIMA", SCHEMAS.SERAH_TERIMA);
    var headers = headerMap_(sheet);
    var row = new Array(sheet.getLastColumn()).fill("");
    setCell_(row, headers, "timestamp", new Date());
    setCell_(row, headers, "handover_id", safeSheetValue_(hoId));
    setCell_(row, headers, "giver", safeSheetValue_(cleanText_(body.giver, 100)));
    setCell_(row, headers, "receiver", safeSheetValue_(cleanText_(body.receiver, 100)));
    setCell_(row, headers, "old_shift", safeSheetValue_(cleanText_(body.oldShift, 50)));
    setCell_(row, headers, "new_shift", safeSheetValue_(cleanText_(body.newShift, 50)));
    setCell_(row, headers, "area", safeSheetValue_(cleanText_(body.area, 120)));
    setCell_(row, headers, "face_count", Number(body.faceCount || 1));
    setCell_(row, headers, "photo_drive_file_id", fileIds.join(","));
    setCell_(row, headers, "status", "COMPLETED");
    sheet.appendRow(row);

    if (hoId) CacheService.getScriptCache().put("HO_DONE_" + hoId, "completed", 3600);
    markRequestCompleted_(idempKey);
    return json_({status: "success"});
  } finally {
    lock.releaseLock();
  }
}

function saveCheckpoint_(body, idempKey) {
  var fileIds = saveBase64ImagesToDrive_(extractPhotosArray_(body), body.fileName || "Pos.jpg");
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureSheetSchema_(ss, "TITIK_PATROLI", SCHEMAS.TITIK_PATROLI);
    var headers = headerMap_(sheet);
    var row = new Array(sheet.getLastColumn()).fill("");
    setCell_(row, headers, "timestamp", new Date());
    setCell_(row, headers, "checkpoint_name", safeSheetValue_(cleanText_(body.checkpointName, 100)));
    setCell_(row, headers, "officer_name", safeSheetValue_(cleanText_(body.officerName, 100)));
    setCell_(row, headers, "note", safeSheetValue_(cleanText_(body.note, 1000)));
    setCell_(row, headers, "address", safeSheetValue_(cleanText_(body.address, 250)));
    setCell_(row, headers, "coordinates", safeSheetValue_(cleanText_(body.coordinates, 120)));
    setCell_(row, headers, "photo_drive_file_id", fileIds.join(","));
    sheet.appendRow(row);
    markRequestCompleted_(idempKey);
    return json_({status: "success"});
  } finally {
    lock.releaseLock();
  }
}

function saveFacilityMulti_(body, idempKey) {
  var fileIds = saveBase64ImagesToDrive_(extractPhotosArray_(body), body.fileName || "Fasilitas.jpg");
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureSheetSchema_(ss, "DOKUMENTASI_GUDANG", SCHEMAS.DOKUMENTASI_GUDANG);
    var headers = headerMap_(sheet);
    var row = new Array(sheet.getLastColumn()).fill("");
    setCell_(row, headers, "timestamp", new Date());
    setCell_(row, headers, "report_name", safeSheetValue_(cleanText_(body.reportName, 120)));
    setCell_(row, headers, "officer_name", safeSheetValue_(cleanText_(body.officerName, 100)));
    setCell_(row, headers, "note", safeSheetValue_(cleanText_(body.note, 1000)));
    setCell_(row, headers, "address", safeSheetValue_(cleanText_(body.address, 250)));
    setCell_(row, headers, "coordinates", safeSheetValue_(cleanText_(body.coordinates, 120)));
    setCell_(row, headers, "photo_drive_file_ids", fileIds.join(","));
    sheet.appendRow(row);
    markRequestCompleted_(idempKey);
    return json_({status: "success"});
  } finally {
    lock.releaseLock();
  }
}

function saveGenset_(body, idempKey) {
  var folder = DriveApp.getFolderById(CONFIG.PHOTO_FOLDER_ID);
  var fisikIds = saveBase64ImagesToDrive_([body.imageBase64], body.fileName || "Genset.jpg");
  var hseIds = body.hseDocBase64 ? saveBase64ImagesToDrive_([body.hseDocBase64], "Genset_HSE.jpg") : [];
  var videoId = "";
  if (body.videoBase64) {
    var vFile = folder.createFile(Utilities.newBlob(Utilities.base64Decode(String(body.videoBase64)), "video/mp4", "Genset_" + Date.now() + ".mp4"));
    videoId = vFile.getId();
  }
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureSheetSchema_(ss, "PENGECEKAN_GENSET", SCHEMAS.PENGECEKAN_GENSET);
    var headers = headerMap_(sheet);
    var row = new Array(sheet.getLastColumn()).fill("");
    setCell_(row, headers, "timestamp", new Date());
    setCell_(row, headers, "status_genset", safeSheetValue_(cleanText_(body.statusGenset, 100)));
    setCell_(row, headers, "oil_check", safeSheetValue_(cleanText_(body.oilCheck, 100)));
    setCell_(row, headers, "fuel_info", safeSheetValue_(cleanText_(body.fuelInfo, 100)));
    setCell_(row, headers, "officer_name", safeSheetValue_(cleanText_(body.officerName, 100)));
    setCell_(row, headers, "note", safeSheetValue_(cleanText_(body.note, 1000)));
    setCell_(row, headers, "address", safeSheetValue_(cleanText_(body.address, 250)));
    setCell_(row, headers, "coordinates", safeSheetValue_(cleanText_(body.coordinates, 120)));
    setCell_(row, headers, "photo_fisik_id", fisikIds.join(","));
    setCell_(row, headers, "photo_hse_id", hseIds.join(","));
    setCell_(row, headers, "video_id", videoId);
    sheet.appendRow(row);
    markRequestCompleted_(idempKey);
    return json_({status: "success"});
  } finally {
    lock.releaseLock();
  }
}

function saveVideoPatrol_(body, idempKey) {
  var folder = DriveApp.getFolderById(CONFIG.PHOTO_FOLDER_ID);
  var videoId = "";
  if (body.videoBase64) {
    var vFile = folder.createFile(Utilities.newBlob(Utilities.base64Decode(String(body.videoBase64)), "video/mp4", cleanFilename_(body.fileName || "Patroli.mp4")));
    videoId = vFile.getId();
  }
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) throw new Error("Server sibuk.");
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ensureSheetSchema_(ss, "VIDEO_PATROLI", SCHEMAS.VIDEO_PATROLI);
    var headers = headerMap_(sheet);
    var row = new Array(sheet.getLastColumn()).fill("");
    setCell_(row, headers, "timestamp", new Date());
    setCell_(row, headers, "title", safeSheetValue_(cleanText_(body.title, 120)));
    setCell_(row, headers, "officer_name", safeSheetValue_(cleanText_(body.officerName, 100)));
    setCell_(row, headers, "note", safeSheetValue_(cleanText_(body.note, 1000)));
    setCell_(row, headers, "coordinates", safeSheetValue_(cleanText_(body.coordinates, 120)));
    setCell_(row, headers, "video_drive_file_id", videoId);
    sheet.appendRow(row);
    markRequestCompleted_(idempKey);
    return json_({status: "success"});
  } finally {
    lock.releaseLock();
  }
}

function getActiveInboundQueue_() {
  try {
    var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
    var sheet = ss.getSheetByName("TRUCK_VISITS");
    if (!sheet || sheet.getLastRow() < 2) return json_({status: "success", data: []});
    var headers = headerMap_(sheet);
    var values = sheet.getRange(2, 1, sheet.getLastRow() - 1, sheet.getLastColumn()).getValues();
    var latestByNopol = {};
    for (var i = 0; i < values.length; i++) {
      var r = values[i];
      var nopol = String(cell_(r, headers, "nopol") || "").trim().toUpperCase();
      if (!nopol || nopol === "TRUCK") continue;
      latestByNopol[nopol] = {
        nopol: nopol,
        driverName: String(cell_(r, headers, "driver_name") || ""),
        driverPhone: String(cell_(r, headers, "driver_phone") || ""),
        division: String(cell_(r, headers, "division") || ""),
        vehicleType: String(cell_(r, headers, "vehicle_type") || ""),
        expedition: String(cell_(r, headers, "vendor") || ""),
        sealNo: String(cell_(r, headers, "seal_no") || ""),
        status: String(cell_(r, headers, "status") || "").trim()
      };
    }
    var activeList = [];
    Object.keys(latestByNopol).forEach(function(np) {
      if (latestByNopol[np].status.indexOf("Selesai") === -1) activeList.push(latestByNopol[np]);
    });
    return json_({status: "success", data: activeList});
  } catch (err) {
    return json_({status: "success", data: []});
  }
}

function initializeDataSheets() {
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  Object.keys(SCHEMAS).forEach(function(name) { ensureSheetSchema_(ss, name, SCHEMAS[name]); });
  return "Seluruh 11 tab sheet berhasil disiapkan.";
}

function seedTestAdmin() {
  var properties = PropertiesService.getScriptProperties();
  var password = properties.getProperty("SEC_PATROL_TEST_ADMIN_PASSWORD");
  if (!password || password.length < 6) {
    throw new Error("Isi Script Property SEC_PATROL_TEST_ADMIN_PASSWORD (minimal 6 karakter) terlebih dahulu.");
  }
  var ss = SpreadsheetApp.openById(CONFIG.SPREADSHEET_ID);
  var sheet = ensureSheetSchema_(ss, "USERS", SCHEMAS.USERS);
  var headerMap = headerMap_(sheet);
  var salt = Utilities.getUuid() + Utilities.getUuid();
  var row = new Array(sheet.getLastColumn()).fill("");
  setCell_(row, headerMap, "user_id", "ADMIN-DEV");
  setCell_(row, headerMap, "username", "ADMIN-DEV");
  setCell_(row, headerMap, "display_name", "Admin Uji");
  setCell_(row, headerMap, "shift", "Shift Pagi");
  setCell_(row, headerMap, "role", "ADMIN-DEV");
  setCell_(row, headerMap, "site_id", "KEDIRI");
  setCell_(row, headerMap, "active", true);
  setCell_(row, headerMap, "password_salt", salt);
  setCell_(row, headerMap, "password_hash", hashPassword_(password, salt));
  sheet.appendRow(row);
  properties.deleteProperty("SEC_PATROL_TEST_ADMIN_PASSWORD");
  return "Akun ADMIN-DEV berhasil ditambahkan.";
}

function extractPhotosArray_(body) {
  var photos = Array.isArray(body.imagesBase64) ? body.imagesBase64 : (body.imageBase64 ? [body.imageBase64] : []);
  return photos.map(function(v) { return String(v || "").trim().replace(/^data:image\/[a-zA-Z0-9.+-]+;base64,/, ""); }).filter(Boolean).slice(0, 3);
}

function saveBase64ImagesToDrive_(photos, defaultName) {
  if (!photos || !photos.length) return [];
  var folder = DriveApp.getFolderById(CONFIG.PHOTO_FOLDER_ID);
  var baseName = cleanFilename_(defaultName);
  return photos.map(function(encoded, idx) {
    var suffix = photos.length > 1 ? "_" + (idx + 1) : "";
    var name = baseName.replace(/\.(jpg|jpeg|png|webp)$/i, "") + suffix + ".jpg";
    return folder.createFile(Utilities.newBlob(Utilities.base64Decode(encoded), "image/jpeg", name)).getId();
  });
}

function safeSheetValue_(val) {
  var str = String(val == null ? "" : val);
  return /^[=+\-@]/.test(str) ? "'" + str : str;
}

function cleanText_(val, max) {
  return String(val == null ? "" : val).replace(/[\u0000-\u001F\u007F]/g, " ").trim().slice(0, max);
}

function cleanFilename_(val) {
  return String(val || "File.jpg").split(/[\\/]/).pop().replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 80) || "File.jpg";
}

function hashPassword_(password, salt) {
  var digest = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, salt + ":" + password, Utilities.Charset.UTF_8);
  for (var i = 1; i < CONFIG.PASSWORD_HASH_ROUNDS; i++) digest = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, digest);
  return Utilities.base64EncodeWebSafe(digest);
}

function digestText_(text) {
  return Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(text), Utilities.Charset.UTF_8));
}

function constantTimeEquals_(a, b) {
  if (a.length !== b.length) return false;
  var diff = 0;
  for (var i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function ensureSheetSchema_(ss, name, headers) {
  var sheet = ss.getSheetByName(name) || ss.insertSheet(name);
  if (sheet.getLastRow() === 0 || sheet.getLastColumn() === 0) {
    sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
    sheet.setFrozenRows(1);
    return sheet;
  }
  var cur = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0].map(function(v) { return String(v || "").trim(); });
  headers.forEach(function(h) {
    if (cur.indexOf(h) === -1) {
      cur.push(h);
      sheet.getRange(1, cur.length).setValue(h);
    }
  });
  sheet.setFrozenRows(1);
  return sheet;
}

function headerMap_(sheet) {
  var headers = sheet.getRange(1, 1, 1, sheet.getLastColumn()).getValues()[0];
  var map = {};
  headers.forEach(function(v, i) { var k = String(v || "").trim(); if (k) map[k] = i + 1; });
  return map;
}

function setCell_(row, map, key, val) { if (map[key]) row[map[key] - 1] = val; }
function cell_(row, map, key) { return map[key] ? row[map[key] - 1] : ""; }
function json_(val) { return ContentService.createTextOutput(JSON.stringify(val)).setMimeType(ContentService.MimeType.JSON); }
