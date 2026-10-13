package com.bcpkediri.securitypatrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Uploader {

    private fun encodeFileToBase64(file: File): String {
        return try {
            if (!file.exists()) return ""

            if (file.name.endsWith(".mp4", ignoreCase = true)) {
                return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            }

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            var sampleSize = 1
            while (options.outWidth / sampleSize > 1280 || options.outHeight / sampleSize > 1280) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                ?: return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

            val outputStream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val compressedBytes = outputStream.toByteArray()
            Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            try {
                if (file.exists()) Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) else ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun getEndpointUrl(context: Context): String {
        val sp = context.getSharedPreferences("secpatrol_settings", Context.MODE_PRIVATE)
        val saved = sp.getString("endpoint_url", "") ?: ""
        val raw = saved.ifBlank { BuildConfig.APPS_SCRIPT_URL }
        return raw.trim().replace(" ", "").replace("\n", "").replace("\r", "")
    }

    fun setEndpointUrl(context: Context, url: String) {
        val sp = context.getSharedPreferences("secpatrol_settings", Context.MODE_PRIVATE)
        val clean = url.trim().replace(" ", "").replace("\n", "").replace("\r", "")
        sp.edit().putString("endpoint_url", clean).apply()
    }

    suspend fun testEndpoint(testUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = testUrl.trim().replace(" ", "").replace("\n", "").replace("\r", "")
        if (cleanUrl.isBlank()) return@withContext Pair(false, "URL tidak boleh kosong!")
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext Pair(false, "Format URL harus diawali https://")
        }

        return@withContext try {
            val pingUrl = if (cleanUrl.contains("?")) "$cleanUrl&action=ping" else "$cleanUrl?action=ping"
            var conn = (URL(pingUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            var redirectCount = 0
            while ((conn.responseCode in 301..307) && redirectCount < 4) {
                val newLocation = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = (URL(newLocation).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                redirectCount++
            }

            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (body.contains("Maaf file yang Anda minta tidak ada") || body.contains("Google Drive – Pemberitahuan")) {
                    Pair(false, "Gagal: URL salah atau file Script tidak ada di Google Drive.")
                } else if (body.contains("\"status\"") || body.contains("SecPatrol") || body.contains("OK")) {
                    Pair(true, "Berhasil! Server Apps Script terhubung dan aktif merespons.")
                } else {
                    Pair(true, "Terhubung (HTTP 200).")
                }
            } else {
                Pair(false, "Gagal terhubung! Kode respon HTTP: $code")
            }
        } catch (e: Exception) {
            Pair(false, "Gagal koneksi: ${e.message ?: "Jaringan tidak dapat dijangkau"}")
        }
    }

    suspend fun verifyUserRegistration(
        context: Context,
        username: String,
        passwordInput: String
    ): Triple<Boolean, String, Pair<String, String>?> = withContext(Dispatchers.IO) {
        val endpoint = getEndpointUrl(context)
        if (endpoint.isBlank()) return@withContext Triple(false, "URL endpoint belum diatur di Pengaturan", null)

        if (!LocationUtil.checkNetworkAvailable(context)) {
            val spUser = context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE)
            val cachedUser = spUser.getString("officer_id", "") ?: ""
            return@withContext if (cachedUser.equals(username.trim(), ignoreCase = true)) {
                Triple(false, "Perangkat offline. Sesi aktif yang tersimpan tetap dapat digunakan; login ulang memerlukan koneksi internet.", null)
            } else Triple(false, "Login baru memerlukan koneksi internet.", null)
        }

        return@withContext try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                outputStream.use {
                    it.write(JSONObject()
                        .put("action", "verifyUser")
                        .put("username", username.trim().uppercase())
                        .put("password", passwordInput)
                        .toString()
                        .toByteArray(Charsets.UTF_8))
                }
            }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val trimmed = resp.trim()

                if (trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)) {
                    return@withContext Triple(false, "Backend menolak akses atau mengembalikan respons yang tidak valid. Periksa izin deployment.", null)
                }

                val json = JSONObject(resp)
                if (json.optString("status") == "success") {
                    val u = json.getJSONObject("user")
                    val name = u.optString("name")
                    val shift = u.optString("shift", "Shift Pagi")
                    Triple(true, "Terdaftar", Pair(name, shift))
                } else {
                    Triple(false, json.optString("message", "Username atau password salah"), null)
                }
            } else {
                Triple(false, "Server merespons: HTTP ${conn.responseCode}", null)
            }
        } catch (e: Exception) {
            Triple(false, "Gagal koneksi: ${e.message}", null)
        }
    }

    // CEK STATUS REAL-TIME STATUS SCAN QR OLEH REKAN PENERIMA
    suspend fun checkHandoverStatus(context: Context, handoverId: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = getEndpointUrl(context)
        if (endpoint.isBlank() || !LocationUtil.checkNetworkAvailable(context)) return@withContext false

        return@withContext try {
            val encId = URLEncoder.encode(handoverId.trim(), "UTF-8")
            val url = URL("$endpoint?action=checkHandoverStatus&handoverId=$encId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                json.optString("status") == "completed"
            } else false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchActiveInboundQueue(context: Context): List<InboundQueueItem> = withContext(Dispatchers.IO) {
        val endpoint = getEndpointUrl(context)
        if (endpoint.isBlank() || !LocationUtil.checkNetworkAvailable(context)) return@withContext emptyList()

        return@withContext try {
            val url = URL("$endpoint?action=getActiveInboundQueue")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                if (response.trim().startsWith("<")) return@withContext emptyList()

                val json = JSONObject(response)
                val list = mutableListOf<InboundQueueItem>()
                val arr = json.optJSONArray("data") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        InboundQueueItem(
                            nopol = o.optString("nopol"),
                            driverName = o.optString("driverName"),
                            driverPhone = o.optString("driverPhone"),
                            division = o.optString("division"),
                            vehicleType = o.optString("vehicleType"),
                            expedition = o.optString("expedition"),
                            sealNo = o.optString("sealNo"),
                            status = o.optString("status")
                        )
                    )
                }
                list
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun send(context: Context, handover: Handover, file: File, faceCount: Int = 1): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val b64 = encodeFileToBase64(file)
            val json = JSONObject().apply {
                put("action", "handover")
                put("handoverId", handover.id)
                put("giver", handover.giver)
                put("receiver", handover.receiver)
                put("oldShift", handover.oldShift)
                put("newShift", handover.newShift)
                put("area", handover.area)
                put("faceCount", faceCount)
                put("fileName", file.name)
                put("imageBase64", b64)
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal kirim serah-terima")
        }
    }

    suspend fun sendCheckpoint(
        context: Context,
        checkpointName: String,
        officer: String,
        note: String,
        address: String,
        coords: String,
        file: File
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val b64 = encodeFileToBase64(file)
            val json = JSONObject().apply {
                put("action", "checkpoint")
                put("checkpointName", checkpointName)
                put("officerName", officer)
                put("note", note)
                put("address", address)
                put("coordinates", coords)
                put("fileName", file.name)
                put("imageBase64", b64)
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal")
        }
    }

    suspend fun sendFacilityMulti(
        context: Context,
        reportName: String,
        officer: String,
        note: String,
        address: String,
        coords: String,
        files: List<File>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            files.forEach { f ->
                if (f.exists()) arr.put(encodeFileToBase64(f))
            }
            val json = JSONObject().apply {
                put("action", "facility_multi")
                put("reportName", reportName)
                put("officerName", officer)
                put("note", note)
                put("address", address)
                put("coordinates", coords)
                put("imagesBase64", arr)
                if (files.isNotEmpty()) {
                    put("imageBase64", encodeFileToBase64(files[0]))
                    put("fileName", files[0].name)
                }
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal")
        }
    }

    suspend fun sendInbound(
        context: Context,
        nopol: String,
        driverName: String,
        driverPhone: String,
        division: String,
        vehicleType: String,
        expedition: String,
        sealNo: String,
        status: String,
        officer: String,
        note: String,
        coords: String,
        files: List<File>
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            files.forEach { f ->
                if (f.exists()) arr.put(encodeFileToBase64(f))
            }
            val json = JSONObject().apply {
                put("action", "inbound")
                put("nopol", nopol)
                put("driverName", driverName)
                put("driverPhone", driverPhone)
                put("division", division)
                put("vehicleType", vehicleType)
                put("expedition", expedition)
                put("sealNo", sealNo)
                put("status", status)
                put("officerName", officer)
                put("note", note)
                put("coordinates", coords)
                put("imagesBase64", arr)
                if (files.isNotEmpty()) {
                    put("imageBase64", encodeFileToBase64(files[0]))
                    put("fileName", files[0].name)
                }
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal")
        }
    }

    suspend fun sendGensetCheck(
        context: Context,
        statusGenset: String,
        oilCheck: String,
        fuelInfo: String,
        officer: String,
        note: String,
        address: String,
        coords: String,
        photoFisik: File,
        photoHse: File?,
        videoFile: File?
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val b64Fisik = encodeFileToBase64(photoFisik)
            val b64Hse = if (photoHse != null && photoHse.exists()) encodeFileToBase64(photoHse) else null
            val b64Video = if (videoFile != null && videoFile.exists()) encodeFileToBase64(videoFile) else null

            val json = JSONObject().apply {
                put("action", "genset")
                put("statusGenset", statusGenset)
                put("oilCheck", oilCheck)
                put("fuelInfo", fuelInfo)
                put("officerName", officer)
                put("note", note)
                put("address", address)
                put("coordinates", coords)
                put("imageBase64", b64Fisik)
                if (b64Hse != null) put("hseDocBase64", b64Hse as String)
                if (b64Video != null) put("videoBase64", b64Video as String)
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal")
        }
    }

    suspend fun sendVideoPatrol(
        context: Context,
        title: String,
        officer: String,
        note: String,
        coords: String,
        videoFile: File
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val b64Video = encodeFileToBase64(videoFile)
            val json = JSONObject().apply {
                put("action", "video_patrol")
                put("title", title)
                put("officerName", officer)
                put("note", note)
                put("coordinates", coords)
                put("videoBase64", b64Video)
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal")
        }
    }

    private fun postJson(urlString: String, json: JSONObject): Pair<Boolean, String> {
        val cleanUrl = urlString.trim().replace(" ", "").replace("\n", "").replace("\r", "")
        if (cleanUrl.isBlank()) return Pair(false, "URL kosong")

        return try {
            var conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = 40000
                readTimeout = 40000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            conn.outputStream.use { os ->
                os.write(json.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            var code = conn.responseCode

            var redirectCount = 0
            while ((code in 301..307) && redirectCount < 4) {
                val newLocation = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = (URL(newLocation).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 30000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                code = conn.responseCode
                redirectCount++
            }

            if (code in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                Pair(true, resp)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                Pair(false, err)
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal koneksi")
        }
    }
}
