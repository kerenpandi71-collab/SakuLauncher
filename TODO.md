# Saku Dock — hasil yang diminta

- [x] **Workspace desktop dan Start drawer** — “ubah total jadi kayak taskbar atau youkidex atau smartdock” serta “ala windows ataupun chrome dekstop”; beranda menampilkan workspace dan dock, sedangkan seluruh aplikasi dibuka terpisah dari tombol Start.
- [x] **Dock aplikasi yang dapat dikustomisasi** — tambah/hapus pin, geser posisi ikon, dan atur pin aplikasi untuk akses cepat.
- [x] **Personalisasi visual** — pilihan tema gelap/cerah, empat warna aksen, dan wallpaper beranda yang dipilih pengguna.
- [x] **Floating dock dengan izin khusus** — dock di atas aplikasi adalah fitur opsional; izin overlay dijelaskan dan diminta hanya ketika pengguna mengaktifkan fitur.
- [x] **Floating/freeform app windows dengan fallback** — menu konteks menyediakan aksi “coba buka sebagai jendela” lewat launch bounds API publik; shell dasar tidak meminta root/Shizuku, dan dukungan jendela tetap tunduk pada Android/OEM.
- [x] **Dokumentasi dan build** — dokumentasi batas fitur dan izin diperbarui; GitHub Actions membangun dan mengunggah APK debug.
- [x] **Keyboard shortcut** — Ctrl+Space untuk Start, Ctrl+F untuk pencarian, Ctrl+, untuk pengaturan, Esc untuk menutup Start, dan F5 untuk menyegarkan daftar aplikasi.
- [ ] **Virtual touchpad + keyboard overlay** — tahap berikutnya; perlu implementasi input yang aman dan pengujian Android 15.
- [ ] **Multi-window manager** — minimize/restore dan status jendela perlu diuji terhadap dukungan shell Android/ROM.
