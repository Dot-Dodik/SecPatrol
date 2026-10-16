package com.bcpkediri.securitypatrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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

    /**
     * Menghasilkan format persis seperti:
     * - vivo V2109 (Android 13)
     * - Xiaomi 22021211RG (Android 14)
     * - OPPO CPH1933 (Android 11)
     */
    fun getDeviceHardwareInfo(): String {
        val manufacturer = (Build.MANUFACTURER ?: "").trim()
        val model = (Build.MODEL ?: "").trim()
        val combined = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
        return "$combined (Android ${Build.VERSION.RELEASE})"
    }

    private fun buildIdempotencyKey(file: File): String = "IDEMP_${file.name}_${file.length()}"

    private fun getSessionToken(context: Context): String {
        val spUser = context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE)
        return spUser.getString("session_token", "") ?: ""
    }

    private fun getOfficerId(context: Context): String {
        val spUser = context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE)
        return spUser.getString("officer_id", "") ?: ""
    }

    fun getCachedOfficerNames(context: Context): List<String> {
        val spUser = context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE)
        val raw = spUser.getString("registered_officers_json", "[]") ?: "[]"
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.get(i)
                val name = when (item) {
                    is JSONObject -> item.optString("name", "").trim()
                    is String -> item.trim()
                    else -> ""
                }
                if (name.isNotBlank() && !list.contains(name)) {
                    list.add(name)
                }
            }
        } catch (_: Exception) {}

        val currentOfficer = (spUser.getString("officer_name", "") ?: "").trim()
        if (currentOfficer.isNotBlank() && !list.contains(currentOfficer)) {
            list.add(0, currentOfficer)
        }
        if (list.isEmpty()) {
            list.addAll(listOf("EKO SISWOYO", "ANDRE DWI CAHYONO", "ALEX PERDANA", "TESTAROSA"))
        }
        return list
    }

    private fun saveCachedOfficerNames(context: Context, arr: JSONArray?) {
        if (arr == null || arr.length() == 0) return
        context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE)
            .edit()
            .putString("registered_officers_json", arr.toString())
            .commit()
    }

    suspend fun fetchActiveOfficers(context: Context): List<String> = withContext(Dispatchers.IO) {
        val endpoint = getEndpointUrl(context)
        if (endpoint.isBlank() || !LocationUtil.checkNetworkAvailable(context)) {
            return@withContext getCachedOfficerNames(context)
        }
        return@withContext try {
            val urlStr = if (endpoint.contains("?")) "$endpoint&action=getActiveOfficers" else "$endpoint?action=getActiveOfficers"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                if (!resp.startsWith("<")) {
                    val json = JSONObject(resp)
                    val arr = json.optJSONArray("officers")
                    if (arr != null && arr.length() > 0) {
                        saveCachedOfficerNames(context, arr)
                    }
                }
            }
            getCachedOfficerNames(context)
        } catch (_: Exception) {
            getCachedOfficerNames(context)
        }
    }

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
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            try {
                if (file.exists()) Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) else ""
            } catch (_: Exception) { "" }
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
        sp.edit().putString("endpoint_url", clean).commit()
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
                } else {
                    Pair(true, "Berhasil! Server Apps Script terhubung dan aktif merespons.")
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
            return@withContext Triple(false, "Login memerlukan koneksi internet untuk memverifikasi status aktif akun.", null)
        }

        val cleanUser = username.trim().uppercase()
        val devModel = getDeviceHardwareInfo()

        return@withContext try {
            val payload = JSONObject()
                .put("action", "verifyUser")
                .put("username", cleanUser)
                .put("password", passwordInput)
                .put("device", devModel)
                .put("deviceModel", devModel)

            val res = postJson(endpoint, payload)
            if (res.first) {
                val trimmed = res.second.trim()
                if (!trimmed.startsWith("<") && !trimmed.contains("<!DOCTYPE", ignoreCase = true)) {
                    val json = JSONObject(trimmed)
                    if (json.optString("status") == "success") {
                        val u = json.getJSONObject("user")
                        val name = u.optString("name")
                        val shift = u.optString("shift", "Shift Pagi")
                        val sessionToken = json.optString("sessionToken", "")
                        saveCachedOfficerNames(context, json.optJSONArray("officers"))
                        context.getSharedPreferences("secpatrol_user", Context.MODE_PRIVATE)
                            .edit()
                            .putString("session_token", sessionToken)
                            .putString("officer_device", devModel)
                            .commit()
                        return@withContext Triple(true, "Terdaftar", Pair(name, shift))
                    } else {
                        return@withContext Triple(false, json.optString("message", "Akun tidak aktif atau password salah"), null)
                    }
                }
            }
            Triple(false, res.second.ifBlank { "Username/password salah atau akun belum aktif" }, null)
        } catch (e: Exception) {
            Triple(false, "Gagal koneksi: ${e.message}", null)
        }
    }

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
                JSONObject(resp).optString("status") == "completed"
            } else false
        } catch (_: Exception) { false }
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
                val arr = JSONObject(response).optJSONArray("data") ?: JSONArray()
                val list = mutableListOf<InboundQueueItem>()
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
        } catch (_: Exception) { emptyList() }
    }

    suspend fun send(context: Context, handover: Handover, file: File, faceCount: Int = 1): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val devInfo = getDeviceHardwareInfo()
            val json = JSONObject().apply {
                put("action", "handover")
                put("sessionToken", getSessionToken(context))
                put("officerId", getOfficerId(context))
                put("device", devInfo)
                put("deviceModel", devInfo)
                put("idempotencyKey", buildIdempotencyKey(file))
                put("handoverId", handover.id)
                put("giver", handover.giver)
                put("receiver", handover.receiver)
                put("oldShift", handover.oldShift)
                put("newShift", handover.newShift)
                put("area", handover.area)
                put("faceCount", faceCount)
                put("fileName", file.name)
                put("imageBase64", encodeFileToBase64(file))
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) { Pair(false, e.message ?: "Gagal kirim serah-terima") }
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
            val devInfo = getDeviceHardwareInfo()
            val json = JSONObject().apply {
                put("action", "checkpoint")
                put("sessionToken", getSessionToken(context))
                put("officerId", getOfficerId(context))
                put("device", devInfo)
                put("deviceModel", devInfo)
                put("idempotencyKey", buildIdempotencyKey(file))
                put("checkpointName", checkpointName)
                put("officerName", officer)
                put("note", note)
                put("address", address)
                put("coordinates", coords)
                put("fileName", file.name)
                put("imageBase64", encodeFileToBase64(file))
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) { Pair(false, e.message ?: "Gagal") }
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
            val devInfo = getDeviceHardwareInfo()
            val arr = JSONArray()
            files.forEach { if (it.exists()) arr.put(encodeFileToBase64(it)) }
            val firstFile = files.firstOrNull()
            val json = JSONObject().apply {
                put("action", "facility_multi")
                put("sessionToken", getSessionToken(context))
                put("officerId", getOfficerId(context))
                put("device", devInfo)
                put("deviceModel", devInfo)
                if (firstFile != null) put("idempotencyKey", buildIdempotencyKey(firstFile))
                put("reportName", reportName)
                put("officerName", officer)
                put("note", note)
                put("address", address)
                put("coordinates", coords)
                put("imagesBase64", arr)
                if (firstFile != null) {
                    put("imageBase64", encodeFileToBase64(firstFile))
                    put("fileName", firstFile.name)
                }
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) { Pair(false, e.message ?: "Gagal") }
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
            val devInfo = getDeviceHardwareInfo()
            val arr = JSONArray()
            files.forEach { if (it.exists()) arr.put(encodeFileToBase64(it)) }
            val firstFile = files.firstOrNull()
            val json = JSONObject().apply {
                put("action", "inbound")
                put("sessionToken", getSessionToken(context))
                put("officerId", getOfficerId(context))
                put("device", devInfo)
                put("deviceModel", devInfo)
                if (firstFile != null) put("idempotencyKey", buildIdempotencyKey(firstFile))
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
                if (firstFile != null) {
                    put("imageBase64", encodeFileToBase64(firstFile))
                    put("fileName", firstFile.name)
                }
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) { Pair(false, e.message ?: "Gagal") }
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
            val devInfo = getDeviceHardwareInfo()
            val json = JSONObject().apply {
                put("action", "genset")
                put("sessionToken", getSessionToken(context))
                put("officerId", getOfficerId(context))
                put("device", devInfo)
                put("deviceModel", devInfo)
                put("idempotencyKey", buildIdempotencyKey(photoFisik))
                put("statusGenset", statusGenset)
                put("oilCheck", oilCheck)
                put("fuelInfo", fuelInfo)
                put("officerName", officer)
                put("note", note)
                put("address", address)
                put("coordinates", coords)
                put("fileName", photoFisik.name)
                put("imageBase64", encodeFileToBase64(photoFisik))
                if (photoHse != null && photoHse.exists()) put("hseDocBase64", encodeFileToBase64(photoHse))
                if (videoFile != null && videoFile.exists()) put("videoBase64", encodeFileToBase64(videoFile))
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) { Pair(false, e.message ?: "Gagal") }
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
            val devInfo = getDeviceHardwareInfo()
            val json = JSONObject().apply {
                put("action", "video_patrol")
                put("sessionToken", getSessionToken(context))
                put("officerId", getOfficerId(context))
                put("device", devInfo)
                put("deviceModel", devInfo)
                put("idempotencyKey", buildIdempotencyKey(videoFile))
                put("title", title)
                put("officerName", officer)
                put("note", note)
                put("coordinates", coords)
                put("fileName", videoFile.name)
                put("videoBase64", encodeFileToBase64(videoFile))
            }
            postJson(getEndpointUrl(context), json)
        } catch (e: Exception) { Pair(false, e.message ?: "Gagal") }
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
                if (!resp.contains("\"status\":\"error\"")) {
                    Pair(true, resp)
                } else {
                    val errMsg = try { JSONObject(resp).optString("message", resp) } catch (_: Exception) { resp }
                    Pair(false, errMsg)
                }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                Pair(false, err)
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Gagal koneksi")
        }
    }
}
