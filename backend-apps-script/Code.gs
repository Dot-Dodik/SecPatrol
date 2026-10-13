/**
 * SecPatrol Warehouse Backend Script (v1.1.0 STABLE)
 * Database: Google Spreadsheet & Google Drive Folder ID
 */

const CONFIG = {
  SPREADSHEET_ID: "1pu7vAQTzSppiPtFqDYIxebcKXk1kWWm9yxnQrWOCaS0",
  FOLDER_ID: "1uhHx-r31TW2ms0d8Ion4oJ514MEw_CG1"
};

// ==========================================
// 1. FUNGSI CEK OTORISASI (JALANKAN 1x DI EDITOR)
// ==========================================
function testAuth() {
  var ss = getSpreadsheet();
  Logger.log("Koneksi Spreadsheet Berhasil: " + ss.getName());
  var sheetUser = getOrCreateSheet(ss, "DATABASE_USER", [
    "Username / NIP", "Password", "Nama Lengkap", "Shift Default", "Status"
  ]);
  Logger.log("Sheet DATABASE_USER Siap: " + sheetUser.getName());
  var sheetInbound = getOrCreateSheet(ss, "LOGISTIK_INBOUND", [
    "Timestamp", "No Polisi", "Nama Driver", "No HP/WA", 
    "Divisi", "Jenis Kendaraan", "Ekspedisi", "No Segel", 
    "Status Muatan", "Status Antrean", "Petugas", "Catatan", "Koordinat", "Foto URL"
  ]);
  Logger.log("Sheet LOGISTIK_INBOUND Siap: " + sheetInbound.getName());
}

// ==========================================
// 2. GET HANDLER (PING, LOGIN, & ANTREAN TRUK)
// ==========================================
function doGet(e) {
  try {
    var action = (e && e.parameter) ? e.parameter.action : "";

    // A. TES KONEKSI & PING SERVER
    if (action === "ping" || !action) {
      return ContentService.createTextOutput(JSON.stringify({
        status: "OK",
        message: "Server SecPatrol BCP Kediri aktif & siap!"
      })).setMimeType(ContentService.MimeType.JSON);
    }

    // Login is accepted only through POST so passwords do not appear in URL history/logs.
    // This remains a pilot-grade check, not production authorization; see docs/SECURITY.md.

    // B. ANTREAN INBOUND (FILTER KOLOM J STATUS ANTREAN)
    if (action === "getActiveInboundQueue") {
      var cache = CacheService.getScriptCache();
      var cachedData = cache.get("ACTIVE_INBOUND_CACHE");
      if (cachedData != null) {
        return ContentService.createTextOutput(cachedData).setMimeType(ContentService.MimeType.JSON);
      }

      var ss = getSpreadsheet();
      var sheet = ss.getSheetByName("LOGISTIK_INBOUND");
      if (!sheet) {
        return ContentService.createTextOutput(JSON.stringify({ status: "success", data: [] }))
          .setMimeType(ContentService.MimeType.JSON);
      }

      var lastRow = sheet.getLastRow();
      if (lastRow <= 1) {
        return ContentService.createTextOutput(JSON.stringify({ status: "success", data: [] }))
          .setMimeType(ContentService.MimeType.JSON);
      }

      var startRow = 2;
      var numRows = lastRow - 1;
      // Membaca seluruh histori agar truk outstanding lama tidak hilang setelah 60 catatan baru.
      var data = sheet.getRange(startRow, 1, numRows, 10).getValues();

      var activeMap = {};
      for (var i = 0; i < data.length; i++) {
        var row = data[i];
        var nopolRaw = String(row[1] || "").trim().toUpperCase();       // Kolom B: Nopol
        var statusMuatan = String(row[8] || "").trim();                 // Kolom I: Status Muatan
        var statusAntrean = String(row[9] || "").trim().toUpperCase();  // Kolom J: Status Antrean

        if (!nopolRaw || nopolRaw === "TIBA") continue;

        var nopolKey = nopolRaw.replace(/\s+/g, "");

        // KONTROL SISTEM & ADMIN:
        // Jika Kolom J berisi SELESAI, armada otomatis hilang dari dropdown HP
        if (statusAntrean === "SELESAI" || statusMuatan.indexOf("Selesai") !== -1) {
          delete activeMap[nopolKey];
        } else if (statusAntrean === "OUTSTANDING" || statusAntrean === "") {
          activeMap[nopolKey] = {
            nopol: nopolRaw,
            driverName: String(row[2] || "-"),
            driverPhone: String(row[3] || "-"),
            division: String(row[4] || "-"),
            vehicleType: String(row[5] || "-"),
            expedition: String(row[6] || "-"),
            sealNo: String(row[7] || "-"),
            status: statusMuatan
          };
        }
      }

      var resultList = [];
      for (var key in activeMap) {
        resultList.push(activeMap[key]);
      }

      var jsonOutput = JSON.stringify({ status: "success", data: resultList });
      cache.put("ACTIVE_INBOUND_CACHE", jsonOutput, 15); // Cache RAM singkat (15 detik)

      return ContentService.createTextOutput(jsonOutput).setMimeType(ContentService.MimeType.JSON);
    }

    return ContentService.createTextOutput(JSON.stringify({ status: "online", timestamp: new Date() }))
      .setMimeType(ContentService.MimeType.JSON);

  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({
      status: "error",
      message: "Permintaan gagal diproses. Periksa konfigurasi backend dan log admin."
    })).setMimeType(ContentService.MimeType.JSON);
  }
}

