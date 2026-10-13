# PRD — SecPatrol Truck Inbound Dashboard

**Status:** Draft 0.1 · **Tanggal:** 13 Oktober 2026 · **Pemilik produk:** perlu ditetapkan

## 1. Ringkasan

Bangun sistem operasional untuk mencatat dan memantau siklus truk inbound dari kedatangan, antre, masuk ke dock, bongkar muat, hingga selesai dan keluar. Petugas menggunakan aplikasi Android yang sudah ada atau aplikasi native yang diperluas. Supervisor dan admin memakai dashboard web untuk melihat kondisi langsung, mengatur alur dock, mencari riwayat, dan membaca metrik.

Untuk pilot, Google Sheets dapat dipakai sebagai sumber data awal melalui backend yang tervalidasi. Dashboard tidak boleh mengakses Sheet langsung dari browser. Setelah volume atau kebutuhan audit/akses meningkat, pindahkan penyimpanan transaksi ke database API; Sheets tetap dapat menjadi ekspor/laporan.

## 2. Masalah dan tujuan

Data truk saat ini dicatat melalui formulir dan antrean sederhana. Sulit melihat durasi tunggu/bongkar secara konsisten, posisi truk di area gudang, beban tiap dock, dan status terakhir secara langsung. Data penting seperti sopir, nomor telepon, foto, dan koordinat perlu akses yang terkendali.

Tujuan pilot:

- Catat satu siklus inbound dengan riwayat perubahan status dan waktu server.
- Beri tampilan antrean yang jelas dan dapat dipakai supervisor untuk mengambil tindakan.
- Tampilkan status truk di denah gudang; animasi 3D menggambarkan perpindahan antar area/dock.
- Kurangi input berulang dan kesalahan status dari perangkat lapangan.
- Simpan bukti foto serta jejak audit dengan hak akses dan retensi yang ditetapkan.

## 3. Pengguna dan hak akses

| Peran | Kebutuhan | Akses awal |
|---|---|---|
| Petugas gate/security | Catat kedatangan, verifikasi kendaraan/dokumen, ubah status awal | Membuat dan memperbarui truk pada site/shift yang ditugaskan |
| Operator dock | Memanggil truk, mulai/akhiri bongkar, catat kendala | Mengubah status di dock yang ditugaskan |
| Supervisor | Atur prioritas/dock, tangani pengecualian, pantau SLA | Melihat semua transaksi site, melakukan override dengan alasan |
| Admin | Kelola pengguna, site, dock, konfigurasi, retensi | Pengaturan; akses data tetap dicatat |
| Viewer/auditor | Lihat laporan dan jejak audit | Baca saja sesuai cakupan yang diberikan |

Semua tindakan perubahan status menyimpan identitas aktor, waktu server, status sebelum/sesudah, dan alasan bila override. Autentikasi/otorisasi server wajib; menyembunyikan tombol di UI bukan kontrol akses.

## 4. Alur kerja inti

1. **Pra-kedatangan (opsional):** admin/import jadwal memuat pemasok, nomor PO/DO, perkiraan waktu, tipe kendaraan, dan dock tujuan.
2. **Gate — Tiba:** petugas mencari jadwal atau membuat kunjungan; catat nopol, sopir, vendor/ekspedisi, muatan, segel, waktu tiba, dan foto opsional. Sistem membuat `truck_visit_id` unik.
3. **Verifikasi:** status dokumen/segel (lolos, perlu pemeriksaan, ditolak) dan catatan pengecualian. Data pribadi sopir hanya dikumpulkan sesuai kebutuhan.
4. **Antre:** truk masuk antrean site; supervisor dapat mengatur prioritas dan dock. Perubahan prioritas wajib menyimpan alasan.
5. **Dipanggil / menuju dock:** assign dock dan tampilkan instruksi ke petugas terkait.
6. **Bongkar dimulai:** simpan waktu mulai dan operator; foto/kuantitas/temuan dapat dicatat bila dibutuhkan.
7. **Bongkar selesai:** simpan waktu selesai, hasil, ketidaksesuaian, dan persetujuan bila diperlukan.
8. **Keluar:** petugas gate konfirmasi waktu keluar, status akhir, segel/dokumen, lalu tutup kunjungan.

