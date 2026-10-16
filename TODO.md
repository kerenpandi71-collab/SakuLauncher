# Saku Dock — tindak lanjut UX v0.4

- [x] Pertahankan seluruh commit yang lebih baru dari sesi ChatGPT lain; perbaikan diterapkan di atas `5276edf` tanpa menghapus riwayat remote.
- [x] Ganti perpindahan halaman Home instan dengan slide-fade berbasis arah; bedakan swipe mendatar dari scroll vertikal dan drag ikon.
- [x] Tambahkan slide-fade pada pembukaan/penutupan Start dan perpindahan tab drawer; tombol Back dan tap di luar menutup panel.
- [x] Sediakan kontrol label Drawer terpisah dari label Home, dengan default mengikuti preferensi lama.
- [x] Terapkan blur lintas-jendela pada floating dock hanya ketika Liquid Glass aktif dan Android mendukung; sediakan fallback translucent.
- [x] Perbaiki rebuild dock ketika wallpaper menambah child di depan shell; perbarui penjelasan izin Pengelola Proses/root.
- [x] **Build v0.4.0** — GitHub Actions run `38958750113` sukses; APK telah diunduh dan arsipnya lolos `unzip -t`.
- [ ] **Uji UX di perangkat pengguna** — CI memvalidasi kompilasi, bukan rasa animasi atau kompatibilitas blur pada ponsel.
- [ ] **Integrasi Shizuku** — belum dilakukan pada branch terbaru. Aksi root di Pengelola Proses berbeda dari Shizuku; integrasi perlu diikat pada aksi spesifik dan tidak aktif otomatis.
