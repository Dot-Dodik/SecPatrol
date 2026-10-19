/**
 * ============================================================================
 * SECPATROL BACKEND API - v1.1.4 (LIVE PRODUCTION & BACKWARD COMPATIBLE)
 * Fitur Utama:
 * 1. Auto-Extract ID dari URL Lengkap Google Sheets & Google Drive
 * 2. Auto-Hash Password (🔒 TERKUNCI:) dengan SHA-256 (4096 Iterasi + Salt NIP)
 * 3. Kompatibel Penuh dengan APK Android Lama & Baru (Single & Multi-Foto)
 * 4. Auto-Styling Header Hijau Tua (#004741) & Sinkronisasi Struktur 7 Sheet
 * 5. Anti-Duplikasi (Idempotency Cache) & Concurrency Lock (LockService)
 * ============================================================================
 */

var SCRIPT_PROP = PropertiesService.getScriptProperties();

var CONFIG = {
  // CUKUP PASTE LINK/URL LENGKAP GOOGLE SHEET LIVE ANDA DI DALAM TANDA KUTIP INI:
  SPREADSHEET_ID: '1pu7vAQTzSppiPtFqDYIxebcKXk1kWwM9yxnQrWOCaS0',

  // OPSIONAL: PASTE LINK/URL FOLDER GOOGLE DRIVE LIVE (Jika dikosongkan, otomatis memakai folder tempat GSheet berada):
  FOLDER_ID: '1uhHx-r31TW2ms0d8Ion4oJ5l4MEw_CG1',

  PASSWORD_PREFIX: '🔒 TERKUNCI:',
  SALT_PREFIX: 'SECPATROL_SALT_',
  HASH_ITERATIONS: 4096,
  HEADER_BG_COLOR: '#004741',
  HEADER_TEXT_COLOR: '#FFFFFF',
  TIMEZONE: 'Asia/Jakarta',
  DEDUP_TTL_SECONDS: 90
};

/**
 * Definisi 7 Struktur Sheet Standar Staging
 */
var SHEET_SCHEMAS = {
  DATABASE_USER: [
    'Username / NIP', 'Password', 'Nama Lengkap', 'Shift Default', 'Status', 'Tipe Perangkat HP'
  ],
  SERAH_TERIMA: [
    'Waktu', 'ID', 'Penyerah', 'Penerima', 'Shift Lama', 'Shift Baru', 'Area', 'Wajah', 'Foto Drive'
  ],
  TITIK_PATROLI: [
    'Waktu', 'Pos/Titik', 'Petugas', 'Catatan', 'Alamat Geotag', 'Koordinat GPS', 'Foto Drive'
  ],
  LOGISTIK_INBOUND: [
    'Waktu', 'Nopol Truck', 'Nama Sopir', 'No HP Driver', 'Divisi', 'Jenis Armada',
    'Ekspedisi', 'No Segel', 'Status Muatan', 'Status Antrean', 'Petugas', 'Catatan',
    'Koordinat GPS', 'Foto Drive', 'Foto 2', 'Foto 3'
  ],
  PENGECEKAN_GENSET: [
    'Waktu', 'Status Genset', 'Kondisi Oli Mesin', 'Solar & Voltase', 'Petugas',
    'Catatan Temuan', 'Alamat Geotag', 'Koordinat GPS', 'Foto Drive', 'Foto Dokumen HSE', 'Video Drive'
  ],
  DOKUMENTASI_GUDANG: [
    'Waktu', 'Area / Fasilitas', 'Petugas', 'Catatan', 'Alamat Geotag',
    'Koordinat GPS', 'Foto Drive', 'Foto 2', 'Foto 3'
  ],
  VIDEO_PATROLI: [
    'Waktu', 'File Video', 'Petugas', 'Catatan', 'Alamat Geotag', 'Koordinat GPS', 'Link Video Drive'
  ]
};

// ============================================================================
// 1. INISIALISASI DATABASE & TRIGGER OTOMATIS (setupDatabase & onEdit)
// ============================================================================

