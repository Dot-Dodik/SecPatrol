# Tinjauan keamanan SecPatrol

## Status

Repositori ini belum layak untuk menyimpan data operasional atau data pribadi produksi. Tinjauan dilakukan pada ZIP `SecPatrol-main (4).zip`, bukan pada checkout GitHub langsung atau konfigurasi Google yang sedang aktif.

### Temuan kritis

1. **Keystore rilis dan password pernah ditanam di `app/build.gradle.kts`.** Siapa pun yang memiliki repositori/ZIP dapat menandatangani APK yang tampak sebagai pembaruan aplikasi. Perbaikan lokal menghapus material itu dan membaca signing dari properti/environment. Anggap keystore lama telah bocor: cabut/rotasi kredensial yang bisa dirotasi, hentikan distribusi APK yang memakai kunci lama, dan jika kunci Play App Signing digunakan ikuti prosedur rotasi Google Play. Kunci lama tidak dapat dibuat rahasia kembali.
2. **Akun bypass `ADMIN-DEV` / `dev123` ada di klien dan backend.** Jalur bypass dihapus. Backend baru harus di-deploy dan versi aplikasi lama harus ditarik; edit kode saja tidak mencabut deployment Apps Script yang lama.
3. **Aplikasi memakai endpoint Apps Script yang ID-nya tertanam di APK.** Endpoint publik bukan rahasia dan tidak dapat menjadi autentikasi. Versi lokal membaca URL dari konfigurasi build, tetapi akses endpoint dan seluruh operasi tulis tetap perlu dipindahkan ke API berautentikasi sebelum penggunaan produksi.

### Temuan tinggi

4. **Login sebelumnya memakai GET dengan password sebagai parameter URL.** Ini membuka kredensial di log/riwayat URL; klien lokal kini mengirim login lewat POST.
5. **Password user disimpan sebagai teks biasa di Google Sheet dan sebelumnya juga di SharedPreferences Android.** Penyimpanan kata sandi lokal dihapus. Kolom password di Sheet tetap harus dianggap bocor dan dirotasi; jangan gunakan password yang sama di layanan lain. Migrasikan ke penyimpanan hash password server-side sebelum produksi.
6. **Endpoint Apps Script menerima operasi tulis tanpa autentikasi/otorisasi yang kuat.** Perubahan klien tidak mengamankan endpoint yang bisa dipanggil langsung. Gunakan backend yang memvalidasi sesi/token, peran pengguna, hak akses per operasi, batas laju, dan audit log. Batasi deployment Apps Script ke domain organisasi sebagai mitigasi sementara jika alur kerja Google mengizinkannya.
7. **Foto di Drive sebelumnya dibagikan `ANYONE_WITH_LINK`.** Perbaikan lokal meniadakan pembagian publik baru. Setel ulang izin file/folder lama, cabut tautan publik, dan periksa siapa yang memiliki akses.

### Temuan sedang

8. Data formulir dapat dimasukkan ke Google Sheets; teks yang diawali karakter formula sekarang di-escape sebelum ditulis. Tetap validasi tipe, panjang, dan nilai enum di server.
9. Respons error Apps Script tidak lagi mengembalikan rincian error internal ke pemanggil.
10. Manifest Android kini menolak HTTP cleartext. Tetap periksa semua URL/redirect dan jangan mengizinkan endpoint arbitrer untuk produksi.

## Tindakan sebelum memakai produksi

- Hapus/arsipkan kredensial dan deployment Apps Script lama; buat deployment baru dengan akses paling sempit yang mendukung operasi.
- Rotasi signing key sesuai prosedur distribusi aplikasi; audit rilis APK sebelumnya dan batasi kanal download.
- Reset semua password yang pernah tersimpan di `DATABASE_USER`; migrasikan skema ke hash ber-salt dengan verifikasi di server. Jangan mencatat password di log.
- Periksa permission Google Sheet dan Drive, termasuk file yang sudah terlanjur dibagikan publik. Terapkan retensi/penghapusan untuk foto, koordinat, dan nomor telepon.
- Ganti Apps Script anonim dengan backend yang punya sesi/token berumur pendek dan role-based access control. Jangan menaruh API secret di APK atau JavaScript browser.
- Tambahkan audit log append-only, validasi payload, idempotency key untuk sinkronisasi offline, rate limit, backup teruji, dan proses pencabutan perangkat/sesi.
- Pisahkan data uji dan produksi. QR serah-terima lokal bukan bukti identitas; jangan menggunakannya sebagai kontrol akses produksi.
- Beri pemberitahuan dan tetapkan tujuan, dasar pemrosesan, akses, retensi, serta proses penghapusan untuk data foto wajah dan lokasi.

## Batas perbaikan lokal

Perubahan dalam paket ini memperbaiki paparan kredensial yang terlihat di kode dan beberapa konfigurasi klien. Perubahan tersebut belum mengamankan Apps Script yang sudah terdeploy, tidak membatalkan kredensial yang pernah bocor, dan belum menyediakan autentikasi produksi untuk operasi inbound/patroli. Jangan menganggap status produksi aman sebelum butir kritis dan tinggi di atas ditutup.