Status terminal tambahan: `DITOLAK`, `DIBATALKAN`, `TIDAK_HADIR`. Jangan menimpa transaksi kunjungan lama saat nopol yang sama datang lagi; setiap kedatangan adalah kunjungan baru.

## 5. Kebutuhan produk

### Dashboard web

- Ringkasan shift/site: truk menunggu, dock aktif, durasi tunggu median/P90, bongkar aktif, selesai hari ini, melewati SLA.
- Tabel antrean dengan pencarian nopol/kunjungan, filter site/status/vendor/dock/jam, sort prioritas dan waktu tiba.
- Papan dock dengan status tersedia/terisi/maintenance dan aksi assign/call/override berizin.
- Denah gudang interaktif: gate, jalur, area antre, dock, area terlarang. Marker truk menunjukkan status dan dock.
- Mode 3D: transisi truk dianimasikan antar node di denah; status tetap bisa dipahami lewat label/warna dan daftar teks. Animasi dekoratif tidak boleh menjadi satu-satunya sumber informasi atau menghalangi aksi.
- Detail kunjungan: timeline status, data truk, foto dengan akses terbatas, catatan, aktor, audit trail.
- Laporan ekspor CSV/XLSX berdasarkan filter dan periode dengan pencatatan siapa yang mengekspor.
- Pembaruan hampir real-time (target pilot 5–15 detik) dan indikator terakhir diperbarui/offline.

### Aplikasi Android

- Form cepat untuk kedatangan dengan validasi nopol, vendor, status dokumen, waktu server, dan lampiran foto.
- Antrean yang dapat dicari; tindakan status tersedia sesuai peran dan site.
- Offline queue dengan ID idempotensi; tampilkan status pending/synced/failed dan konflik untuk ditinjau.
- Jangan simpan password/API secret. Token sesi disimpan aman dan dapat dicabut.
- Minta izin lokasi hanya saat diperlukan dan tampilkan tujuan penggunaannya.

## 6. Data inti

Tabel/entitas konseptual:

- `sites`, `warehouse_map_versions`, `map_nodes`, `map_edges`, `docks`
- `users`, `roles`, `site_assignments`, `sessions`
- `truck_visits` (ID kunjungan, nopol ternormalisasi, sopir/vendor, muatan, segel, jadwal, status saat ini)
- `status_events` (kunjungan, status lama/baru, waktu server, aktor, sumber perangkat, alasan, idempotency key)
- `dock_assignments`, `inspection_results`, `attachments` (metadata + private object reference)
- `audit_events`, `retention_policies`

Google Sheets pilot sebaiknya memakai tab `TRUCK_VISITS`, `STATUS_EVENTS`, `DOCKS`, `USERS` dan ID kunjungan; jangan pakai nomor polisi sebagai primary key. Jangan menulis rahasia autentikasi ke Sheet. Lindungi file foto secara default dan simpan tautan privat/metadatanya.

## 7. Denah dan animasi 3D

Denah milik pengguna menjadi dasar pemodelan. Untuk versi pertama, perlu gambar denah yang memiliki skala atau ukuran referensi, label area/gate/dock, arah utara atau orientasi, dan batas jalur. Versi 2D SVG/Canvas lebih dahulu untuk memvalidasi alur dan posisi; setelah disetujui, bangun scene 3D WebGL/Three.js dengan geometri sederhana (lantai, jalur, dock, truk) dan kamera yang bisa dikontrol.

Setiap node denah memiliki ID stabil dan posisi; event status memetakan truk ke node. Animasi berasal dari event antrean/dock dengan transisi yang bisa dihentikan, reduced-motion support, dan fallback 2D. Hindari mengklaim posisi GPS presisi bila hanya status proses yang diketahui.

## 8. Keamanan, privasi, dan keandalan