/**
 * Jalankan fungsi `setupDatabase` ini 1x dari toolbar Apps Script Editor
 * untuk merapikan warna header tabel menjadi hijau (#004741) dan langsung
 * mengenkripsi seluruh password plaintext yang ada di DATABASE_USER.
 */
function setupDatabase() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) {
    throw new Error('Proses lain sedang berjalan, silakan tunggu beberapa detik lalu coba lagi.');
  }
  try {
    var ss = getSpreadsheet_();
    var sheetNames = Object.keys(SHEET_SCHEMAS);
    for (var i = 0; i < sheetNames.length; i++) {
      var name = sheetNames[i];
      ensureIndonesianSheet_(ss, name, SHEET_SCHEMAS[name]);
    }
    encryptAllPlainPasswordsInSheet_(ss);
    Logger.log('SUKSES! Seluruh 7 Sheet telah disamakan dengan Staging & Password telah dikunci (🔒 TERKUNCI:).');
  } finally {
    lock.releaseLock();
  }
}

/**
 * Trigger otomatis saat Admin mengetik/mengubah password di Google Sheets.
 * Setiap password plaintext yang diketik di kolom B DATABASE_USER langsung berubah menjadi 🔒 TERKUNCI:...
 */
function onEdit(e) {
  try {
    if (!e || !e.range) return;
    var sheet = e.range.getSheet();
    if (sheet.getName() !== 'DATABASE_USER') return;

    var row = e.range.getRow();
    var col = e.range.getColumn();
    if (row <= 1 || col > 2) return;

    var nipVal = String(sheet.getRange(row, 1).getValue() || '').trim();
    var passVal = String(sheet.getRange(row, 2).getValue() || '').trim();

    if (nipVal && passVal && !isPasswordLocked_(passVal)) {
      var lockedHash = hashUserPassword_(nipVal, passVal);
      sheet.getRange(row, 2).setValue(lockedHash);
    }
  } catch (err) {
    console.error('[onEdit Error]: ' + err.message);
  }
}

// ============================================================================
// 2. HTTP HANDLERS (doGet & doPost) - KOMPATIBEL APK LAMA & BARU
// ============================================================================

function doGet(e) {
  try {
    var params = (e && e.parameter) ? e.parameter : {};
    var action = String(params.action || '').trim();

    if (action === 'verifyUser' || action === 'login') {
      var res = verifyUser_({
        username: params.username || params.nip || '',
        password: params.password || '',
        deviceModel: params.deviceModel || params.device || ''
      });
      return jsonResponse_(res);
    }

    return jsonResponse_({
      status: 'success',
      success: true,
      version: '1.1.4',
      message: 'SecPatrol Backend API v1.1.4 Aktif & Siap Menerima Data'
    });
  } catch (err) {
    console.error('[doGet Error]: ' + err.stack);
    return jsonResponse_({ status: 'error', success: false, message: err.message });
  }
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  var hasLock = false;

  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse_({ status: 'error', success: false, message: 'Payload request kosong.' });
    }

    var data = JSON.parse(e.postData.contents);
    var action = String(data.action || '').trim();

    // Proses Login / Verifikasi User
    if (action === 'verifyUser' || action === 'login') {
      return jsonResponse_(verifyUser_(data));
    }

    // Gunakan ScriptLock untuk penulisan ke Spreadsheet agar bebas tabrakan data
    hasLock = lock.tryLock(20000);
    if (!hasLock) {
      return jsonResponse_({
        status: 'error',
        success: false,
        message: 'Server sedang memproses antrean data lain, silakan coba lagi.'
      });
    }

    var result;
    switch (action) {
      case 'inbound':
        result = saveInbound_(data);
        break;
      case 'handover':
        result = saveHandover_(data);
        break;
      case 'checkpoint':
        result = saveCheckpoint_(data);
        break;
      case 'facility_multi':
      case 'facility':
        result = saveFacilityMulti_(data);
        break;
      case 'genset':
        result = saveGenset_(data);
        break;
      case 'video_patrol':
        result = saveVideoPatrol_(data);
        break;
      default:
        result = { status: 'error', success: false, message: 'Action tidak dikenali: ' + action };
    }

    return jsonResponse_(result);
  } catch (err) {
    console.error('[doPost Error]: ' + (err && err.stack ? err.stack : err));
    return jsonResponse_({
      status: 'error',
      success: false,
      message: 'Terjadi kesalahan pada server: ' + err.message
    });
  } finally {
    if (hasLock) {
      lock.releaseLock();
    }
  }
}

