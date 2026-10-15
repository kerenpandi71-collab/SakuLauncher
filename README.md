# Saku Launcher

Launcher Android native yang ringan, dengan tata letak lapang dan sudut lembut yang terinspirasi pendekatan ergonomis One UI—tanpa menyalin aset atau merek Samsung.

## Yang sudah ada

- Dapat dipilih sebagai aplikasi **Home** default Android.
- Jam dan tanggal berbahasa Indonesia.
- Pencarian aplikasi terpasang dan grid aplikasi dengan ikon asli.
- Beranda ringkas menampilkan hingga delapan aplikasi; tombol **Semua aplikasi** membuka seluruh daftar.
- Implementasi native Kotlin + Android Views, tanpa framework UI tambahan.
- Kueri paket dibatasi pada aplikasi yang memang mendaftarkan kategori launcher.

## Persyaratan dan build

- Android SDK Platform 35 (build CI memasangnya otomatis).
- JDK 17.
- Gradle Wrapper.

```bash
./gradlew assembleDebug
```

APK debug akan berada di `app/build/outputs/apk/debug/app-debug.apk`. Instal di perangkat uji, lalu pilih **Saku Launcher** sebagai aplikasi Home untuk mencobanya.

## Status

MVP awal, versi `0.1.0`. Belum mencakup widget Android, gesture khusus, folder, atau pengaturan dock. Silakan uji pada perangkat sebelum dipakai sebagai launcher utama.
