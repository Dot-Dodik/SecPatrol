package com.bcpkediri.securitypatrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

// VARIABEL TOP-LEVEL: Kompatibilitas jika dipanggil langsung tanpa prefix LocationUtil
var currentAzimuth: Float
    get() = LocationUtil.currentAzimuth
    set(value) { LocationUtil.currentAzimuth = value }

object LocationUtil : SensorEventListener {

    // VARIABEL AZIMUTH / SUDUT KOMPAS (UNTUK WATERMARK RADAR CONE)
    var currentAzimuth: Float = 0f

    private var sensorManager: SensorManager? = null
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    fun checkNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val actNw = cm.getNetworkCapabilities(network) ?: return false
            actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    fun isLocationPermissionGranted(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    // CEK APAKAH GPS FISIK DIAKTIFKAN DI HP
    fun isGpsEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            val isGps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNet = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            isGps || isNet
        } catch (_: Exception) {
            false
        }
    }

    // BUKA PENGATURAN GPS JIKA LAYANAN LOKASI MATI
    fun openLocationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    // AKTIFKAN SENSOR KOMPAS PERANGKAT
    fun startCompass(context: Context) {
        try {
            if (sensorManager == null) {
                sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            }
            sensorManager?.let { sm ->
                val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                if (rotSensor != null) {
                    sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
                } else {
                    val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                    accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                    mag?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        try {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuthRad = orientation[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                if (azimuthDeg < 0) azimuthDeg += 360f
                currentAzimuth = azimuthDeg
            } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                gravity = event.values.clone()
            } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                geomagnetic = event.values.clone()
            }

            if (gravity != null && geomagnetic != null) {
                val r = FloatArray(9)
                val i = FloatArray(9)
                if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(r, orientation)
                    var azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    if (azimuthDeg < 0) azimuthDeg += 360f
                    currentAzimuth = azimuthDeg
                }
            }
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun fetchPureGpsLocation(
        context: Context,
        onLocationResult: (locText: String, coordsRaw: String, location: Location?) -> Unit
    ) {
        // Otomatis nyalakan sensor kompas saat pelacakan GPS dimulai
        startCompass(context)

        try {
            if (!isLocationPermissionGranted(context)) {
                onLocationResult("Izin GPS belum diaktifkan", "-", null)
                return
            }

            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                onLocationResult("Sensor GPS tidak tersedia", "-", null)
                return
            }

            val isGps = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
            val isNet = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }

            if (!isGps && !isNet) {
                onLocationResult("Layanan Lokasi (GPS) Dimatikan", "-", null)
                return
            }

            var lastKnown: Location? = null
            if (isGps) {
                try { lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            }
            if (lastKnown == null && isNet) {
                try { lastKnown = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) {}
            }

            if (lastKnown != null) {
                val coords = String.format(Locale.US, "%.6f, %.6f", lastKnown.latitude, lastKnown.longitude)
                val text = "GPS Satelit: $coords (Akurasi: ±${lastKnown.accuracy.toInt()}m)"
                onLocationResult(text, coords, lastKnown)
            } else {
                onLocationResult("Mencari koordinat satelit...", "-", null)
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    try {
                        val coords = String.format(Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude)
                        val text = "GPS Satelit: $coords (Akurasi: ±${loc.accuracy.toInt()}m)"
                        onLocationResult(text, coords, loc)
                        lm.removeUpdates(this)
                    } catch (_: Exception) {}
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    onLocationResult("Layanan Lokasi (GPS) Dimatikan", "-", null)
                }
            }

            if (isGps) {
                try {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, listener, Looper.getMainLooper())
                } catch (_: Exception) {}
            } else if (isNet) {
                try {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, listener, Looper.getMainLooper())
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            onLocationResult("GPS Error: ${e.message ?: "Tidak terdeteksi"}", "-", null)
        }
    }

    suspend fun resolveAddress(context: Context, latitude: Double, longitude: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale("id", "ID"))
            val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latitude, longitude, 1)
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1)
            }

            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val thoroughfare = addr.thoroughfare ?: addr.subLocality ?: ""
                val locality = addr.locality ?: addr.subAdminArea ?: ""
                val adminArea = addr.adminArea ?: ""
                listOf(thoroughfare, locality, adminArea).filter { it.isNotBlank() }.joinToString(", ").ifBlank {
                    addr.getAddressLine(0) ?: "Jalan Mauni, Kecamatan Pesantren, Kota Kediri"
                }
            } else {
                "Jalan Mauni, Kecamatan Pesantren, Kota Kediri"
            }
        } catch (_: Exception) {
            "Jalan Mauni, Kecamatan Pesantren, Kota Kediri"
        }
    }
}