- Backend API sebagai satu-satunya akses ke database/Sheets; TLS, autentikasi server, RBAC, validasi setiap operasi, pembatasan laju, audit trail.
- Jangan percaya status/peran atau timestamp dari klien; server memvalidasi transisi status dan waktu.
- Foto dan koordinat tidak publik; least privilege, signed URL berumur pendek bila diperlukan, retensi/penghapusan yang disetujui.
- Enkripsi transit dan at-rest sesuai kemampuan platform; backup dan uji pemulihan.
- Batasi data sopir/nomor HP dan akses ekspor; kebijakan tujuan, retensi, notifikasi, dan penghapusan harus ditetapkan organisasi.
- Offline sync aman terhadap duplikasi dengan idempotency key serta deteksi konflik, bukan last-write-wins diam-diam.

## 9. Non-goals pilot

- Perencanaan rute armada di luar fasilitas.
- Pengenalan wajah/identitas otomatis.
- Integrasi ERP/WMS yang belum dipilih.
- Optimasi AI atau prediksi ETA sebelum data historis cukup.
- Simulasi fisika realistis; visual 3D hanya mewakili status operasional.

## 10. Ukuran keberhasilan

- ≥95% kunjungan inbound memiliki rangkaian waktu tiba, mulai bongkar, selesai bongkar, dan keluar yang lengkap.
- Median waktu pencatatan kedatangan <60 detik pada uji lapangan.
- Selisih antara waktu event server dan praktik lapangan dapat diaudit; tidak ada status ganda karena retry.
- Supervisor dapat menemukan kunjungan aktif dan dock-nya dalam <10 detik.
- Pengguna dapat memakai alur utama pada perangkat low-end dan jaringan putus-sambung.
- Tidak ada akses foto publik atau operasi perubahan tanpa role yang sah pada audit pra-produksi.

## 11. Rencana bertahap

### Tahap 0 — Discovery & keamanan (1–2 minggu)

- Petakan proses aktual, status/SLA, peran, jumlah truk per shift, sumber data, dan owner data.
- Rotasi kredensial bocor, tinjau izin Google, nonaktifkan deployment lama, dan tetapkan keputusan data pribadi/retensi.
- Kumpulkan denah beserta ukuran, dock, arah, dan alur kendaraan.

### Tahap 1 — Pilot operasional (2–4 minggu)

- Backend API aman + login/role; model kunjungan dan event status.
- Form kedatangan, antrean, assign dock, mulai/selesai bongkar, keluar; Sheets hanya sebagai adapter/laporan jika masih memadai.
- Dashboard tabel/KPI + denah 2D; audit trail dan ekspor terbatas.
- Uji pilot pada satu site/shift dengan data sintetis lebih dulu.

### Tahap 2 — Visualisasi dan offline (2–4 minggu)

- Model denah 3D sederhana dan animasi status; fallback 2D/reduced motion.
- Sinkronisasi offline idempotent, konflik yang dapat ditinjau, notifikasi perubahan antrean.
- Dashboard SLA, analisis jam sibuk, dan akses auditor.

### Tahap 3 — Skalakan (sesudah pilot)

- Migrasi dari Sheets ke database transaksional jika concurrency/retensi/audit melampaui batas.
- Integrasi ERP/WMS/penjadwalan supplier berdasarkan prioritas bisnis.
- Multi-site, observability, disaster recovery, uji beban dan uji keamanan independen.

## 12. Keputusan yang masih diperlukan

- Siapa pengguna dan approver pilot; satu gudang/site mana yang pertama?
- Proses/status dan target SLA aktual untuk gate, antre, dan tiap jenis bongkar?
- Apakah jadwal truk/PO berasal dari WMS/ERP atau dimasukkan manual?
- Berapa volume puncak truk per jam, jumlah dock, perangkat, dan lama retensi bukti?
- Siapa boleh melihat/mengekspor nomor telepon, foto, lokasi, dan berapa lama?
- Apakah pengguna bisa memakai akun perusahaan Google untuk pilot, atau diperlukan backend login terpisah?
- Silakan kirim denah gudang dengan skala/ukuran, label gate/dock, dan arah untuk membuat prototipe tampilan 2D lalu 3D.
