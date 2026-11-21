package com.example.chessoverlay.vision

import android.graphics.Bitmap
import com.example.chessoverlay.core.Square
import kotlin.math.abs
import kotlin.math.max

/**
 * Pengenal papan berbasis BENTUK (peta tepi), bukan warna latar.
 *
 *  - Kotak kosong  : hampir tidak ada tepi di bagian tengah kotak. Sorotan langkah terakhir,
 *                    kotak terpilih, atau warna latar apa pun tetap dianggap kosong (latar datar).
 *  - Warna bidak   : kecerahan tengah kotak (bidak hitam gelap, bidak putih terang).
 *  - Jenis bidak   : peta tepi dicocokkan dengan template yang dipelajari dari POSISI AWAL.
 *
 * Divalidasi pada screenshot chess.com (tema hijau) termasuk kotak tersorot dan area tertutup panah.
 */
class TemplateBoardRecognizer {

    private val cell = 32            // ukuran kotak setelah di-scale (px)
    private val margin = 4           // buang tepi kotak (koordinat, garis)
    private val grid = cell - 2 * margin       // 24
    private val eSize = grid - 2               // 22: ukuran peta tepi
    private val c0 = 3                          // jendela tengah untuk menghitung tepi
    private val c1 = grid - 4

    private class Template(val edges: BooleanArray, val dilated: BooleanArray)

    private val templates = HashMap<Char, MutableList<Template>>()

    val isCalibrated: Boolean
        get() = templates.size == 12

    /** Skala papan ke 256x256 (box filter agar tidak aliasing), lalu pecah jadi 64 kotak [baris][kolom]. */
    fun sample(board: Bitmap): Array<Array<FloatArray>> {
        val size = cell * 8
        val w = board.width
        val h = board.height
        val px = IntArray(w * h)
        board.getPixels(px, 0, w, 0, 0, w, h)

        val gray = FloatArray(size * size)
        for (oy in 0 until size) {
            val ys = oy * h / size
            val ye = max(ys + 1, (oy + 1) * h / size)
            for (ox in 0 until size) {
                val xs = ox * w / size
                val xe = max(xs + 1, (ox + 1) * w / size)
                var sum = 0f
                for (y in ys until ye) {
                    var i = y * w + xs
                    for (x in xs until xe) {
                        val c = px[i++]
                        sum += 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                    }
                }
                gray[oy * size + ox] = sum / ((ye - ys) * (xe - xs)) / 255f
            }
        }

        return Array(8) { row ->
            Array(8) { col ->
                val out = FloatArray(grid * grid)
                for (y in 0 until grid) {
                    val src = (row * cell + margin + y) * size + col * cell + margin
                    for (x in 0 until grid) out[y * grid + x] = gray[src + x]
                }
                out
            }
        }
    }

    /**
     * Pelajari template dari posisi awal. Mengembalikan null bila berhasil,
     * atau pesan kesalahan bila posisi awal tidak terdeteksi / batas papan meleset.
     */
    fun learnFromStart(samples: Array<Array<FloatArray>>, whiteAtBottom: Boolean): String? {
        val learned = HashMap<Char, MutableList<Template>>()
        var emptyOk = 0
        var emptyTotal = 0
        var pieceOk = 0

        forEachSquare(whiteAtBottom) { row, col, sq ->
            val s = samples[row][col]
            val e = edges(s)
            val busy = centerEdges(e) >= EMPTY_MIN
            val piece = startPiece(sq)
            if (piece == null) {
                emptyTotal++
                if (!busy) emptyOk++
            } else if (busy) {
                pieceOk++
                learned.getOrPut(piece) { mutableListOf() }.add(Template(e, dilate(e)))
            }
        }
        if (pieceOk < 28 || emptyOk < emptyTotal - 3 || learned.size != 12) {
            return "Posisi awal tidak terdeteksi (bidak $pieceOk/32, kotak kosong $emptyOk/$emptyTotal). " +
                "Pastikan papan penuh terlihat, batas papan pas, dan arah (Balik) benar."
        }
        templates.clear()
        templates.putAll(learned)
        return null
    }

    /** Kenali seluruh papan. Null bila ada kotak yang tidak bisa dikenali. */
    fun recognize(samples: Array<Array<FloatArray>>, whiteAtBottom: Boolean): Map<Square, Char>? {
        if (!isCalibrated) return null
        val result = HashMap<Square, Char>()
        var ok = true
        forEachSquare(whiteAtBottom) { row, col, sq ->
            if (!ok) return@forEachSquare
            val piece = classify(samples[row][col])
            if (piece == null) ok = false
            else if (piece != '.') result[sq] = piece
        }
        return if (ok) result else null
    }

    private fun classify(s: FloatArray): Char? {
        val e = edges(s)
        if (centerEdges(e) < EMPTY_MIN) return '.'

        val black = centerBrightness(s) < 0.5f
        val dil = dilate(e)

        var best: Char? = null
        var bestD = Float.MAX_VALUE
        for ((piece, list) in templates) {
            if (piece.isLowerCase() != black) continue
            for (t in list) {
                val d = distance(e, dil, t)
                if (d < bestD) { bestD = d; best = piece }
            }
        }
        return if (bestD <= MAX_DISTANCE) best else null
    }

    /** Peta tepi biner (22x22): gradien |dx|+|dy| di atas ambang. */
    private fun edges(s: FloatArray): BooleanArray {
        val e = BooleanArray(eSize * eSize)
        for (y in 0 until eSize) {
            for (x in 0 until eSize) {
                val gx = s[(y + 1) * grid + x + 2] - s[(y + 1) * grid + x]
                val gy = s[(y + 2) * grid + x + 1] - s[y * grid + x + 1]
                e[y * eSize + x] = abs(gx) + abs(gy) > EDGE_THRESHOLD
            }
        }
        return e
    }

    private fun centerEdges(e: BooleanArray): Int {
        var n = 0
        for (y in c0 until c1) for (x in c0 until c1) if (e[y * eSize + x]) n++
        return n
    }

    private fun centerBrightness(s: FloatArray): Float {
        val mid = grid / 2
        val v = FloatArray(36)
        var k = 0
        for (y in mid - 3 until mid + 3) for (x in mid - 3 until mid + 3) v[k++] = s[y * grid + x]
        v.sort()
        return (v[17] + v[18]) / 2f
    }

    private fun dilate(e: BooleanArray): BooleanArray {
        val out = BooleanArray(e.size)
        for (y in 0 until eSize) for (x in 0 until eSize) {
            var on = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until eSize && xx in 0 until eSize && e[yy * eSize + xx]) { on = true; break@loop }
            }
            out[y * eSize + x] = on
        }
        return out
    }

    private fun distance(a: BooleanArray, aDil: BooleanArray, t: Template): Float {
        var na = 0; var nb = 0; var da = 0; var db = 0
        for (i in a.indices) {
            if (a[i]) { na++; if (!t.dilated[i]) da++ }
            if (t.edges[i]) { nb++; if (!aDil[i]) db++ }
        }
        return (da + db).toFloat() / (na + nb + 1)
    }

    private inline fun forEachSquare(whiteAtBottom: Boolean, block: (row: Int, col: Int, sq: Square) -> Unit) {
        for (row in 0 until 8) for (col in 0 until 8) {
            val sq = if (whiteAtBottom) Square(col, 7 - row) else Square(7 - col, row)
            block(row, col, sq)
        }
    }

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
        private const val EDGE_THRESHOLD = 0.25f
        private const val EMPTY_MIN = 12
        private const val MAX_DISTANCE = 0.35f
    }
}
