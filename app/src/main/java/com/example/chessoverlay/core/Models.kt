package com.example.chessoverlay.core

/** Kotak papan. file 0..7 = a..h, rank 0..7 = 1..8 */
data class Square(val file: Int, val rank: Int) {
    init { require(file in 0..7 && rank in 0..7) { "Square out of range: $file,$rank" } }
    override fun toString() = "${'a' + file}${rank + 1}"

    companion object {
        fun parse(s: String): Square {
            require(s.length >= 2) { "Invalid square: $s" }
            return Square(s[0] - 'a', s[1] - '1')
        }
    }
}

data class UciMove(val from: Square, val to: Square, val promotion: Char? = null) {
    override fun toString() = "$from$to${promotion ?: ""}"

    companion object {
        fun parse(s: String): UciMove = UciMove(
            Square.parse(s.substring(0, 2)),
            Square.parse(s.substring(2, 4)),
            s.getOrNull(4)
        )
    }
}

sealed interface Score {
    data class Cp(val centipawns: Int) : Score
    data class Mate(val moves: Int) : Score

    /** Teks untuk ditampilkan, mis. "+0.34" atau "M3". */
    fun display(): String = when (this) {
        is Cp -> "%+.2f".format(centipawns / 100.0)
        is Mate -> if (moves > 0) "M$moves" else "-M${-moves}"
    }

    /**
     * Perkiraan peluang menang PUTIH (0..100) dari skor posisi, memakai kurva yang sama dengan Lichess.
     * Peluang hitam = 100 - nilai ini. (Remis tidak dimodelkan terpisah.)
     */
    fun whiteWinPercent(): Double = when (this) {
        is Mate -> if (moves > 0) 100.0 else 0.0
        is Cp -> 50.0 + 50.0 * (2.0 / (1.0 + Math.exp(-0.00368208 * centipawns)) - 1.0)
    }

    /** Nilai -1..1 untuk bar evaluasi (perspektif putih). */
    fun barValue(): Float = when (this) {
        is Mate -> if (moves > 0) 1f else -1f
        is Cp -> (2 / (1 + Math.exp(-0.004 * centipawns)) - 1).toFloat()
    }
}

data class PvLine(
    val multiPv: Int,
    val depth: Int,
    val score: Score,   // sudah dinormalisasi ke sudut pandang putih
    val moves: List<UciMove>,
    val nodes: Long = 0,
    val nps: Long = 0,
)

/** Papan 8x8 dari FEN. Dipakai untuk menggambar bidak & menentukan giliran. */
data class Board(val pieces: Map<Square, Char>, val whiteToMove: Boolean, val fen: String) {
    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun fromFen(fen: String): Board {
            val parts = fen.trim().split(Regex("\\s+"))
            require(parts.size >= 2) { "FEN tidak valid" }
            val rows = parts[0].split('/')
            require(rows.size == 8) { "FEN harus memiliki 8 baris" }
            val map = HashMap<Square, Char>()
            rows.forEachIndexed { i, row ->
                val rank = 7 - i
                var file = 0
                for (c in row) {
                    if (c.isDigit()) file += c - '0'
                    else {
                        require(c in "pnbrqkPNBRQK") { "Bidak tidak dikenal: $c" }
                        require(file < 8) { "Baris terlalu panjang" }
                        map[Square(file++, rank)] = c
                    }
                }
                require(file == 8) { "Baris FEN tidak berjumlah 8 kotak" }
            }
            require(parts[1] == "w" || parts[1] == "b") { "Giliran harus w/b" }
            return Board(map, parts[1] == "w", fen.trim())
        }
    }
}
