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


## DroidOS Trackpad integration

Branch `feature/saku-trackpad-integration` packages the GPLv3 FOSS trackpad implementation as a library module inside SakuLauncher. In Saku settings, select **Mode Trackpad DroidOS · ON/OFF** to open the trackpad setup/service or send its stop command. Android Accessibility, overlay, and Shizuku permissions remain user-controlled.

Source and license attribution: see [trackpad/README.md](trackpad/README.md) and [trackpad/LICENSE](trackpad/LICENSE). If you redistribute a build containing this GPLv3 module, comply with GPLv3 obligations for the combined derivative work.


## DroidOS desktop mode

Saku bundles the original FOSS/GPLv3 DroidOS launcher and trackpad modules. Open **Settings → Masuk Mode Desktop DroidOS** to enter the combined setup screen, authorize Shizuku, and then start the original launcher and trackpad overlays. Root can be used to grant overlay and accessibility settings; the FOSS DroidOS shell/input backend still requires Shizuku authorization.
