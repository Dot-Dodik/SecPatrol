# Instalasi, Upload GitHub, dan Build

## Upload source ke repository

Ekstrak ZIP, masuk ke folder `SecPatrol`, lalu jalankan:

```bash
git init
git branch -M main
git remote add origin https://github.com/Dodik-Dot/SecPatrol.git
git add .
git commit -m "Initial trial SecPatrol 0.1"
git push -u origin main
```

Jika repository sudah memiliki commit, clone repository terlebih dahulu lalu salin isi project ke folder hasil clone.

## Build melalui GitHub Actions

1. Buka tab **Actions**.
2. Pilih **Build Trial APK**.
3. Klik **Run workflow**.
4. Tunggu job berwarna hijau.
5. Unduh artifact `SecPatrol-debug-apk`.
6. Ekstrak dan instal `app-debug.apk`.

Android mungkin meminta izin instalasi dari sumber tidak dikenal. Aktifkan hanya untuk aplikasi yang dipakai mengunduh APK, lalu nonaktifkan kembali setelah pengujian.

## Build lokal

Buka project di Android Studio terbaru, tunggu Gradle Sync, lalu pilih **Build → Build APK(s)**. APK berada di `app/build/outputs/apk/debug/`.

## Masalah umum

- `Permission denied`: pastikan izin kamera diberikan.
- QR tidak terbaca: naikkan kecerahan HP pembuat QR.
- QR kedaluwarsa: buat QR baru dan scan sebelum 120 detik.
- Build gagal: buka detail job Actions dan simpan seluruh log error.