// ============================================================================
// 3. SISTEM KEAMANAN PASSWORD (🔒 TERKUNCI:) & VERIFIKASI USER
// ============================================================================

function isPasswordLocked_(val) {
  var str = String(val || '').trim();
  return str.indexOf('🔒 TERKUNCI:') === 0 || str.indexOf('TERKUNCI:') === 0;
}

/**
 * Menghasilkan hash password satu arah dengan SHA-256 (4096 iterasi + Salt NIP)
 */
function hashUserPassword_(nip, plainPassword) {
  var cleanNip = String(nip || '').trim().toUpperCase();
  var cleanPass = String(plainPassword || '').trim();
  if (!cleanPass) return '';

  if (isPasswordLocked_(cleanPass)) {
    return cleanPass;
  }

  var salt = CONFIG.SALT_PREFIX + cleanNip;
  var digest = Utilities.computeDigest(
    Utilities.DigestAlgorithm.SHA_256,
    salt + ':' + cleanPass,
    Utilities.Charset.UTF_8
  );

  for (var i = 1; i < CONFIG.HASH_ITERATIONS; i++) {
    var combined = digest.concat(Utilities.newBlob(salt).getBytes());
    digest = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, combined);
  }

  var base64Hash = Utilities.base64EncodeWebSafe(digest).replace(/=+$/, '').substring(0, 28);
  return CONFIG.PASSWORD_PREFIX + base64Hash;
}

/**
 * Mengenkripsi semua password plaintext yang ada di DATABASE_USER sekaligus
 */
function encryptAllPlainPasswordsInSheet_(ss) {
  var sheet = ensureIndonesianSheet_(ss, 'DATABASE_USER', SHEET_SCHEMAS.DATABASE_USER);
  var lastRow = sheet.getLastRow();
  if (lastRow < 2) return;

  var range = sheet.getRange(2, 1, lastRow - 1, 2);
  var values = range.getValues();
  var updated = false;

  for (var i = 0; i < values.length; i++) {
    var nip = String(values[i][0] || '').trim();
    var pass = String(values[i][1] || '').trim();
    if (nip && pass && !isPasswordLocked_(pass)) {
      values[i][1] = hashUserPassword_(nip, pass);
      updated = true;
    }
  }

  if (updated) {
    range.setValues(values);
  }
}

/**
 * Verifikasi Login User (Mendukung Password Plaintext Lama & Format 🔒 TERKUNCI:)
 */