// ==========================================
// 3. POST HANDLER (PENYIMPANAN FORM LAPORAN)
// ==========================================
function doPost(e) {
  try {
    var contents = JSON.parse(e.postData.contents);
    var action = contents.action;
    if (action === "verifyUser") return verifyUser(contents);
    var ss = getSpreadsheet();

    // A. INBOUND TRUCK
    if (action === "inbound") {
      var sheet = getOrCreateSheet(ss, "LOGISTIK_INBOUND", [
        "Timestamp", "No Polisi", "Nama Driver", "No HP/WA", 
        "Divisi", "Jenis Kendaraan", "Ekspedisi", "No Segel", 
        "Status Muatan", "Status Antrean", "Petugas", "Catatan", "Koordinat", "Foto URL"
      ]);
      
      var fileUrl = contents.imageBase64 ? saveFileToDrive(contents.imageBase64, contents.fileName || "Truck.jpg", "Inbound_Truck") : "-";
      
      var statusText = String(contents.status || "");
      var isSelesai = statusText.indexOf("Selesai") !== -1;
      var statusAntrean = isSelesai ? "SELESAI" : "OUTSTANDING";

      sheet.appendRow(safeRow([
        contents.timestamp || new Date(), // A: Waktu
        contents.nopol,                   // B: Nopol
        contents.driverName,              // C: Nama Sopir
        contents.driverPhone,             // D: No HP Driver
        contents.division,                // E: Divisi
        contents.vehicleType,             // F: Jenis Armada
        contents.expedition,              // G: Ekspedisi
        contents.sealNo,                  // H: No Segel
        contents.status,                  // I: Status Muatan
        statusAntrean,                    // J: Status Antrean (OUTSTANDING / SELESAI)
        contents.officerName,             // K: Petugas
        contents.note,                    // L: Catatan
        contents.coordinates,             // M: Koordinat GPS
        fileUrl                           // N: Foto Drive
      ]));

      // JIKA SELESAI: Otomatis ubah status baris lama nopol ini menjadi SELESAI
      if (isSelesai) {
        var lastRow = sheet.getLastRow();
        if (lastRow > 2) {
          var targetNopol = String(contents.nopol || "").trim().toUpperCase().replace(/\s+/g, "");
          var nopolRange = sheet.getRange(2, 2, lastRow - 1, 1).getValues(); // Baca Kolom B
          for (var r = 0; r < nopolRange.length; r++) {
            var platBaris = String(nopolRange[r][0] || "").trim().toUpperCase().replace(/\s+/g, "");
            if (platBaris === targetNopol) {
              sheet.getRange(r + 2, 10).setValue("SELESAI"); // Set Kolom J menjadi SELESAI
            }
          }
        }
      }

      CacheService.getScriptCache().remove("ACTIVE_INBOUND_CACHE");
      return responseSuccess({ message: "Inbound berhasil disimpan", fileUrl: fileUrl });
    }

    // B. TITIK PATROLI (CHECKPOINT)
    if (action === "checkpoint") {
      var sheet = getOrCreateSheet(ss, "TITIK_PATROLI", [
        "Timestamp", "Nama Titik / Pos", "Petugas", "Catatan", "Alamat Geotag", "Koordinat", "Foto URL"
      ]);
      var fileUrl = saveFileToDrive(contents.imageBase64, contents.fileName || "Pos.jpg", "Patrol_Checkpoints");
      sheet.appendRow(safeRow([
        contents.timestamp || new Date(),
        contents.checkpointName,
        contents.officerName,
        contents.note,
        contents.address,
        contents.coordinates,
        fileUrl
      ]));
      return responseSuccess({ message: "Pos patroli tercatat", fileUrl: fileUrl });
    }

    // C. DOKUMENTASI FASILITAS & GUDANG
    if (action === "facility_multi") {
      var sheet = getOrCreateSheet(ss, "DOKUMENTASI_GUDANG", [
        "Timestamp", "Area / Fasilitas", "Petugas", "Catatan", "Alamat", "Koordinat", "Foto 1", "Foto 2", "Foto 3"
      ]);
      var urls = [];
      if (contents.imagesBase64 && contents.imagesBase64.length > 0) {
        for (var i = 0; i < contents.imagesBase64.length; i++) {
          urls.push(saveFileToDrive(contents.imagesBase64[i], "Fasilitas_" + (i + 1) + ".jpg", "Facility_Reports"));
        }
      }
      sheet.appendRow(safeRow([
        contents.timestamp || new Date(),
        contents.reportName,
        contents.officerName,
        contents.note,
        contents.address,
        contents.coordinates,
        urls[0] || "-",
        urls[1] || "-",
        urls[2] || "-"
      ]));
      return responseSuccess({ message: "Dokumentasi fasilitas tercatat" });
    }

    // D. PENGECEKAN GENSET & PANEL
    if (action === "genset") {
      var sheet = getOrCreateSheet(ss, "PENGECEKAN_GENSET", [
        "Timestamp", "Status Genset", "Level Oli", "Bahan Bakar & Voltase", 
        "Petugas", "Catatan", "Alamat", "Koordinat", "Foto Fisik", "Foto Dokumen HSE", "Video URL"
      ]);
      var fotoFisik = contents.imageBase64 ? saveFileToDrive(contents.imageBase64, "Genset_Fisik.jpg", "Genset") : "-";
      var fotoHse = contents.hseDocBase64 ? saveFileToDrive(contents.hseDocBase64, "Genset_HSE.jpg", "Genset") : "-";
      var videoUrl = contents.videoBase64 ? saveFileToDrive(contents.videoBase64, "Genset_Video.mp4", "Genset_Video") : "-";

      sheet.appendRow(safeRow([
        contents.timestamp || new Date(),
        contents.statusGenset,
        contents.oilCheck,
        contents.fuelInfo,
        contents.officerName,
        contents.note,
        contents.address,
        contents.coordinates,
        fotoFisik,
        fotoHse,
        videoUrl
      ]));
      return responseSuccess({ message: "Inspeksi genset tercatat" });
    }

    // E. SERAH TERIMA SHIFT (HANDOVER)
    if (action === "handover") {
      var sheet = getOrCreateSheet(ss, "SERAH_TERIMA", [
        "Timestamp", "Kode Serah-Terima", "Petugas Penyerah", "Petugas Penerima", "Shift Lama", "Shift Baru", "Area", "Foto Selfie URL"
      ]);
      var selfieUrl = contents.imageBase64 ? saveFileToDrive(contents.imageBase64, "Selfie_HO.jpg", "Handover_Selfie") : "-";
      sheet.appendRow(safeRow([
        contents.timestamp || new Date(),
        contents.handoverId,
        contents.giver,
        contents.receiver,
        contents.oldShift,
        contents.newShift,
        contents.area,
        selfieUrl
      ]));
      return responseSuccess({ message: "Serah terima berhasil disimpan" });
    }

    // F. REKAM VIDEO PATROLI
    if (action === "video_patrol") {
      var sheet = getOrCreateSheet(ss, "VIDEO_PATROLI", [
        "Timestamp", "Judul Inspeksi", "Petugas", "Catatan", "Koordinat", "Video URL"
      ]);
      var vUrl = contents.videoBase64 ? saveFileToDrive(contents.videoBase64, "Patroli_Video.mp4", "Video_Patroli") : "-";
      sheet.appendRow(safeRow([
        contents.timestamp || new Date(),
        contents.title,
        contents.officerName,
        contents.note,
        contents.coordinates,
        vUrl
      ]));
      return responseSuccess({ message: "Video patroli tersimpan" });
    }

    return responseError("Aksi tidak dikenali");
  } catch (err) {
    return responseError("Permintaan gagal diproses. Periksa data dan konfigurasi backend.");
  }
}

