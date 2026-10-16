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

- Tombol penunjuk ⌖ di dock atau opsi **Virtual Mouse + Keyboard** di Pengaturan membuka panel touchpad mengambang.
- Gerakan jari memindahkan penunjuk virtual; ketukan mengirim tap dan mode Drag mengirim gesture seret.
- Tombol keyboard membuka pemilih IME. Aktifkan **Saku Keyboard** pada pengaturan keyboard Android, lalu pilih saat ingin mengetik ke kolom teks yang sedang fokus.
- Pertama kali, aktifkan layanan **Saku Virtual Controls** di Pengaturan > Aksesibilitas dan izinkan panel overlay.
- Input memakai Accessibility gesture API. Ini **bukan mouse hardware penuh**: tidak ada hover asli, pointer bisa tidak bekerja pada aplikasi tertentu, dan keyboard berfungsi saat sebuah kolom teks menerima fokus. Layanan tidak membaca teks layar.

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