function verifyUser_(data) {
  var inputUser = String(data.username || data.nip || '').trim();
  var inputPass = String(data.password || '').trim();
  var deviceModel = String(data.deviceModel || data.device || data.tipeHp || '').trim();

  if (!inputUser || !inputPass) {
    return { status: 'error', success: false, message: 'Username/NIP dan Password wajib diisi.' };
  }

  var ss = getSpreadsheet_();
  var sheet = ensureIndonesianSheet_(ss, 'DATABASE_USER', SHEET_SCHEMAS.DATABASE_USER);
  var rows = sheet.getDataRange().getValues();

  for (var i = 1; i < rows.length; i++) {
    var rowNip = String(rows[i][0] || '').trim();
    var rowPass = String(rows[i][1] || '').trim();
    var fullName = String(rows[i][2] || '').trim();
    var defaultShift = String(rows[i][3] || '').trim();
    var status = String(rows[i][4] || 'AKTIF').trim().toUpperCase();
    var currentDevice = String(rows[i][5] || '').trim();

    if (rowNip.toUpperCase() === inputUser.toUpperCase()) {
      if (status !== 'AKTIF') {
        return {
          status: 'error',
          success: false,
          message: 'Akun Anda berstatus ' + status + '. Silakan hubungi Admin.'
        };
      }

      var expectedHash = hashUserPassword_(rowNip, inputPass);
      var isMatch = false;

      if (isPasswordLocked_(rowPass)) {
        var cleanStored = rowPass.replace(/^🔒\s*/, '');
        var cleanExpected = expectedHash.replace(/^🔒\s*/, '');
        isMatch = (rowPass === expectedHash) || (cleanStored === cleanExpected);
      } else {
        // Jika di sheet masih plaintext, cocokkan lalu langsung ubah menjadi 🔒 TERKUNCI:
        if (rowPass === inputPass) {
          isMatch = true;
          sheet.getRange(i + 1, 2).setValue(expectedHash);
        }
      }

      if (!isMatch) {
        return { status: 'error', success: false, message: 'Password salah.' };
      }

      // Perbarui informasi Tipe Perangkat HP di kolom F jika dikirim dari APK
      if (deviceModel && deviceModel !== currentDevice) {
        sheet.getRange(i + 1, 6).setValue(deviceModel);
        currentDevice = deviceModel;
      }

      return {
        status: 'success',
        success: true,
        message: 'Login Berhasil',
        username: rowNip,
        nip: rowNip,
        fullName: fullName,
        nama: fullName,
        defaultShift: defaultShift,
        shift: defaultShift,
        userStatus: status,
        deviceModel: currentDevice,
        data: {
          username: rowNip,
          fullName: fullName,
          defaultShift: defaultShift,
          status: status,
          deviceModel: currentDevice
        }
      };
    }
  }

  return { status: 'error', success: false, message: 'Username / NIP tidak ditemukan.' };
}

// ============================================================================
// 4. HANDLER LAPORAN OPERASIONAL (7 TAB DATABASE)
// ============================================================================

/**
 * 1. LOGISTIK_INBOUND (Mendukung 1 s/d 3 Foto & Update Status Antrean)
 */
function saveInbound_(data) {
  var timeStr = formatTimestamp_(data.timestamp);
  var nopol = String(data.nopol || '-').trim().toUpperCase();
  var driverName = String(data.driverName || '-').trim();
  var driverPhone = String(data.driverPhone || '-').trim();
  var division = String(data.division || '-').trim();
  var vehicleType = String(data.vehicleType || '-').trim();
  var expedition = String(data.expedition || '-').trim();
  var sealNo = String(data.sealNo || '-').trim();
  var loadStatus = String(data.status || data.loadStatus || '-').trim();
  var officerName = String(data.officerName || data.petugas || '-').trim();
  var note = String(data.note || '-').trim();
  var coordinates = String(data.coordinates || '-').trim();

  var dedupKey = 'INBOUND_' + nopol + '_' + loadStatus + '_' + officerName;
  if (isDuplicateRequest_(dedupKey)) {
    return { status: 'success', success: true, duplicate: true, message: 'Data inbound sudah tercatat.' };
  }

  var ss = getSpreadsheet_();
  var sheet = ensureIndonesianSheet_(ss, 'LOGISTIK_INBOUND', SHEET_SCHEMAS.LOGISTIK_INBOUND);

  var photoLinks = extractAndSavePhotos_(data, 'Logistik_Inbound', 'INBOUND_' + nopol.replace(/\s+/g, ''));
  var queueStatus = String(data.queueStatus || '').trim();
  if (!queueStatus) {
    queueStatus = (loadStatus.toLowerCase().indexOf('selesai') !== -1) ? 'SELESAI' : 'PROSES';
  }

  if (queueStatus === 'SELESAI') {
    markPreviousTruckEntriesDone_(sheet, nopol);
  }

  sheet.appendRow([
    timeStr,
    nopol,
    driverName,
    driverPhone,
    division,
    vehicleType,
    expedition,
    sealNo,
    loadStatus,
    queueStatus,
    officerName,
    note,
    coordinates,
    photoLinks[0] || '-',
    photoLinks[1] || '-',
    photoLinks[2] || '-'
  ]);

  return { status: 'success', success: true, message: 'Data Logistik Inbound berhasil disimpan.' };
}

