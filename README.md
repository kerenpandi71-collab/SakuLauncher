# Saku Launcher

Launcher Android native yang ringan, dengan tata letak lapang dan sudut lembut yang terinspirasi pendekatan ergonomis One UI—tanpa menyalin aset atau merek Samsung.

## Yang sudah ada

- Dapat dipilih sebagai aplikasi **Home** default Android.
- Jam dan tanggal berbahasa Indonesia.
- Dua mode: **Normal** (beranda ringkas) dan **Desktop** dengan taskbar, pintasan favorit, serta grid aplikasi.
- Pencarian aplikasi terpasang dan grid ikon aplikasi.
- Personalisasi jumlah kolom (3–6), ukuran ikon, label aplikasi, tema terang/gelap, dan warna aksen.
- Tekan lama ikon untuk menambah atau menghapus favorit; favorit tampil di Desktop/taskbar.
- Opsi meminta Android membuka aplikasi dengan batas jendela bebas saat mode Desktop aktif.
- Implementasi native Kotlin + Android Views, tanpa framework UI tambahan.
- Kueri paket dibatasi pada aplikasi yang memang mendaftarkan kategori launcher.

## Jendela bebas dan mode Desktop

Saku mengirim `launchBounds` Android saat opsi **Jendela bebas** aktif di mode Desktop. Jendela mengambang sesungguhnya dikendalikan sistem: perlu perangkat/ROM yang mendukung freeform multi-window atau lingkungan desktop seperti Samsung DeX. Jika sistem tidak mendukungnya, aplikasi tujuan tetap terbuka dengan mode jendela standarnya. Mode Desktop Saku tetap menampilkan taskbar dan pintasan walau dijalankan di ponsel biasa.

## Persyaratan dan build

- Android SDK Platform 35 (build CI memasangnya otomatis).
- JDK 17.
- Gradle Wrapper.

```bash
./gradlew assembleDebug
```

APK debug akan berada di `app/build/outputs/apk/debug/app-debug.apk`. Instal di perangkat uji, lalu pilih **Saku Launcher** sebagai aplikasi Home untuk mencobanya.

## Status

Versi `0.2.0`. Widget Android, folder, icon pack, gesture khusus, dan pengaturan dock lanjutan belum termasuk.