function verifyUser(contents) {
  var username = String(contents.username || "").trim().toUpperCase();
  var password = String(contents.password || "");
  if (!username || !password) return responseError("Username dan password wajib diisi.");

  var ss = getSpreadsheet();
  var sheet = getOrCreateSheet(ss, "DATABASE_USER", [
    "Username / NIP", "Password", "Nama Lengkap", "Shift Default", "Status"
  ]);
  var data = sheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    var rowUser = String(data[i][0] || "").trim().toUpperCase();
    if (rowUser !== username) continue;
    var rowPass = String(data[i][1] || "");
    var rowStatus = String(data[i][4] || "AKTIF").trim().toUpperCase();
    if (rowPass === password && rowStatus === "AKTIF") {
      return ContentService.createTextOutput(JSON.stringify({
        status: "success",
        user: {
          id: rowUser,
          name: String(data[i][2] || "").trim(),
          shift: String(data[i][3] || "Shift Pagi").trim()
        }
      })).setMimeType(ContentService.MimeType.JSON);
    }
    break;
  }
  return responseError("Kredensial tidak valid atau akun tidak aktif.");
}

// ==========================================
// 4. FUNGSI PENDUKUNG GOOGLE DRIVE & GSHEET
// ==========================================
function getSpreadsheet() {
  try {
    var active = SpreadsheetApp.getActiveSpreadsheet();
    if (active) return active;
  } catch (e) {}

  var cleanId = String(CONFIG.SPREADSHEET_ID || "").trim().replace(/\s+/g, "");
  try {
    if (cleanId) return SpreadsheetApp.openById(cleanId);
  } catch (errId) {
    try {
      return SpreadsheetApp.openByUrl("https://docs.google.com/spreadsheets/d/" + cleanId + "/edit");
    } catch (errUrl) {
      throw new Error("Gsheet gagal dibuka (" + errId.message + "). Pastikan script ini dibuka dari 'Ekstensi > Apps Script' di Google Sheet Database_SecPatrol_Kediri!");
    }
  }
}

