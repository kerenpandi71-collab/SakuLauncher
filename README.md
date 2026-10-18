# Saku Launcher

Launcher Android bergaya desktop yang mempertahankan ikon aplikasi Android asli dari sistem.

## Fitur launcher

- Ikon aplikasi Android asli di layar utama, Start Menu, dan dock.
- Start Menu dengan pencarian aplikasi, tab disematkan/semua aplikasi.
- Halaman layar utama tambahan, susun ikon dengan drag-and-drop, dan geser antarhalaman.
- Dock bawah dengan aplikasi favorit dan menu tekan-lama.
- Widget Android melalui AppWidgetHost.
- Wallpaper dari galeri, tema terang/gelap, warna aksen, ukuran ikon, transparansi dock, label ikon, dan pengaturan tampilan.
- Dock mengambang opsional di atas aplikasi lain.
- Keyboard Android buatan Saku dengan tombol QWERTY, angka/simbol, Shift, Backspace, clipboard, dan kontrol ukuran/posisi.

## Saku YouTube

Saku menyediakan halaman YouTube tertanam berbasis GeckoView. Tautan YouTube dibuka di activity aplikasi Saku, bukan diteruskan ke browser default Android. Pada peluncuran awal, pengguna dapat menyetujui pemasangan ekstensi uBlock Origin dari Mozilla Add-ons. Status ekstensi ditampilkan di UI; jika pemasangan gagal atau pengguna menolak, aplikasi tidak mengklaim iklan sudah diblokir.

Fitur saat ini:
- Navigasi dan pencarian YouTube di dalam Saku.
- Tombol beranda, muat ulang, dan kembali.
- Sakelar User-Agent Desktop/Mobile.
- Dukungan video layar penuh.
- Alur pemasangan uBlock Origin resmi yang membutuhkan koneksi internet dan persetujuan pengguna.

Inspirasi arsitektur dan UX:
- [LiteTube](https://github.com/HydeYYHH/litube) — referensi open-source untuk aplikasi YouTube mandiri. Saku tidak menyalin kode LiteTube.
- [GeckoView WebExtensionController](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebExtensionController.html) — API untuk memasang dan mengelola WebExtensions.
- [uBlock Origin di Mozilla Add-ons](https://addons.mozilla.org/en-US/firefox/addon/ublock-origin/) — sumber resmi ekstensi.

Catatan: ad-blocking bergantung pada keberhasilan pemasangan dan kompatibilitas ekstensi/YouTube, sehingga tidak bisa dijamin memblokir setiap iklan atau sponsor. Login dan video tertentu dapat tunduk pada pembatasan YouTube.

## Build

- Android SDK Platform 36
- JDK 17
- Gradle wrapper (versi di `gradle/wrapper/gradle-wrapper.properties`)
- Target ABI build ini: `arm64-v8a` (untuk perangkat ARM64 seperti Redmi 12C)

Jalankan:

```bash
./gradlew assembleDebug
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions membangun dan menerbitkan artefak APK debug pada workflow terkait.

## Lisensi dan atribusi

- Saku Launcher: lihat lisensi dan pemberitahuan di repository.
- GeckoView: Mozilla Public License 2.0 (MPL-2.0); GeckoView diambil sebagai dependency Maven, tidak disalin ke source tree.
- uBlock Origin: GPL-3.0; aplikasi mengunduh XPI resmi dari Mozilla Add-ons saat pengguna menyetujui pemasangan, bukan membundel XPI tersebut di APK.
- LiteTube disebut sebagai referensi; source code LiteTube tidak dimasukkan ke Saku.
