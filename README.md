# Saku Launcher

Launcher Android bergaya desktop yang mempertahankan ikon aplikasi Android asli dari sistem.

## Fitur
- Ikon aplikasi Android asli di layar utama, Start Menu, dan dock.
- Start Menu dengan pencarian aplikasi, tab disematkan/semua aplikasi.
- Halaman layar utama tambahan, susun ikon dengan drag-and-drop, dan geser antarhalaman.
- Dock bawah dengan aplikasi favorit dan menu tekan-lama.
- Widget Android melalui AppWidgetHost.
- Wallpaper dari galeri, tema terang/gelap, warna aksen, ukuran ikon, transparansi dock, label ikon, dan pengaturan tampilan.
- Dock mengambang opsional di atas aplikasi lain. Aplikasi yang dibuka dari dock memakai peluncuran Android normal, bukan mode freeform.

## Jendela aplikasi
Mode jendela bebas (freeform) sengaja tidak diminta oleh Saku Launcher. Aplikasi diluncurkan dengan intent Android biasa sehingga tidak dipaksa menjadi jendela mengambang. Multi-window sistem tetap mengikuti dukungan dan pengaturan ROM.

## Build
- Android SDK Platform 35
- JDK 17
- `./gradlew assembleDebug`

APK debug: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions membangun APK pada branch `feature/saku-desktop-overhaul`; periksa tab Actions untuk status dan artefak.


## YouTube di dalam Saku

SakuLauncher menyediakan layar YouTube internal melalui Android WebView, jadi tautan ini tidak perlu membuka browser eksternal. Penyaringan permintaan iklan yang ada bersifat terbatas dan **bukan pengganti uBlock Origin**; pemblokiran iklan penuh dan pemutaran stabil belum dijamin.