/**
 * 2. SERAH_TERIMA (Handover Shift)
 */
function saveHandover_(data) {
  var timeStr = formatTimestamp_(data.timestamp);
  var handoverId = String(data.handoverId || data.id || ('HO-' + new Date().getTime())).trim();
  var giver = String(data.giver || data.penyerah || '-').trim();
  var receiver = String(data.receiver || data.penerima || '-').trim();
  var oldShift = String(data.oldShift || data.shiftLama || '-').trim();
  var newShift = String(data.newShift || data.shiftBaru || '-').trim();
  var area = String(data.area || 'Gudang BCP Kediri').trim();
  var faceCount = Number(data.faceCount || data.wajah || 1);

  var dedupKey = 'HANDOVER_' + handoverId + '_' + giver + '_' + receiver;
  if (isDuplicateRequest_(dedupKey)) {
    return { status: 'success', success: true, duplicate: true, message: 'Serah terima sudah tercatat.' };
  }

  var ss = getSpreadsheet_();
  var sheet = ensureIndonesianSheet_(ss, 'SERAH_TERIMA', SHEET_SCHEMAS.SERAH_TERIMA);

  var photoLinks = extractAndSavePhotos_(data, 'Serah_Terima', data.fileName || handoverId);

  sheet.appendRow([
    timeStr,
    handoverId,
    giver,
    receiver,
    oldShift,
    newShift,
    area,
    faceCount,
    photoLinks[0] || '-'
  ]);

  return { status: 'success', success: true, message: 'Data Serah Terima berhasil disimpan.' };
}

/**
 * 3. TITIK_PATROLI (Checkpoint Patroli Pos)
 */
function saveCheckpoint_(data) {
  var timeStr = formatTimestamp_(data.timestamp);
  var checkpointName = String(data.checkpointName || data.pos || '-').trim();
  var officerName = String(data.officerName || data.petugas || '-').trim();
  var note = String(data.note || 'Aman / Terkendali').trim();
  var address = String(data.address || '-').trim();
  var coordinates = String(data.coordinates || '-').trim();

  var dedupKey = 'CHECKPOINT_' + checkpointName + '_' + officerName + '_' + note;
  if (isDuplicateRequest_(dedupKey)) {
    return { status: 'success', success: true, duplicate: true, message: 'Titik patroli sudah tercatat.' };
  }

  var ss = getSpreadsheet_();
  var sheet = ensureIndonesianSheet_(ss, 'TITIK_PATROLI', SHEET_SCHEMAS.TITIK_PATROLI);

  var photoLinks = extractAndSavePhotos_(data, 'Titik_Patroli', data.fileName || ('PATROLI_' + checkpointName));

  sheet.appendRow([
    timeStr,
    checkpointName,
    officerName,
    note,
    address,
    coordinates,
    photoLinks[0] || '-'
  ]);

  return { status: 'success', success: true, message: 'Laporan Titik Patroli berhasil disimpan.' };
}

/**
 * 4. DOKUMENTASI_GUDANG (Mendukung 1 s/d 3 Foto Area/Fasilitas)
 */
