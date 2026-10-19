# SakuTube Auto Dub Gratis (prototipe Firefox)

- Membaca subtitle/caption YouTube, mendeteksi bahasa sumber dari teks melalui layanan terjemahan, menerjemahkannya ke bahasa Indonesia, lalu membacakannya memakai TTS browser/perangkat.
- Tidak memakai API key berbayar. Perlu internet untuk terjemahan; endpoint gratis tanpa API key tidak resmi, dapat dibatasi atau berubah.
- Hanya bekerja jika YouTube menyediakan caption yang dapat dibaca. Ini bukan speech-to-text dari audio mentah.
- Pilihan **Redupkan audio asli saat dubbing** menurunkan volume video YouTube selama TTS berbicara, lalu mengembalikan volume sebelumnya. Karena suara manusia, musik, dan efek sudah tercampur di audio video, opsi ini meredupkan semuanya—bukan menghapus dialog saja.
- Pemisahan dialog dari musik/efek membutuhkan model pemisahan sumber audio. Fitur itu belum tersedia pada prototipe ini; jangan menganggap opsi redup audio sebagai penghapus suara manusia.
- Voice Indonesia bergantung pada mesin TTS perangkat. Sinkronisasi belum sempurna dan extension belum diuji di perangkat pengguna.

## Uji sementara di Firefox desktop
1. Buka `about:debugging#/runtime/this-firefox`.
2. Klik **Load Temporary Add-on…** dan pilih `manifest.json`.
3. Buka YouTube, klik ikon extension, aktifkan dubbing, dan pastikan CC menyala.
4. Jika ingin audio asli lebih pelan selama TTS, aktifkan **Redupkan audio asli saat dubbing**. Musik dan efek juga ikut mengecil.

Untuk pemasangan permanen, paket perlu dikirim ke proses signing/distribusi Mozilla. Firefox Android mungkin memiliki keterbatasan TTS/API berbeda.
