# Saku Desktop

Launcher desktop Android ringan. Rilis **v0.4.0** menyempurnakan swipe halaman, kustomisasi label Drawer, dan efek Liquid Glass di atas fitur workspace, widget, serta kontrol aplikasi yang sudah ada.

## Fitur

- Workspace multi-halaman; ikon dapat disusun, dipindah antarhalaman, dan dilengkapi widget Android.
- Dock/taskbar yang dapat disembunyikan dan diatur ukuran ikon, urutan, serta transparansinya.
- Start Menu untuk mencari, menampilkan daftar aplikasi, atau membuka daftar aplikasi yang disematkan.
- Label ikon Home dan Drawer dapat diatur **secara terpisah**.
- Tekan lama aplikasi untuk membuka jendela/freeform atau fullscreen, menambah/menghapus dari Home, memindah posisi/halaman, menyematkan ke dock, membuka info aplikasi/Play Store, atau meminta uninstall melalui konfirmasi Android.
- Transisi halaman Home dan tab drawer menggunakan slide/fade; swipe vertikal tidak seharusnya berpindah halaman. Pembukaan Start juga memakai animasi.
- Tema gelap/cerah, warna aksen, wallpaper, jumlah kolom, ukuran ikon, transparansi, aplikasi tersembunyi, dan widget.
- Liquid Glass menggunakan permukaan translucent. Floating dock memakai blur lintas-jendela pada Android 12+ jika fitur blur tersedia; jika tidak, ada fallback transparan.
- Floating dock opsional di atas aplikasi, dengan notifikasi kontrol untuk mematikannya.
- Pengelola proses bersifat opsional: membuka panelnya dapat meminta otorisasi `su` untuk membaca PID; tindakan stop/freeze memerlukan konfirmasi terpisah.

## Izin dan batas Android

Dock mengambang memakai izin **Display over other apps** yang diaktifkan pengguna di Settings Android. Uninstall hanya membuka konfirmasi sistem; aplikasi tidak dihapus diam-diam. Root hanya digunakan oleh panel Pengelola Proses. **Shizuku belum terintegrasi pada branch ini**; fungsi launcher, label, animasi, dan glass UI tidak membutuhkannya.

Peluncuran freeform memakai API Android publik dan bisa diabaikan jika perangkat, ROM, atau aplikasi tidak mendukungnya. Efek blur lintas-jendela bergantung Android 12+, dukungan OEM/GPU, dan status sistem; tampilan tetap memakai surface translucent sebagai fallback.

## Build

- Android SDK Platform 35
- JDK 17

```bash
./gradlew assembleDebug
```

APK debug berada di `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions membangun APK otomatis saat push ke `main`.
