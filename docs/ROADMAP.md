# Rencana pengembangan

## 0. Amankan sistem yang ada

- Rotasi signing key yang terpapar; jangan memakai kembali key lama untuk rilis baru.
- Cabut deployment Apps Script lama, ganti password pengguna yang tersimpan sebagai teks biasa, dan audit izin Sheet/Drive termasuk file publik lama.
- Buat backend API dengan autentikasi sesi, role dan validasi transisi status. Endpoint Apps Script saat ini masih belum aman untuk data produksi.
- Uji build lokal dan CI dengan signing secret baru sebelum merilis.

## 1. Discovery inbound

- Dokumentasikan status, peran, SLA, volume truk/dock, data wajib, dan pengecualian.
- Kumpulkan denah gudang yang memiliki skala/ukuran, label gate dan dock, serta arah orientasi.
- Sepakati kepemilikan data sopir/foto/lokasi, retensi, dan hak ekspor.

## 2. Pilot inbound

- Model kunjungan truk unik dan riwayat event status append-only.
- Alur gate → antre → assign dock → bongkar → selesai → keluar.
- Form Android, antrean pencarian, KPI dan dashboard web tabel.
- Sheets dapat dipakai sebagai adapter pilot di balik backend; UI tidak mengakses Sheet secara langsung.
- Denah 2D untuk memvalidasi area, status, dan alur operasional.

## 3. Visualisasi dan keandalan lapangan

- Animasi 3D setelah denah/alur disepakati; selalu sediakan tampilan 2D dan mode reduced motion.
- Sinkronisasi offline idempotent dengan penanganan konflik.
- Riwayat audit, pembaruan hampir real-time, notifikasi pengecualian dan laporan SLA.

## 4. Skalakan setelah pilot

- Pindahkan penyimpanan transaksi dari Sheets ke database bila concurrency, audit, query, atau retensi menuntutnya.
- Integrasikan ERP/WMS atau jadwal pemasok sesuai prioritas.
- Multi-site, observability, backup/pemulihan, uji beban, dan uji keamanan independen.

Lihat [`PRD_TRUCK_INBOUND.md`](PRD_TRUCK_INBOUND.md) untuk kebutuhan, alur, metrik, dan keputusan yang masih terbuka.
