package com.example.chessoverlay.vision

import com.example.chessoverlay.core.Square

/**
 * Menstabilkan hasil pengenalan papan antar-frame (menghindari frame saat animasi gerakan),
 * menyimpulkan giliran jalan dari perubahan posisi, dan menghasilkan FEN.
 */
class PositionTracker(private val stableFrames: Int = 2) {

    private var candidate: Map<Square, Char>? = null
    private var candidateCount = 0
    private var committed: Map<Square, Char>? = null

    var whiteToMove = true
        private set

    /** Dipakai saat giliran hasil tebakan salah, atau saat mulai dari tengah partai. */
    fun setSideToMove(white: Boolean) {
        whiteToMove = white
    }

    fun reset() {
        candidate = null
        candidateCount = 0
        committed = null
        whiteToMove = true
    }

    /** Mengembalikan FEN baru hanya ketika posisi stabil, valid, dan berbeda dari sebelumnya. */
    fun submit(pieces: Map<Square, Char>): String? {
        if (pieces != candidate) {
            candidate = pieces
            candidateCount = 1
            return null
        }
        candidateCount++
        if (candidateCount < stableFrames || pieces == committed) return null
        if (!isPlausible(pieces)) return null

        committed?.let { prev -> inferSideToMove(prev, pieces)?.let { whiteToMove = it } }
        committed = pieces
        return toFen(pieces, whiteToMove)
    }

    /** Buat ulang FEN dari posisi terakhir (mis. setelah giliran diubah manual). */
    fun currentFen(): String? = committed?.let { toFen(it, whiteToMove) }

    private fun inferSideToMove(prev: Map<Square, Char>, now: Map<Square, Char>): Boolean? {
        val vacated = prev.filter { (sq, _) -> sq !in now }.values
        if (vacated.isEmpty()) return null
        val whiteMoved = vacated.all { it.isUpperCase() }
        val blackMoved = vacated.all { it.isLowerCase() }
        return when {
            whiteMoved -> false   // putih baru jalan -> giliran hitam
            blackMoved -> true
            else -> null
        }
    }

    private fun isPlausible(p: Map<Square, Char>): Boolean {
        val values = p.values
        if (values.count { it == 'K' } != 1 || values.count { it == 'k' } != 1) return false
        if (values.count { it.isUpperCase() } > 16 || values.count { it.isLowerCase() } > 16) return false
        if (p.any { (sq, c) -> (c == 'P' || c == 'p') && (sq.rank == 0 || sq.rank == 7) }) return false
        return true
    }

    companion object {
        fun toFen(p: Map<Square, Char>, whiteToMove: Boolean): String {
            val sb = StringBuilder()
            for (rank in 7 downTo 0) {
                var empty = 0
                for (file in 0..7) {
                    val c = p[Square(file, rank)]
                    if (c == null) empty++
                    else {
                        if (empty > 0) { sb.append(empty); empty = 0 }
                        sb.append(c)
                    }
                }
                if (empty > 0) sb.append(empty)
                if (rank > 0) sb.append('/')
            }
            sb.append(if (whiteToMove) " w " else " b ")

            // Hak rokade disimpulkan dari posisi raja & benteng (tidak bisa tahu riwayat gerak).
            val castle = StringBuilder()
            if (p[Square(4, 0)] == 'K') {
                if (p[Square(7, 0)] == 'R') castle.append('K')
                if (p[Square(0, 0)] == 'R') castle.append('Q')
            }
            if (p[Square(4, 7)] == 'k') {
                if (p[Square(7, 7)] == 'r') castle.append('k')
                if (p[Square(0, 7)] == 'r') castle.append('q')
            }
            sb.append(if (castle.isEmpty()) "-" else castle).append(" - 0 1")
            return sb.toString()
        }
    }
}
