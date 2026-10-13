package com.bcpkediri.securitypatrol

import android.graphics.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

object WatermarkUtil {

    // Mengambil ubin satelit asli Google Maps Tile (Super Tajam)
    private fun getGoogleSatelliteTile(lat: Double, lon: Double, zoom: Int = 18): Bitmap? {
        return try {
            val n = 1 shl zoom
            val x = floor((lon + 180.0) / 360.0 * n).toInt()
            val latRad = Math.toRadians(lat)
            val y = floor((1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()

            val urlString = "https://mt1.google.com/vt/lyrs=s&x=$x&y=$y&z=$zoom"
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                BitmapFactory.decodeStream(conn.inputStream)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchSatelliteBitmap(lat: Double, lon: Double, targetW: Int, targetH: Int): Bitmap? {
        val googleTile = getGoogleSatelliteTile(lat, lon, zoom = 18)
        if (googleTile != null) return googleTile

        val googleTile17 = getGoogleSatelliteTile(lat, lon, zoom = 17)
        if (googleTile17 != null) return googleTile17

        return try {
            val sizeW = min(targetW.coerceAtLeast(280), 400)
            val sizeH = min(targetH.coerceAtLeast(280), 400)
            val fallbackUrl = "https://static-maps.yandex.ru/1.x/?ll=$lon,$lat&z=17&l=sat&size=$sizeW,$sizeH"
            val conn = URL(fallbackUrl).openConnection().apply {
                connectTimeout = 2500
                readTimeout = 2500
            }
            BitmapFactory.decodeStream(conn.getInputStream())
        } catch (_: Exception) {
            null
        }
    }

    fun applyTimemarkWatermark(
        filePath: String,
        checkpoint: String,
        address: String,
        coordinates: String
    ): Bitmap {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, boundsOptions)
        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight

        val maxDim = 1920
        var inSampleSize = 1
        if (origH > maxDim || origW > maxDim) {
            val halfH = origH / 2
            val halfW = origW / 2
            while ((halfH / inSampleSize) >= maxDim && (halfW / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }

        val src = BitmapFactory.decodeFile(filePath, decodeOptions) ?: Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val mutableBitmap = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val width = mutableBitmap.width.toFloat()
        val height = mutableBitmap.height.toFloat()
        val isLandscape = width > height
        val baseDim = min(width, height)

        // ==========================================
        // 1. MINIMAP DI POJOK KIRI ATAS
        // ==========================================
        val mapMargin = baseDim * 0.035f
        val mapSize = baseDim * 0.26f
        val mapRect = RectF(mapMargin, mapMargin, mapMargin + mapSize, mapMargin + mapSize)

        canvas.save()
        val mapClip = Path().apply {
            addRoundRect(mapRect, baseDim * 0.02f, baseDim * 0.02f, Path.Direction.CW)
        }
        canvas.clipPath(mapClip)

        var parsedLat = -7.831205
        var parsedLon = 112.040651
        try {
            if (coordinates.contains(",")) {
                val p = coordinates.split(",")
                parsedLat = p[0].replace("[^0-9.-]".toRegex(), "").toDouble()
                parsedLon = p[1].replace("[^0-9.-]".toRegex(), "").toDouble()
            }
        } catch (_: Exception) {}

        val satBitmap = fetchSatelliteBitmap(parsedLat, parsedLon, mapSize.toInt(), mapSize.toInt())
        if (satBitmap != null) {
            canvas.drawBitmap(satBitmap, null, mapRect, null)
        } else {
            val baseGround = Paint().apply { color = Color.rgb(105, 98, 85); style = Paint.Style.FILL }
            canvas.drawRect(mapRect, baseGround)
            val vegPaint = Paint().apply { color = Color.rgb(48, 62, 45); style = Paint.Style.FILL; isAntiAlias = true }
            canvas.drawCircle(mapRect.left + (mapRect.width() * 0.75f), mapRect.top + (mapRect.height() * 0.2f), mapRect.width() * 0.40f, vegPaint)
            val bldgX = mapRect.left + (mapRect.width() * 0.22f)
            val bldgW = mapRect.width() * 0.35f
            val roofPaint = Paint().apply { color = Color.rgb(185, 180, 175); style = Paint.Style.FILL; isAntiAlias = true }
            canvas.drawRect(bldgX, mapRect.top, bldgX + bldgW, mapRect.bottom, roofPaint)
        }

        // Radar Cone Menyesuaikan Kompas Kamera (Pusat di Tengah Map)
        val centerPinX = mapRect.centerX()
        val centerPinY = mapRect.centerY()
        val compassAngle = LocationUtil.currentAzimuth

        canvas.save()
        canvas.rotate(compassAngle, centerPinX, centerPinY)
        val conePath = Path().apply {
            moveTo(centerPinX, centerPinY)
            val coneRadius = mapSize * 0.52f
            val sweepHalf = 30f
            val oval = RectF(centerPinX - coneRadius, centerPinY - coneRadius, centerPinX + coneRadius, centerPinY + coneRadius)
            arcTo(oval, 270f - sweepHalf, sweepHalf * 2f)
            close()
        }
        val conePaint = Paint().apply {
            color = Color.argb(165, 46, 204, 113)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawPath(conePath, conePaint)
        canvas.restore()

        // Pin Biru dengan Titik Putih
        val pinRadius = mapSize * 0.082f
        val bluePinPaint = Paint().apply {
            color = Color.rgb(0, 149, 255)
            style = Paint.Style.FILL
            isAntiAlias = true
            setShadowLayer(4f, 0f, 2f, Color.argb(140, 0, 0, 0))
        }
        canvas.drawCircle(centerPinX, centerPinY, pinRadius, bluePinPaint)

        val whiteDotPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(centerPinX, centerPinY, pinRadius * 0.48f, whiteDotPaint)

        // Logo Google di Pojok Kiri Bawah Map
        val googlePaint = Paint().apply {
            color = Color.WHITE
            textSize = mapSize * 0.15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        canvas.drawText("Google", mapRect.left + (mapSize * 0.06f), mapRect.bottom - (mapSize * 0.06f), googlePaint)
        canvas.restore()

        // Bingkai Putih Map
        val mapBorder = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        canvas.drawRoundRect(mapRect, baseDim * 0.02f, baseDim * 0.02f, mapBorder)

        // ==========================================
        // 2. BANNER WATERMARK BAWAH RAMPING & PENDEK
        // ==========================================
        val marginSide = baseDim * 0.035f
        val boxWidth = if (isLandscape) width * 0.65f else width - (marginSide * 2f)
        val boxHeight = baseDim * 0.22f
        val badgeCheckH = baseDim * 0.065f

        val bottomOffset = (baseDim * 0.025f) + badgeCheckH + 8f
        val boxBottom = height - bottomOffset
        val boxTop = boxBottom - boxHeight

        val bgPaint = Paint().apply {
            color = Color.argb(195, 18, 18, 18)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val bannerRect = RectF(marginSide, boxTop, marginSide + boxWidth, boxBottom)
        canvas.drawRoundRect(bannerRect, baseDim * 0.02f, baseDim * 0.02f, bgPaint)

        val textStartX = marginSide + (baseDim * 0.03f)
        val maxTextWidth = (marginSide + boxWidth) - textStartX - (baseDim * 0.03f)
        var curY = boxTop + (boxHeight * 0.28f)

        // Baris 1: Bendera Merah Putih + BORWITA + Badge Security Patrol
        val flagW = baseDim * 0.055f
        val flagH = flagW * 0.65f
        val flagLeft = textStartX
        val flagTop = curY - (flagH * 0.85f)

        val flagBorderPaint = Paint().apply {
            color = Color.argb(120, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        val flagRed = Paint().apply { color = Color.rgb(220, 38, 38); style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawRect(flagLeft, flagTop, flagLeft + flagW, flagTop + (flagH / 2f), flagRed)
        val flagWhite = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawRect(flagLeft, flagTop + (flagH / 2f), flagLeft + flagW, flagTop + flagH, flagWhite)
        canvas.drawRect(flagLeft, flagTop, flagLeft + flagW, flagTop + flagH, flagBorderPaint)

        val borwitaPaint = Paint().apply {
            color = Color.WHITE
            textSize = baseDim * 0.032f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText("BORWITA", flagLeft + flagW + (baseDim * 0.018f), curY, borwitaPaint)

        val badgePatrolW = baseDim * 0.22f
        val badgePatrolH = baseDim * 0.040f
        val badgePatrolLeft = flagLeft + flagW + (baseDim * 0.18f)
        val badgePatrolRect = RectF(badgePatrolLeft, curY - (badgePatrolH * 0.80f), badgePatrolLeft + badgePatrolW, curY + (badgePatrolH * 0.35f))
        val badgePatrolPaint = Paint().apply { color = Color.rgb(67, 160, 71); style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawRoundRect(badgePatrolRect, 6f, 6f, badgePatrolPaint)

        val badgePatrolTextP = Paint().apply {
            color = Color.WHITE
            textSize = badgePatrolH * 0.62f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("Security Patrol", badgePatrolRect.centerX(), badgePatrolRect.centerY() + (badgePatrolH * 0.22f), badgePatrolTextP)

        curY += (boxHeight * 0.24f)

        // Baris 2: Alamat Lengkap Geotag
        val addrPaint = Paint().apply {
            color = Color.rgb(235, 235, 235)
            textSize = baseDim * 0.028f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }
        val displayAddr = if (addrPaint.measureText(address) > maxTextWidth) {
            var sub = address
            while (addrPaint.measureText("$sub...") > maxTextWidth && sub.length > 5) {
                sub = sub.dropLast(1)
            }
            "$sub..."
        } else address
        canvas.drawText(displayAddr, textStartX, curY, addrPaint)

        curY += (boxHeight * 0.23f)

        // Baris 3: Koordinat Lat, Long
        val coordPaint = Paint().apply {
            color = Color.rgb(220, 220, 220)
            textSize = baseDim * 0.026f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }
        val cleanCoords = if (coordinates.contains(",")) {
            val parts = coordinates.split(",")
            val lat = parts[0].replace("°S", "").replace("°N", "").trim()
            val lon = parts[1].replace("°E", "").replace("°W", "").trim()
            "Lat $lat, Long $lon"
        } else {
            "Lat/Long: $coordinates"
        }
        canvas.drawText(cleanCoords, textStartX, curY, coordPaint)

        curY += (boxHeight * 0.23f)

        // Baris 4: Hari, Tanggal, Jam
        val timeDateStr = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale("id", "ID")).format(Date())
        val timePaint = Paint().apply {
            color = Color.WHITE
            textSize = baseDim * 0.027f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText(timeDateStr, textStartX, curY, timePaint)

        // ==========================================
        // 3. BADGE PUTIH NAMA RUANGAN / TRUK / POS
        // ==========================================
        val roomTitle = checkpoint.ifBlank { "Patroli BCP" }
        val roomTextPaint = Paint().apply {
            color = Color.BLACK
            textSize = badgeCheckH * 0.54f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
        val badgeW = roomTextPaint.measureText(roomTitle) + (baseDim * 0.05f)
        val badgeTop = boxBottom + (baseDim * 0.012f)
        val roomBadgeRect = RectF(
            marginSide + 4f,
            badgeTop,
            marginSide + 4f + badgeW,
            badgeTop + badgeCheckH
        )

        val roomBadgeBg = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(roomBadgeRect, baseDim * 0.016f, baseDim * 0.016f, roomBadgeBg)
        canvas.drawText(
            roomTitle,
            roomBadgeRect.left + (baseDim * 0.025f),
            roomBadgeRect.bottom - (badgeCheckH * 0.30f),
            roomTextPaint
        )

        return mutableBitmap
    }
}
