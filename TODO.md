# Saku Dock — tindak lanjut UX v0.4

- [x] Pertahankan seluruh 87 commit yang lebih baru dari sesi ChatGPT lain; perbaikan diterapkan di atas `5276edf` dan patch lokal lama disimpan di branch cadangan.
- [x] Ganti perpindahan halaman Home instan dengan slide-fade berbasis arah; bedakan swipe mendatar dari scroll vertikal dan drag ikon.
- [x] Tambahkan slide-fade pada pembukaan/penutupan Start dan perpindahan tab drawer; tombol Back dan tap di luar menutup panel.
- [x] Sediakan kontrol label Drawer terpisah dari label Home, dengan default mengikuti preferensi lama agar perilaku pengguna lama tetap kompatibel.
- [x] Terapkan blur lintas-jendela pada floating dock hanya ketika Liquid Glass aktif dan Android mendukung; selalu sediakan fallback translucent.
- [x] Perbaiki rebuild dock ketika wallpaper menambah child di depan shell; perbarui penjelasan izin Pengelola Proses/root.
- [ ] Validasi kompilasi v0.4.0 lewat GitHub Actions dan unduh APK hasil build.
- [ ] Uji gesture/label pada perangkat pengguna; build CI hanya memvalidasi kompilasi, bukan feel animasi di ponsel.

Catatan: Shizuku belum ditambahkan pada branch terbaru. Fungsi root di Pengelola Proses dan Shizuku bukan hal yang sama; integrasi Shizuku perlu diikat pada aksi spesifik dan tidak diaktifkan otomatis.