function saveFacilityMulti_(data) {
  var timeStr = formatTimestamp_(data.timestamp);
  var reportName = String(data.reportName || data.facilityName || data.area || '-').trim();
  var officerName = String(data.officerName || data.petugas || '-').trim();
  var note = String(data.note || 'Kondisi aman & rapi').trim();
  var address = String(data.address || '-').trim();
  var coordinates = String(data.coordinates || '-').trim();

  var dedupKey = 'FACILITY_' + reportName + '_' + officerName + '_' + note;
  if (isDuplicateRequest_(dedupKey)) {
    return { status: 'success', success: true, duplicate: true, message: 'Dokumentasi gudang sudah tercatat.' };
  }

  var ss = getSpreadsheet_();
  var sheet = ensureIndonesianSheet_(ss, 'DOKUMENTASI_GUDANG', SHEET_SCHEMAS.DOKUMENTASI_GUDANG);

  var photoLinks = extractAndSavePhotos_(data, 'Dokumentasi_Gudang', data.fileName || ('FASILITAS_' + reportName));

  sheet.appendRow([
    timeStr,
    reportName,
    officerName,
    note,
    address,
    coordinates,
    photoLinks[0] || '-',
    photoLinks[1] || '-',
    photoLinks[2] || '-'
  ]);

  return { status: 'success', success: true, message: 'Dokumentasi Gudang berhasil disimpan.' };
}

/**
 * 5. PENGECEKAN_GENSET (Mendukung Foto Genset, Foto HSE, dan Video)
 */
function saveGenset_(data) {
  var timeStr = formatTimestamp_(data.timestamp);
  var statusGenset = String(data.statusGenset || 'Uji Pemanasan (Warming Up Rutin)').trim();
  var oilCheck = String(data.oilCheck || '-').trim();
  var fuelInfo = String(data.fuelInfo || '-').trim();
  var officerName = String(data.officerName || data.petugas || '-').trim();
  var note = String(data.note || 'Kondisi genset normal').trim();
  var address = String(data.address || '-').trim();
  var coordinates = String(data.coordinates || '-').trim();

  var dedupKey = 'GENSET_' + statusGenset + '_' + officerName + '_' + note;
  if (isDuplicateRequest_(dedupKey)) {
    return { status: 'success', success: true, duplicate: true, message: 'Pengecekan genset sudah tercatat.' };
  }

  var ss = getSpreadsheet_();
  var sheet = ensureIndonesianSheet_(ss, 'PENGECEKAN_GENSET', SHEET_SCHEMAS.PENGECEKAN_GENSET);

  var photoLinks = extractAndSavePhotos_(data, 'Pengecekan_Genset', 'GENSET_FOTO');
  var hseLink = '-';
  if (data.hseDocBase64) {
    hseLink = saveBase64FileToDrive_(
      data.hseDocBase64,
      'GENSET_HSE_' + new Date().getTime() + '.jpg',
      'image/jpeg',
      'Pengecekan_Genset'
    );
  }

  var videoLink = '-';
  if (data.videoBase64) {
    videoLink = saveBase64FileToDrive_(
      data.videoBase64,
      'GENSET_VIDEO_' + new Date().getTime() + '.mp4',
      'video/mp4',
      'Pengecekan_Genset'
    );
  }

  sheet.appendRow([
    timeStr,
    statusGenset,
    oilCheck,
    fuelInfo,
    officerName,
    note,
    address,
    coordinates,
    photoLinks[0] || '-',
    hseLink,
    videoLink
  ]);

  return { status: 'success', success: true, message: 'Pengecekan Genset berhasil disimpan.' };
}

/**
 * 6. VIDEO_PATROLI
 */
function saveVideoPatrol_(data) {
  var timeStr = formatTimestamp_(data.timestamp);
  var title = String(data.title || data.fileName || 'Video Keliling Inspeksi Fasilitas').trim();
  var officerName = String(data.officerName || data.petugas || '-').trim();
  var note = String(data.note || 'Patroli keliling fasilitas gudang').trim();
  var address = String(data.address || '-').trim();
  var coordinates = String(data.coordinates || '-').trim();

  var dedupKey = 'VIDPATROL_' + title + '_' + officerName + '_' + coordinates;
  if (isDuplicateRequest_(dedupKey)) {
    return { status: 'success', success: true, duplicate: true, message: 'Video patroli sudah tercatat.' };
  }

  var ss = getSpreadsheet_();
  var sheet = ensureIndonesianSheet_(ss, 'VIDEO_PATROLI', SHEET_SCHEMAS.VIDEO_PATROLI);

  var videoLink = '-';
  if (data.videoBase64) {
    videoLink = saveBase64FileToDrive_(
      data.videoBase64,
      'VIDEO_PATROLI_' + new Date().getTime() + '.mp4',
      'video/mp4',
      'Video_Patroli'
    );
  }

  sheet.appendRow([
    timeStr,
    title,
    officerName,
    note,
    address,
    coordinates,
    videoLink
  ]);

  return { status: 'success', success: true, message: 'Video Patroli berhasil disimpan.' };
}

