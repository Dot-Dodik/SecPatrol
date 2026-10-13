# Konfigurasi Google Sheets dan Google Drive

## Persiapan

Gunakan akun Google perusahaan. Buat satu Spreadsheet kosong dan satu folder Drive khusus selfie serah-terima.

## Apps Script

1. Buka `script.google.com` dan buat project baru.
2. Salin isi `backend-apps-script/Code.gs`.
3. Dari URL Spreadsheet, ambil ID di antara `/d/` dan `/edit`.
4. Dari URL folder Drive, ambil ID setelah `/folders/`.
5. Isi kedua nilai pada objek `CONFIG`.
6. Pilih **Deploy → New deployment → Web app**.
7. Jalankan sebagai pemilik script dan batasi akses ke akun/domain organisasi jika memungkinkan. Jangan membuka data produksi ke publik.
8. Otorisasi akses Spreadsheet dan Drive.
9. Salin URL yang berakhir `/exec`.

Atur `APPS_SCRIPT_URL` melalui Gradle property lokal yang tidak di-commit atau environment CI. Endpoint bukan rahasia dan tidak memberikan keamanan pada API.

Build release membutuhkan properti lokal/CI berikut; simpan sebagai secret GitHub Actions atau di `~/.gradle/gradle.properties`, jangan commit:

```properties
APPS_SCRIPT_URL=https://script.google.com/macros/s/DEPLOYMENT_ID/exec
RELEASE_STORE_FILE=/path/to/release-key.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Jangan menaruh `APPS_SCRIPT_URL` dalam URL login dengan kredensial. Aplikasi yang didistribusikan dapat diperiksa; jangan menyimpan API secret di APK. Versi kode ini mengirim login melalui POST, tetapi backend Apps Script masih belum memiliki autentikasi/otorisasi produksi untuk operasi tulis dan baca.

File Drive yang diunggah tetap privat secara default. Berikan akses lewat akun/role yang ditentukan, bukan `Anyone with the link`.

## Hasil

Sheet `SERAH_TERIMA` dibuat otomatis dengan waktu server, ID transaksi, penyerah, penerima, shift, area, jumlah wajah, link foto, dan status.
