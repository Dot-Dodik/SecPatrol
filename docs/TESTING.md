# Skenario Pengujian Trial

## Perangkat

Gunakan dua HP Android fisik dan koneksi internet jika menguji upload backend.

## Skenario normal

1. HP A memilih **Shift lama — Buat QR**.
2. Isi penyerah, penerima, area, dan shift.
3. Tampilkan QR.
4. HP B memilih **Shift baru — Scan QR**.
5. Scan dalam 120 detik.
6. Ambil selfie berdua dengan kamera depan.
7. Pastikan aplikasi menyatakan valid dan, bila backend aktif, data muncul di Sheets serta foto di Drive.

## Skenario negatif

- Foto tanpa wajah: harus ditolak.
- Foto satu wajah: harus ditolak.
- Foto tiga orang: harus ditolak.
- QR lebih dari 120 detik: harus ditolak.
- QR acak: harus ditolak.
- Internet mati: versi trial harus melaporkan upload gagal; antrean offline belum tersedia.

Catat tipe HP, versi Android, langkah, hasil aktual, screenshot, dan log GitHub Actions saat melaporkan masalah.
