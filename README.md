# SecPatrol — BCP Kediri Security Patrol (v1.0.5 Modular)

[![Build APK Release](https://github.com/Dodik-Dot/SecPatrol/actions/workflows/android-debug.yml/badge.svg)](https://github.com/Dodik-Dot/SecPatrol/actions/workflows/android-debug.yml)

Aplikasi Android operasional keamanan pergudangan berbasis *offline-first* dengan pelacakan satelit GPS/GLONASS mandiri, kompas orientasi terintegrasi, stempel digital (*watermark*) fotorealistis bergaya Timemark dengan minimap satelit Google, serta integrasi otomatis ke Google Sheets dan Google Drive.

Dikembangkan secara khusus untuk standardisasi pos jaga, verifikasi ketat pergantian shift, patroli keliling fasilitas, inspeksi tutup gudang, pemeriksaan mesin genset/dokumen HSE, serta manajemen siklus antrean armada *logistik inbound*.

---

## Visi & Misi

### Visi
Mewujudkan tata kelola pengamanan pergudangan yang transparan, akuntabel, dan bebas manipulasi melalui digitalisasi patroli lapangan yang tangguh tanpa ketergantungan sinyal internet.

### Misi
1. **Integritas Bukti Lapangan**: Memastikan dokumentasi patroli terverifikasi fisik dengan pemindaian barcode pos, koordinat satelit riil, arah kompas kamera, dan stempel waktu anti-duplikasi.
2. **Kepatuhan SOP Shift**: Menjamin tugas patroli hanya dapat diakses setelah serah-terima sah dilakukan dan otomatis terkunci kembali saat shift berakhir.
3. **Operasional Tanpa Hambatan (Zero Downtime)**: Menyediakan sistem *offline-first* yang bekerja maksimal di area *blindspot* gudang dan otomatis tersinkronisasi saat sinyal kembali pulih.
4. **Efisiensi Logistik & Fasilitas**: Mengelola status antrean truk (*inbound queue*) secara dinamis dan memonitor kesiapan operasional fasilitas (genset, kelistrikan, dan segel ruangan) dalam satu ekosistem terpadu.

---

## Identitas Aplikasi

- **Repository**: `Dodik-Dot/SecPatrol`
- **Application ID / Namespace**: `com.bcpkediri.securitypatrol`
- **Versi**: `1.0.5 (versionCode: 5) - Modular Edition`
- **Minimal Android**: Android 7.0 Nougat (API 24)
- **Target Android**: Android 15 (API 35)
- **Tema Visual**: *Cyprus Green* (`#004741`), *Sand* (`#F0EDE4`), dan *Safety Amber* (`#D97706`)
- **Credit Pengembang**: Dibuat oleh **Gemini Vibe x Dot** ([www.github.com/Dodik-Dot/](https://www.github.com/Dodik-Dot/))

---

## Fitur Utama & Logika Sistem

### 1. SOP Task Locking & Dynamic Re-locking
- **Gerbang Serah-Terima Wajib**: Semua menu tugas (Titik Patroli, Buka/Tutup Gudang, Inbound, Genset, dan Video) berada dalam kondisi terkunci (🔒) saat aplikasi pertama kali dibuka atau setelah pergantian shift.
- **Selesai Bekerja (Manual Lock)**: Tersedia tombol merah *"Selesai Bekerja"* pada kartu profil aktif untuk mengunci kembali seluruh tugas secara instan saat shift berakhir.
- **Auto-Lock Penyerah Shift**: Saat petugas shift lama menampilkan QR Serah-Terima dan menekan tombol *"Selesai & Kunci Tugas"*, aplikasi di HP shift lama otomatis terkunci seketika.

### 2. Validasi Wajah Berbasis Machine Learning (ML Kit)
- **Serah-Terima Normal (2 Wajah)**: Memanfaatkan Google ML Kit Face Detection untuk memverifikasi kehadiran fisik tepat 2 orang (petugas lama dan petugas baru) dalam satu bingkai kamera depan.
- **Serah-Terima Mandiri (1 Wajah)**: Menyediakan alur darurat jika petugas sebelumnya sakit atau HP drop, dilengkapi pencatatan alasan wajib dan verifikasi 1 wajah mandiri.
- **Simpan Cepat (Non-Blocking)**: Tombol *"💾 Simpan Cepat"* memungkinkan petugas langsung membuka kunci tugas secara instan tanpa harus menunggu proses pengunggahan foto selfie ke cloud selesai.

### 3. Logistik Truck Inbound (3 Siklus & Antrean Outstanding)
- **3 Tahapan Status Truk**:
  1. *Truck Datang - Antri Bongkar* (Outstanding Tahap 1)
  2. *Truck Sedang Bongkar Muat* (Outstanding Tahap 2)
  3. *Truck Selesai Muat (Kosong & Bersih)* (Closed / Selesai)
- **Alur Fleksibel**: Truk yang datang dan langsung siap bongkar dapat langsung dicatat ke status *Bongkar Muat* tanpa wajib melalui status antri.
- **Antrean Plat Otomatis**: Truk berstatus *Antri* dan *Bongkar Muat* akan menetap di menu dropdown antrean. Saat plat dipilih, seluruh data (nopol, driver, HP/WA, divisi, jenis kendaraan, dan vendor) terisi otomatis tanpa perlu mengetik ulang.
- **Pembersihan Otomatis**: Plat truk hanya akan dihapus dari daftar antrean jika security telah memilih dan mengonfirmasi status *Truck Selesai Muat*.
- **Dukungan Multi-Foto & Galeri**:
  - Mendukung pengambilan hingga **3 foto** (tampak depan nopol, segel belakang, dan kondisi bak/muatan).
  - Tampilan pratinjau ringkas **3 foto berjejer ke samping** dengan tombol hapus satuan (`✕`) dan dialog perbesaran gambar.
  - Tersedia opsi **Ambil dari Galeri (Foto Asli Bebas Watermark)** untuk memudahkan petugas yang pada pagi hari bertugas menyeberangkan karyawan dan baru sempat mengunggah foto kendaraan setelahnya.

### 4. Watermark Digital Presisi & Minimap Satelit Google
- **Minimap Satelit Asli Google di Pojok Kiri Atas**: Mengunduh ubin citra satelit resolusi tinggi langsung dari Google Maps Tile Server pada tingkat zoom dekat (`z=18`), menampilkan atap gudang BCP dan area parkir secara jernih.
- **Radar Cone Kompas Dinamis**: Kerucut radar hijau transparan berotasi mengikuti sensor orientasi/kompas perangkat (`currentAzimuth`) secara riil sesuai arah mata angin kamera menghadap.
- **Banner Bawah Ramping & Proporsional**: Posisi banner informasi dipangkas dan dirancang ramping horizontal sehingga tidak menutupi wajah saat foto selfie serah-terima ataupun menutupi dokumen/segel penting saat foto mode landscape.
- **Atribut Lengkap**: Menampilkan bendera Merah Putih, logo identitas BORWITA, alamat jalan geotag, koordinat lintang/bujur 6 desimal, tanggal, jam, dan badge identitas pos/ruangan.

### 5. Pengecekan Mesin Genset, Dokumen HSE & Video Patroli
- Inspeksi parameter teknis harian: status operasi (standby/warming up/kendala), indikator solar, voltase aki, dan kondisi level oli mesin.
- **Dokumentasi 3-in-1**: Mendukung perekaman foto fisik genset (ber-watermark GPS), foto lembar dokumen log sheet HSE bulanan, serta rekaman video suara mesin genset.
- **Video Patroli Fasilitas**: Perekaman video keliling gudang mandiri untuk bukti audit atau penjagaan libur panjang.

### 6. Arsitektur Offline-First & Pembersihan Cache Otomatis
- **Pembersihan Otomatis 3 Hari**: File foto sementara di direktori cache internal aplikasi (`checkpoints`, `selfies`, dan `videos`) yang berumur lebih dari 3 hari akan otomatis dibersihkan setiap kali aplikasi dibuka, menjaga ukuran aplikasi stabil di kisaran 10–30 MB tanpa membebani memori ponsel.
- **Penyimpanan Galeri Publik**: Setiap foto dan video yang diambil tetap disimpan permanen di galeri ponsel (`Pictures/SecPatrol` dan `Movies/SecPatrol`) sebagai arsip fisik bukti kerja petugas.
- **Smart Resync**: Tombol *Sync* di menu Galeri dan menu Antrean Offline cerdas memetakan tujuan lembar kerja:
  - File berawalan `Pos` -> Sheet **`TITIK_PATROLI`**
  - File berawalan `Truck` -> Sheet **`LOGISTIK_INBOUND`**
  - File berawalan `Genset` -> Sheet **`PENGECEKAN_GENSET`**
  - File berawalan `SerahTerima` -> Sheet **`SERAH_TERIMA`**
  - File fasilitas/ruangan -> Sheet **`DOKUMENTASI_GUDANG`**

---

## Panduan Penggunaan Lapangan

### 1. Pemasangan & Pembaruan Aplikasi
1. Unduh file `app-debug.apk` versi terbaru.
2. Pasang di ponsel. Mulai versi **v1.0.5**, aplikasi mendukung **pembaruan langsung (*in-place update*)** tanpa perlu mencopot (uninstall) versi sebelumnya, sehingga seluruh data profil dan pengaturan tidak hilang.
3. Berikan izin akses Kamera dan Lokasi (GPS) presisi tinggi.

### 2. Serah-Terima Shift (Membuka Kunci Tugas)
1. **Model Berdua**: Shift lama menekan menu *Buat QR Shift*, tentukan shift, lalu tekan *Tampilkan QR*. Shift baru menekan *Scan QR Shift*, arahkan kamera ke QR shift lama, lalu lakukan selfie bersama (wajib 2 wajah).
2. **Model Mandiri**: Jika shift lama berhalangan, shift baru memilih *Serah-Terima Mandiri*, ketik alasan, lalu selfie mandiri (1 wajah).
3. Tekan **"💾 Simpan Cepat"** untuk langsung membuka seluruh fitur tugas seketika.
4. Petugas lama menekan tombol merah **"🔒 Selesai & Kunci Tugas"** pada layar QR untuk mengunci HP lamanya.

### 3. Perekaman Titik Pos Patroli
1. Pilih menu **Titik Patroli (Scan Barcode & Foto GPS)**.
2. Tekan **Scan Pos** dan pindai stiker barcode pos (berlaku jeda anti-duplikasi 30 menit per pos yang sama).
3. Tunggu indikator jarak dan GPS mengunci posisi, masukkan catatan temuan, lalu tekan **Ambil Foto Pos**.
4. Foto otomatis ber-watermark minimap satelit Google dan stempel waktu, lalu tekan **Simpan & Kirim Laporan**.

### 4. Pelaporan Logistik Truck Inbound
1. Buka menu **Logistik Truck Inbound**.
2. **Jika Truk Baru Datang**:
   - Ketik Nomor Polisi, Nama Sopir, No HP/WA, Divisi, dan Ekspedisi.
   - Pilih status: *Truck Datang - Antri Bongkar* atau langsung *Truck Sedang Bongkar Muat*.
   - Ambil foto kondisi kendaraan (1 s.d 3 foto via kamera atau galeri).
   - Tekan *Simpan & Kirim Laporan* (atau tekan *Simpan ke Antrean Outstanding* jika belum sempat memotret).
3. **Jika Memperbarui Truk yang Sedang Antri/Bongkar**:
   - Klik kotak dropdown **"PILIH ANTREAN OUTSTANDING"** di bagian paling atas.
   - Pilih plat nomor kendaraan. Seluruh formulir akan terisi otomatis.
   - Ubah statusnya menjadi *Sedang Bongkar Muat* atau *Truck Selesai Muat (Kosong & Bersih)*.
   - Lampirkan foto kondisi terbaru, lalu tekan *Simpan & Kirim Laporan*. Plat otomatis dibersihkan dari antrean begitu status selesai dipilih.

### 5. Mengakhiri Tugas / Ganti Petugas
- Petugas dapat menekan tombol merah **"🔒 Selesai Bekerja (Kunci Kembali Tugas)"** di menu Beranda kapan pun saat jam dinas berakhir untuk mengunci kembali seluruh modul tugas demi keamanan.

---

## Struktur Basis Data (Google Sheets)

Semua data otomatis tersinkronisasi ke spreadsheet `Database_SecPatrol_Kediri` yang terbagi ke dalam lembar kerja spesifik:
- **`TITIK_PATROLI`**: Rekap scan rute pos (Pos 1 s.d Pos 6), nama petugas, jam patroli, koordinat GPS, dan tautan foto Drive.
- **`LOGISTIK_INBOUND`**: Rekap siklus truk (Antri, Bongkar, Selesai), nomor polisi, sopir, ekspedisi, divisi, nomor segel, dan foto bukti muatan (hingga 3 foto).
- **`PENGECEKAN_GENSET`**: Log teknis voltase aki, level solar, kondisi oli, tautan foto fisik, foto dokumen HSE, dan video mesin.
- **`DOKUMENTASI_GUDANG`**: Bukti kepatuhan buka/tutup gembok segel pintu gudang, ruang sales, finance, dan area parkir.
- **`SERAH_TERIMA`**: Berita acara serah-terima shift (penyerah, penerima, shift, jam, dan foto selfie terverifikasi).
- **`VIDEO_PATROLI`**: Rekaman inspeksi video fasilitas gudang.
- **`DATABASE_USER`**: Master identitas profil NIP dan regu petugas security yang terdaftar.

---

## Lisensi & Atribusi

Hak Cipta © 2026 **BCP Kediri & Dodik-Dot**.  
Dikembangkan bersama **Gemini Vibe x Dot**.  
Didistribusikan di bawah lisensi internal pergudangan untuk standardisasi keamanan fasilitas BCP Kediri.
