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


## Virtual desktop + trackpad

The integrated FOSS trackpad and custom keyboard remain bundled; the DroidOS launcher module is not included. Open **Settings → Mode Desktop Virtual** to create a secondary virtual display, adjust its resolution and DPI, and start the trackpad/keyboard controls. The upper panel is a lightweight secondary-display launcher preview; applications can be launched onto the selected virtual display through the privileged shell. Root is attempted first; Shizuku can be used when authorized.

Source and license attribution: see [trackpad/README.md](trackpad/README.md) and [trackpad/LICENSE](trackpad/LICENSE). If redistributing, comply with the included GPLv3 license.
