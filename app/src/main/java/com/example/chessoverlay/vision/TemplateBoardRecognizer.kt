package com.example.chessoverlay.vision

import android.graphics.Bitmap
import com.example.chessoverlay.core.Square
import kotlin.math.abs

/**
 * Pengenal papan berbasis template matching.
 *
 * Cara kerja:
 *  1. Saat posisi AWAL tampil di layar, [learnFromStart] mempelajari tampilan 12 jenis bidak
 *     serta warna kotak kosong (gelap/terang) dari tema papan yang sedang dipakai.
 *  2. Setelah itu [recognize] mengklasifikasikan tiap kotak dengan membuang piksel latar
 *     (mask) lalu membandingkan bentuk bidak dengan template.
 *
 * Akurasi terbaik bila tema papan/bidak tetap sama dengan saat kalibrasi.
 */
class TemplateBoardRecognizer {

    private val cell = 20            // ukuran satu kotak setelah di-scale (px)
    private val margin = 2           // buang tepi kotak (highlight/koordinat)
    private val grid = cell - 2 * margin
    private val n = grid * grid

    /** Latar kotak kosong: indeks 0 = gelap, 1 = terang. */
    private val background = arrayOfNulls<FloatArray>(2)
    private val templates = HashMap<Char, MutableList<FloatArray>>()

    val isCalibrated: Boolean
        get() = background[0] != null && background[1] != null && templates.size == 12

    /** Ambil sampel grayscale [baris][kolom] dari bitmap papan (sudah dipotong tepat pada papan). */
    fun sample(board: Bitmap): Array<Array<FloatArray>> {
        val size = cell * 8
        val scaled = Bitmap.createScaledBitmap(board, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled !== board) scaled.recycle()

        return Array(8) { row ->
            Array(8) { col ->
                val out = FloatArray(n)
                var k = 0
                for (y in margin until cell - margin) {
                    for (x in margin until cell - margin) {
                        val c = px[(row * cell + y) * size + (col * cell + x)]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        out[k++] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    }
                }
                out
            }
        }
    }

    /** Pelajari template dari posisi awal. Mengembalikan true bila berhasil. */
    fun learnFromStart(samples: Array<Array<FloatArray>>, whiteAtBottom: Boolean): Boolean {
        val bgSum = Array(2) { FloatArray(n) }
        val bgCount = IntArray(2)

        // 1) latar dari rank 3..6 (kotak kosong pada posisi awal)
        forEachSquare(whiteAtBottom) { row, col, sq ->
            if (sq.rank in 2..5) {
                val ci = colorIndex(sq)
                val s = samples[row][col]
                for (i in 0 until n) bgSum[ci][i] += s[i]
                bgCount[ci]++
            }
        }
        if (bgCount[0] == 0 || bgCount[1] == 0) return false
        for (ci in 0..1) background[ci] = FloatArray(n) { bgSum[ci][it] / bgCount[ci] }

        // 2) template bidak dari rank 1,2,7,8
        templates.clear()
        forEachSquare(whiteAtBottom) { row, col, sq ->
            val piece = startPiece(sq) ?: return@forEachSquare
            val feat = feature(samples[row][col], background[colorIndex(sq)]!!)
            templates.getOrPut(piece) { mutableListOf() }.add(feat)
        }
        return isCalibrated
    }

    /** Kenali seluruh papan. Null bila ada kotak yang tidak dikenali. */
    fun recognize(samples: Array<Array<FloatArray>>, whiteAtBottom: Boolean): Map<Square, Char>? {
        if (!isCalibrated) return null
        val result = HashMap<Square, Char>()
        var ok = true
        forEachSquare(whiteAtBottom) { row, col, sq ->
            if (!ok) return@forEachSquare
            val piece = classify(samples[row][col], colorIndex(sq))
            if (piece == null) ok = false
            else if (piece != '.') result[sq] = piece
        }
        return if (ok) result else null
    }

    private fun classify(s: FloatArray, ci: Int): Char? {
        val feat = feature(s, background[ci]!!)
        var fg = 0
        for (v in feat) if (v >= 0f) fg++
        if (fg < n * 0.03f) return '.'

        var best: Char? = null
        var bestD = Float.MAX_VALUE
        for ((piece, list) in templates) {
            for (t in list) {
                val d = distance(feat, t)
                if (d < bestD) { bestD = d; best = piece }
            }
        }
        return if (bestD <= MAX_DISTANCE) best else null
    }

    /** Piksel latar -> -1, piksel bidak -> nilai grayscale. */
    private fun feature(s: FloatArray, bg: FloatArray): FloatArray =
        FloatArray(n) { i -> if (abs(s[i] - bg[i]) > FG_THRESHOLD) s[i] else -1f }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until n) {
            val ax = a[i] >= 0f
            val bx = b[i] >= 0f
            sum += when {
                ax && bx -> abs(a[i] - b[i])
                ax != bx -> 1f
                else -> 0f
            }
        }
        return sum / n
    }

    private inline fun forEachSquare(whiteAtBottom: Boolean, block: (row: Int, col: Int, sq: Square) -> Unit) {
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = if (whiteAtBottom) Square(col, 7 - row) else Square(7 - col, row)
            block(row, col, sq)
        }
    }

    private fun colorIndex(sq: Square) = if ((sq.file + sq.rank) % 2 == 0) 0 else 1

    private fun startPiece(sq: Square): Char? {
        val back = "rnbqkbnr"
        return when (sq.rank) {
            0 -> back[sq.file].uppercaseChar()
            1 -> 'P'
            6 -> 'p'
            7 -> back[sq.file]
            else -> null
        }
    }

    companion object {
        private const val FG_THRESHOLD = 0.12f
        private const val MAX_DISTANCE = 0.45f
    }
}
