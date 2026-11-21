# Chess Overlay Analysis (Kotlin / Android)

Overlay real-time yang menangkap layar, mengenali papan catur, lalu menampilkan **panah langkah terbaik (Top-3)**, **bar evaluasi**, dan **garis analisis (PV)** di atas aplikasi catur apa pun.

## Arsitektur

```
MainActivity ──(izin MediaProjection)──► OverlayService (foreground)
                                          │
        ImageReader/VirtualDisplay ─► TemplateBoardRecognizer ─► PositionTracker ─► FEN
                                                                                   │
        OverlayView (panah, eval bar, PV) ◄──── StateFlow<PvLine> ◄──── UciEngine (Stockfish)
```

| File | Peran |
|---|---|
| `core/Models.kt` | Square, UciMove, Score, PvLine, Board(FEN) |
| `engine/UciEngine.kt` | Proses UCI, MultiPV, normalisasi skor ke sudut pandang putih, stop/restart aman |
| `vision/TemplateBoardRecognizer.kt` | Belajar template dari posisi awal, klasifikasi tiap kotak |
| `vision/PositionTracker.kt` | Stabilisasi antar-frame, inferensi giliran, pembuat FEN |
| `overlay/OverlayView.kt` | Gambar panah, bar evaluasi, teks; mode kalibrasi 2 ketukan |
| `overlay/OverlayService.kt` | Capture layar, jendela overlay + panel kontrol, loop analisis |

## Menyiapkan engine

1. Ambil build Stockfish untuk Android arm64 (executable, bukan library) dari rilis resmi/hasil kompilasi sendiri.
2. Ganti namanya menjadi `libstockfish.so` dan taruh di `app/src/main/jniLibs/arm64-v8a/`.
   (Nama `lib*.so` membuat Android mengekstraknya ke `nativeLibraryDir` sehingga bisa dieksekusi.)

## Cara pakai

1. Build & install, buka aplikasi, **Beri izin overlay**, lalu **Mulai overlay**.
2. Buka aplikasi catur. Tekan **Papan** dan ketuk sudut kiri-atas lalu kanan-bawah papan.
3. Saat **posisi awal** tampil, tekan **Belajar** (kalau Anda main sebagai hitam, tekan **Balik** dulu).
4. Setiap ada gerakan baru, panah dan evaluasi diperbarui. **Giliran** membalik giliran jalan bila tebakan salah.

## Keterbatasan yang perlu diketahui

- Belum dikompilasi/diuji di sini (tidak ada Android SDK/jaringan di sandbox) - kemungkinan perlu perbaikan kecil saat build pertama.
- Overlay ikut tertangkap MediaProjection, jadi disembunyikan ~90 ms tiap siklus capture (500 ms); ini bisa terlihat berkedip tipis. Geser panel kontrol keluar dari area papan.
- Hak rokade disimpulkan dari posisi raja/benteng; en passant tidak dideteksi.
- Pengenalan memakai bentuk (tepi) bidak, jadi sorotan langkah terakhir tidak mengganggu. Tema bidak baru perlu "Belajar" ulang pada posisi awal. Batas papan harus pas (meleset >~10 px bisa menyebabkan salah baca): gunakan garis grid tipis di overlay untuk memeriksanya, dan ketuk ulang "Papan" bila miring.
- Persentase menang dihitung dari skor engine dengan kurva Lichess (perkiraan, bukan hasil pasti).
- Notasi langkah ditampilkan dalam UCI (`e2e4`). Untuk SAN tambahkan library aturan catur (mis. chesslib).

## Catatan etika

Gunakan untuk analisis, review partai, dan latihan. Memakai engine saat bermain online berperingkat melanggar aturan fair play platform (chess.com, lichess, dll.) dan dapat membuat akun diblokir.
