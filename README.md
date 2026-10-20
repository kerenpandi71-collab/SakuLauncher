# Saku Launcher

Saku Launcher adalah launcher Android bergaya desktop. Launcher ini mempertahankan ikon aplikasi Android asli dari sistem dan menyediakan desktop, Start Menu, dock, widget, serta layar browser internal bernama **SakuTube**.

## Fitur launcher
- Ikon aplikasi Android asli di layar utama, Start Menu, dan dock.
- Start Menu dengan pencarian aplikasi serta tab aplikasi disematkan/semua aplikasi.
- Halaman layar utama tambahan, susun ikon dengan drag-and-drop, dan geser antarhalaman.
- Dock bawah dengan aplikasi favorit dan menu tekan-lama.
- Widget Android melalui `AppWidgetHost`.
- Wallpaper dari galeri, tema terang/gelap, warna aksen, ukuran ikon, transparansi dock, label ikon, dan pengaturan tampilan.
- Dock mengambang opsional di atas aplikasi lain. Aplikasi yang dibuka dari dock memakai peluncuran Android normal, bukan mode freeform.

## SakuTube — browser internal

SakuTube adalah **fitur browser yang ditanamkan di dalam Saku Launcher**, bukan aplikasi Firefox mandiri dan bukan browser Android WebView. Mesin halaman web-nya menggunakan **Mozilla GeckoView**.

Fitur yang tersedia mencakup tab, tab samaran, riwayat, unduhan, mode situs desktop, pemutaran audio latar, Picture-in-Picture, serta pemasangan dan pengelolaan WebExtension melalui Mozilla Add-ons atau file XPI. SakuTube juga menyertakan extension Auto Dub dengan jembatan Android Text-to-Speech.

**Batas kompatibilitas:** GeckoView bukan paket Firefox lengkap. Ketersediaan extension di Mozilla Add-ons tidak menjamin semua API, alur login, native messaging, atau fitur yang ditujukan untuk Firefox desktop akan bekerja di SakuTube. Beberapa penyedia login juga dapat membatasi autentikasi di browser tertanam. Extension harus diuji satu per satu; jangan menganggap dukungan penuh seperti Firefox desktop.

Penyaringan permintaan iklan bersifat terbatas dan bukan pengganti uBlock Origin. Keberhasilan pemblokiran iklan, autentikasi situs, dan pemutaran media bergantung pada situs serta kompatibilitas extension.

## Build
- Android SDK Platform 35
- JDK 17
- `./gradlew assembleDebug`

APK debug: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions menjalankan build untuk perubahan branch yang dikonfigurasi; periksa tab Actions untuk status build dan artefak terbaru.