// ============================================================================
// 5. HELPER FUNCTIONS (AUTO-CLEAN ID/URL, SPREADSHEET, DRIVE, DEDUP CACHE)
// ============================================================================

/**
 * Mengekstrak ID secara otomatis dari link/URL lengkap Google Sheet atau Folder Drive,
 * sekaligus mengoreksi otomatis jika ada kesalahan ketik pola WWm menjadi WwM.
 */
function cleanGoogleId_(rawInput, propKey) {
  var str = String(rawInput || '').trim();
  if (!str || str.indexOf('PASTE_') !== -1 || str.indexOf('MASUKKAN_') !== -1) {
    str = String(SCRIPT_PROP.getProperty(propKey) || '').trim();
  }
  if (!str || str.indexOf('PASTE_') !== -1 || str.indexOf('MASUKKAN_') !== -1) {
    return '';
  }

  // Koreksi otomatis kesalahan ketik huruf besar/kecil yang sering terjadi (WWm -> WwM)
  str = str.replace(/1kWWm9y/g, '1kWwM9y');

  // Ekstrak ID jika yang ditempel adalah URL lengkap Google Spreadsheet / Docs
  var matchDoc = str.match(/\/d\/([a-zA-Z0-9-_]{25,})/);
  if (matchDoc && matchDoc[1]) return matchDoc[1];

  // Ekstrak ID jika yang ditempel adalah URL lengkap Folder Google Drive
  var matchFolder = str.match(/\/folders\/([a-zA-Z0-9-_]{25,})/);
  if (matchFolder && matchFolder[1]) return matchFolder[1];

  var matchParam = str.match(/[?&]id=([a-zA-Z0-9-_]{25,})/);
  if (matchParam && matchParam[1]) return matchParam[1];

  return str.replace(/[^a-zA-Z0-9-_]/g, '');
}

function getSpreadsheet_() {
  var sheetId = cleanGoogleId_(CONFIG.SPREADSHEET_ID, 'SPREADSHEET_ID');
  if (sheetId) {
    return SpreadsheetApp.openById(sheetId);
  }
  var activeSs = SpreadsheetApp.getActiveSpreadsheet();
  if (activeSs) return activeSs;
  throw new Error('Silakan paste Link Lengkap Google Sheet Live Anda pada CONFIG.SPREADSHEET_ID (Baris 15).');
}

function ensureIndonesianSheet_(ss, sheetName, headers) {
  var sheet = ss.getSheetByName(sheetName);
  if (!sheet) {
    sheet = ss.insertSheet(sheetName);
  }

  var lastCol = sheet.getLastColumn();
  if (sheet.getLastRow() === 0 || lastCol === 0) {
    sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
  } else {
    var currentHeaders = sheet.getRange(1, 1, 1, Math.max(lastCol, headers.length)).getValues()[0];
    var needHeaderUpdate = false;
    for (var i = 0; i < headers.length; i++) {
      if (String(currentHeaders[i] || '').trim() !== headers[i]) {
        currentHeaders[i] = headers[i];
        needHeaderUpdate = true;
      }
    }
    if (needHeaderUpdate) {
      sheet.getRange(1, 1, 1, headers.length).setValues([currentHeaders.slice(0, headers.length)]);
    }
  }

  var headerRange = sheet.getRange(1, 1, 1, headers.length);
  headerRange
    .setBackground(CONFIG.HEADER_BG_COLOR)
    .setFontColor(CONFIG.HEADER_TEXT_COLOR)
    .setFontWeight('bold');
  sheet.setFrozenRows(1);

  return sheet;
}

