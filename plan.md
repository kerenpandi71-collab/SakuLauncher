# Rencana Saku Dock v0.4

## Arah desain

Saku Launcher beralih menjadi **Saku Dock**, shell desktop Android yang berpusat pada dock/taskbar menetap dan menu Start terpisah. Inspirasi interaksi diambil dari pin dan susun ulang shelf ChromeOS, aplikasi pin + menu konteks Windows, dan app drawer/task tray Taskbar. Tidak menyalin aset atau kode proyek lain.

## Perilaku produk

- Beranda menjadi workspace yang lega dan menunjukkan pintasan terpilih; daftar semua aplikasi hanya dibuka dari tombol Start.
- Dock bawah memuat Start, aplikasi favorit, dan pengaturan. Mode drawer dan dock tidak saling menumpuk.
- Favorit dapat dipin/dilepas dan disusun dengan interaksi sentuh.
- Pengguna dapat memilih tema gelap/cerah, warna aksen, dan wallpaper beranda.
- Dock mengambang di atas aplikasi adalah opsi terpisah; hanya minta izin `SYSTEM_ALERT_WINDOW` ketika pengguna mengaktifkannya.
- Peluncuran freeform memakai batas jendela publik bila perangkat/layar mendukungnya; fallback selalu peluncuran normal. UI tidak menjanjikan dukungan universal.
- Shizuku/root bukan prasyarat. Integrasi tersebut hanya ditambahkan untuk aksi istimewa konkret yang tidak dapat dicapai melalui API biasa; tidak meminta root luas demi kosmetik.

## Implementasi

Aplikasi tetap native Kotlin + Android Views. `MainActivity` mengelola workspace, Start drawer, dan dock beranda. `FloatingDockService` membuat overlay opsional di atas aplikasi melalui `TYPE_APPLICATION_OVERLAY`. Preferensi lokal menyimpan pin, susunan dock, dan status overlay. Manifest mendeklarasikan izin overlay serta service internal. Dialog pengaturan menerangkan alasan dan membawa pengguna ke halaman izin sistem.

## Batas dan sumber desain

Taskbar menyebut menu Start/app tray dan freeform yang bergantung versi/perangkat (https://github.com/farmerbb/Taskbar). Smart Dock mendokumentasikan custom dock dan fitur Shizuku beta, dengan izin lanjutan khusus fitur (https://github.com/axel358/smartdock). YoukiDEX memberikan contoh overlay/dock namun repositorinya diarsipkan pada 2026 (https://github.com/mrYouki/YoukiDex-Android-Desktop). Windows Start/taskbar menjadi referensi pin, search, dan reorganisasi (https://microsoft.design/articles/start-fresh-redesigning-windows-start-menu/; https://support.microsoft.com/en-us/windows/experience/personalization/customize-the-taskbar-in-windows). ChromeOS Shelf menjadi referensi pin, unpin, susun ulang, posisi, dan autohide (https://support.google.com/chromebook/answer/3113576?hl=en). Android freeform/desktop windowing tetap ditentukan dukungan sistem/OEM (https://source.android.com/docs/core/display/desktop-windowing; https://source.android.com/docs/core/display/multi-window). Shizuku adalah bridge ADB/root dengan persetujuan aplikasi, bukan pengganti dukungan windowing (https://github.com/RikkaApps/Shizuku).

## Struktur utama

- `app/src/main/java/com/sakuhome/launcher/MainActivity.kt`: workspace, Start drawer, pengelolaan favorit, pengaturan overlay.
- `app/src/main/java/com/sakuhome/launcher/FloatingDockService.kt`: service overlay opsional di atas aplikasi.
- `app/src/main/AndroidManifest.xml`: entry point HOME, deklarasi service dan izin.
- `app/build.gradle.kts`: versi Android.
