# Saku Desktop — macOS-inspired edition

Launcher Android dengan tampilan desktop bergaya macOS yang dibuat untuk penggunaan satu tangan di ponsel, dan berjalan dari HTML lokal di dalam WebView native.

## Tampilan dan fitur
- Menu bar, jam, Dock kaca, ikon desktop, Launchpad dengan pencarian, dan Control Center.
- Jendela internal yang dapat diseret, ditutup, diminimalkan, dan dimaksimalkan.
- Catatan, Pengingat/To-Do, Kalkulator, Jam dan stopwatch, Kalender dengan acara, Cuaca Open-Meteo, Terminal simulasi, Finder, dan Pengaturan tema.
- Catatan, tugas, acara, wallpaper, tema, dan aksen disimpan lokal melalui localStorage.
- Bridge Android untuk menampilkan aplikasi yang terpasang dan membuka aplikasi Android asli dari Launchpad.
- Pintasan: `Ctrl+Space` atau `Ctrl+F` membuka Launchpad; `Ctrl+,` membuka Pengaturan; `Esc` menutup panel.
- Cuaca memerlukan internet; aplikasi lainnya pada dasarnya dapat digunakan offline.

## Batasan yang perlu diketahui
- Jendela internal adalah jendela UI di dalam launcher. Membuka aplikasi Android lain dalam freeform asli tetap bergantung pada dukungan window manager ROM dan permintaan mode freeform di layanan dock.
- Tombol Wi-Fi/Bluetooth di Control Center merupakan kontrol visual, bukan pengalih radio Android sungguhan. Kecerahan di UI memengaruhi tampilan launcher, bukan kecerahan sistem.
- Terminal adalah simulasi perintah aman di dalam aplikasi, bukan shell root/Termux. Finder belum mengakses seluruh filesystem Android.
- Cuaca menggunakan layanan geocoding dan forecast Open-Meteo tanpa API key.

## Build
- Android SDK Platform 35
- JDK 17
- `./gradlew assembleDebug`

APK debug: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions membangun APK pada branch `feature/saku-desktop-overhaul`; periksa tab Actions untuk status build dan artefak.