function extractAndSavePhotos_(data, subfolderName, prefixName) {
  var rawList = [];
  if (Array.isArray(data.imagesBase64) && data.imagesBase64.length > 0) {
    rawList = data.imagesBase64.slice(0, 3);
  } else if (data.imageBase64) {
    rawList = [data.imageBase64];
  }

  var links = ['-', '-', '-'];
  var cleanPrefix = String(prefixName || 'FOTO').replace(/[^a-zA-Z0-9_-]/g, '_');

  for (var i = 0; i < rawList.length; i++) {
    if (rawList[i]) {
      var fileName = cleanPrefix + '_' + (i + 1) + '_' + new Date().getTime() + '.jpg';
      links[i] = saveBase64FileToDrive_(rawList[i], fileName, 'image/jpeg', subfolderName);
    }
  }
  return links;
}

function saveBase64FileToDrive_(base64Data, fileName, mimeType, subfolderName) {
  try {
    var cleanBase64 = String(base64Data || '').replace(/^data:[^;]+;base64,/, '');
    if (!cleanBase64) return '-';

    var decoded = Utilities.base64Decode(cleanBase64);
    var blob = Utilities.newBlob(decoded, mimeType || 'image/jpeg', fileName);

    var parentFolder = getOrCreateSubfolder_(subfolderName);
    var file = parentFolder.createFile(blob);
    file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
    return file.getUrl();
  } catch (err) {
    console.error('[saveBase64FileToDrive_ Error]: ' + err.message);
    return '-';
  }
}

function getOrCreateSubfolder_(subfolderName) {
  var rootFolder = null;
  var folderId = cleanGoogleId_(CONFIG.FOLDER_ID, 'FOLDER_ID');

  if (folderId) {
    try {
      rootFolder = DriveApp.getFolderById(folderId);
    } catch (e) {
      rootFolder = null;
    }
  }

  // Fallback cerdas: jika FOLDER_ID belum diisi, gunakan folder induk tempat Spreadsheet berada
  if (!rootFolder) {
    try {
      var ssId = getSpreadsheet_().getId();
      var parents = DriveApp.getFileById(ssId).getParents();
      if (parents.hasNext()) {
        rootFolder = parents.next();
      }
    } catch (e) {
      rootFolder = null;
    }
  }

  if (!rootFolder) {
    rootFolder = DriveApp.getRootFolder();
  }

  if (!subfolderName) return rootFolder;

  var folders = rootFolder.getFoldersByName(subfolderName);
  if (folders.hasNext()) {
    return folders.next();
  }
  return rootFolder.createFolder(subfolderName);
}

function markPreviousTruckEntriesDone_(sheet, nopol) {
  var lastRow = sheet.getLastRow();
  if (lastRow < 2) return;

  var data = sheet.getRange(2, 2, lastRow - 1, 9).getValues();
  var cleanNopol = nopol.replace(/\s+/g, '').toUpperCase();

  for (var i = 0; i < data.length; i++) {
    var rowNopol = String(data[i][0] || '').replace(/\s+/g, '').toUpperCase();
    var rowQueue = String(data[i][8] || '').trim().toUpperCase();
    if (rowNopol === cleanNopol && rowQueue !== 'SELESAI') {
      sheet.getRange(i + 2, 10).setValue('SELESAI');
    }
  }
}

function isDuplicateRequest_(rawKey) {
  try {
    var cache = CacheService.getScriptCache();
    var digest = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, rawKey);
    var keyHash = 'DEDUP_' + Utilities.base64EncodeWebSafe(digest).substring(0, 24);
    if (cache.get(keyHash)) {
      return true;
    }
    cache.put(keyHash, '1', CONFIG.DEDUP_TTL_SECONDS);
    return false;
  } catch (err) {
    return false;
  }
}

function formatTimestamp_(ts) {
  if (ts && String(ts).trim() !== '') {
    return String(ts).trim();
  }
  return Utilities.formatDate(new Date(), CONFIG.TIMEZONE, 'dd/MM/yyyy HH:mm:ss');
}

function jsonResponse_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
