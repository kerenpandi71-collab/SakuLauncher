# SakuTube Auto Dub Gratis (prototipe Firefox)

- Membaca subtitle/caption YouTube, deteksi bahasa sumber otomatis, terjemahkan ke Indonesia, lalu dibacakan dengan TTS browser/perangkat.
- Tidak memakai API key berbayar. Perlu internet untuk terjemahan; endpoint gratis tidak resmi dapat dibatasi/berubah.
- Hanya bekerja bila YouTube menyediakan caption yang dapat dibaca. Ini bukan speech-to-text dari audio mentah.
- Voice Indonesia bergantung pada mesin TTS perangkat. Sinkronisasi belum sempurna dan extension belum diuji di perangkat pengguna.

## Uji sementara di Firefox desktop
1. Buka about:debugging#/runtime/this-firefox
2. Klik Load Temporary Add-on… dan pilih manifest.json.
3. Buka YouTube, klik ikon extension, aktifkan dubbing, dan pastikan CC menyala.

Untuk pemasangan permanen, paket perlu dikirim ke proses signing/distribusi Mozilla. Firefox Android mungkin memiliki keterbatasan TTS/API berbeda.