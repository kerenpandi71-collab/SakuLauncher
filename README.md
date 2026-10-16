# Saku Dock

Shell desktop ringan untuk Android, terinspirasi pola Start/Taskbar Windows, Shelf ChromeOS, dan proyek Taskbar/Smart Dock. Tidak menyalin aset atau kode proyek tersebut.

## Fitur v0.3

- Workspace beranda yang terpisah dari **Start drawer** pencarian/daftar aplikasi.
- Dock bawah dengan Start dan aplikasi yang disematkan; tahan ikon dock untuk menggeser atau melepasnya.
- Tahan ikon di workspace/Start untuk menyematkan atau melepas aplikasi. Urutan favorit disimpan lokal.
- **Dock mengambang opsional** di atas aplikasi lain. Pengguna memberi izin overlay dari Settings Android; service menampilkan notifikasi permanen yang punya kontrol mematikan dock.
- Ketuk ikon di dock mengambang untuk membuka aplikasi biasa; **tahan ikon untuk mencoba membuka aplikasi sebagai jendela bebas**.
- Tahan ikon aplikasi untuk membuka menu konteks: buka normal, **coba buka sebagai jendela**, atau sematkan/lepas dari dock.
- Pilihan tema gelap/cerah, warna aksen, dan wallpaper beranda; layout responsif portrait/landscape.

## Virtual Mouse + Keyboard (v0.4)

- Tombol penunjuk ⌖ di dock atau opsi **Virtual Mouse + Keyboard** di Pengaturan membuka panel trackpad mengambang.
- Gerak satu jari menggerakkan pointer relatif dengan akselerasi; tap melakukan klik kiri, tombol **Right** mengirim klik kanan, **Drag** menahan/melepas tombol kiri, dan gerak dua jari mengirim scroll.
- Jalur mouse utama meniru arsitektur input DroidOS: Shizuku UserService memanggil `InputManager.injectInputEvent` untuk mouse hover/move/button/scroll. Jika Shizuku tidak aktif/izin ditolak, tap dan long-press memakai Accessibility sebagai fallback.
- Keyboard Saku memakai susunan gaya PC: Esc, F1–F12, angka, QWERTY, Shift/Caps, Ctrl, Alt, Win, Fn/simbol, Backspace/Delete, Enter, panah, dan spasi.
- Untuk pointer mouse asli dan shortcut global, instal/jalankan Shizuku lalu berikan izin kepada Saku saat diminta. Input bridge tidak memerlukan root langsung.
- Untuk fallback, aktifkan layanan **Saku Virtual Controls** di Pengaturan > Aksesibilitas dan izinkan overlay.
- Keyboard bekerja sebagai IME pada kolom teks yang fokus; saat Shizuku terhubung, tombol navigasi dan shortcut dapat dikirim sebagai event keyboard global. Kompatibilitas hover/scroll dan shortcut tetap bergantung pada ROM/aplikasi.
 
## Izin dan batas platform

Launcher, pencarian, pin, dan dock beranda tidak membutuhkan root atau Shizuku. Dock mengambang menggunakan izin Android **Display over other apps** (`SYSTEM_ALERT_WINDOW`) yang diminta hanya saat pengguna mengaktifkannya. Izin dapat dicabut di pengaturan sistem.

Mode **coba buka sebagai jendela** dari menu konteks workspace/Start atau tahan ikon pada dock mengambang mengirim batas jendela melalui API Android publik. Jendela aplikasi freeform tetap dikendalikan Android dan implementasi OEM; permintaan ini dapat diabaikan dan Saku bukan window manager. Shizuku/root tidak diminta untuk dock atau aksi jendela ini. Izin istimewa baru layak ditambahkan bila ada tindakan sistem konkret yang memerlukannya, dengan persetujuan terpisah.

## Build

- Android SDK Platform 35
- JDK 17

```bash
./gradlew assembleDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`. Build CI otomatis berjalan pada push ke `main`.
