# Saku Desktop

Launcher desktop Android yang ringan. Desain dan alur fiturnya terinspirasi konsep umum dari Taskbar Android; kode dan aset Taskbar tidak disalin.

## Fitur

- Workspace desktop dengan jam, tanggal, wallpaper, tema gelap/cerah, dan warna aksen.
- Start Menu untuk mencari dan membuka aplikasi, dengan tab semua aplikasi atau aplikasi yang disematkan.
- Dock bawah berisi Start dan aplikasi favorit.
- Pin/unpin aplikasi dan pengaturan urutan ikon.
- Dock mengambang opsional di atas aplikasi lain, dengan notifikasi dan kontrol untuk mematikannya.
- Ketuk ikon untuk membuka aplikasi; tahan ikon untuk mencoba mode jendela bebas.
- Pilihan fullscreen atau freeform melalui menu konteks aplikasi.
- Layout menyesuaikan orientasi portrait dan landscape.
- Pintasan keyboard: `Ctrl+Space` membuka Start, `Ctrl+F` mencari aplikasi, `Ctrl+,` membuka pengaturan, `Esc` menutup Start, dan `F5` menyegarkan daftar aplikasi.

## Perubahan arsitektur

Panel mouse virtual, trackpad, keyboard virtual/IME, layanan aksesibilitas input, dan integrasi Shizuku telah dihapus. Saku sekarang fokus sebagai launcher/taskbar, bukan alat input. Keyboard atau mouse fisik yang disediakan Android tetap menggunakan jalur input sistem biasa.

## Batasan Android

Permintaan freeform menggunakan API publik Android dan dapat diabaikan jika ROM atau aplikasi tidak mendukungnya. Dock mengambang memerlukan izin Display over other apps; izin ini bisa dicabut kapan saja. Fitur dasar launcher, Start Menu, dan pin tidak membutuhkan root atau Shizuku.

## Build

- Android SDK Platform 35
- JDK 17

Jalankan `./gradlew assembleDebug`. APK debug berada di `app/build/outputs/apk/debug/app-debug.apk`. Build CI otomatis berjalan saat ada push ke `main` atau branch overhaul dan saat pull request ke `main`.