function getOrCreateSheet(ss, name, headers) {
  var sheet = ss.getSheetByName(name);
  if (!sheet) {
    sheet = ss.insertSheet(name);
    sheet.appendRow(headers);
    sheet.setFrozenRows(1);
    sheet.getRange(1, 1, 1, headers.length).setFontWeight("bold").setBackground("#E0E0E0");
  }
  return sheet;
}

function saveFileToDrive(base64Data, filename, subfolderName) {
  try {
    if (!base64Data || String(base64Data).length > 18000000) {
      throw new Error("Payload file tidak valid atau terlalu besar");
    }
    var parentFolder = DriveApp.getFolderById(CONFIG.FOLDER_ID);
    var targetFolder = parentFolder;

    if (subfolderName) {
      var subFolders = parentFolder.getFoldersByName(subfolderName);
      targetFolder = subFolders.hasNext() ? subFolders.next() : parentFolder.createFolder(subfolderName);
    }

    var decoded = Utilities.base64Decode(base64Data);
    var safeFilename = String(filename || "upload.jpg").replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 100);
    var blob = Utilities.newBlob(decoded, safeFilename.endsWith(".mp4") ? "video/mp4" : "image/jpeg", safeFilename);
    var file = targetFolder.createFile(blob);
    return file.getUrl();
  } catch (e) {
    throw new Error("Unggahan bukti gagal disimpan");
  }
}

function responseSuccess(data) {
  data.status = "success";
  return ContentService.createTextOutput(JSON.stringify(data)).setMimeType(ContentService.MimeType.JSON);
}

function responseError(msg) {
  return ContentService.createTextOutput(JSON.stringify({ status: "error", message: msg })).setMimeType(ContentService.MimeType.JSON);
}

// Prevent untrusted form text from being interpreted as a spreadsheet formula.
function safeRow(values) {
  return values.map(function(value) {
    if (typeof value !== "string") return value;
    return /^[\s]*[=+\-@]/.test(value) ? "'" + value : value;
  });
}
