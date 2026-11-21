package com.example.chessoverlay.engine

import com.example.chessoverlay.core.PvLine
import com.example.chessoverlay.core.Score
import com.example.chessoverlay.core.UciMove
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.BufferedWriter
import java.io.File

/**
 * Pembungkus engine UCI (mis. Stockfish) yang berjalan sebagai proses anak.
 * Skor selalu dinormalisasi ke sudut pandang PUTIH.
 */
class UciEngine(
    private val binary: File,
    private val scope: CoroutineScope,
) : AutoCloseable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    private val lock = Any()
    private var searching = false
    private var pendingFen: String? = null
    private var pendingMoveTime: Long? = null
    private var whiteToMove = true

    private val uciOk = CompletableDeferred<Unit>()
    private val readyOk = CompletableDeferred<Unit>()

    private val _lines = MutableStateFlow<Map<Int, PvLine>>(emptyMap())

    /** Hasil analisis terbaru, kunci = nomor MultiPV (1 = terbaik). */
    val lines: StateFlow<Map<Int, PvLine>> = _lines.asStateFlow()

    suspend fun start(multiPv: Int = 3, threads: Int = 2, hashMb: Int = 64) {
        require(binary.exists()) { "Biner engine tidak ditemukan: ${binary.absolutePath}" }
        val p = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        process = p
        writer = p.outputStream.bufferedWriter()

        scope.launch(Dispatchers.IO) {
            try {
                p.inputStream.bufferedReader().useLines { seq -> seq.forEach(::onLine) }
            } catch (_: Exception) {
                // proses dihentikan
            }
        }

        send("uci")
        withTimeout(5_000) { uciOk.await() }
        send("setoption name Threads value $threads")
        send("setoption name Hash value $hashMb")
        send("setoption name MultiPV value $multiPv")
        send("isready")
        withTimeout(5_000) { readyOk.await() }
    }

    fun setMultiPv(n: Int) = send("setoption name MultiPV value ${n.coerceIn(1, 5)}")

    /** Mulai analisis posisi [fen]. Jika [moveTimeMs] null, analisis berjalan terus sampai diganti/dihentikan. */
    fun analyze(fen: String, moveTimeMs: Long? = null) {
        synchronized(lock) {
            if (searching) {
                pendingFen = fen
                pendingMoveTime = moveTimeMs
                send("stop")
            } else {
                startSearch(fen, moveTimeMs)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            pendingFen = null
            if (searching) send("stop")
        }
    }

    // Harus dipanggil di dalam synchronized(lock)
    private fun startSearch(fen: String, moveTimeMs: Long?) {
        whiteToMove = fen.split(' ').getOrNull(1) != "b"
        searching = true
        _lines.value = emptyMap()
        send("position fen $fen")
        send(if (moveTimeMs != null) "go movetime $moveTimeMs" else "go infinite")
    }

    private fun onLine(line: String) {
        when {
            line == "uciok" -> uciOk.complete(Unit)
            line == "readyok" -> readyOk.complete(Unit)
            line.startsWith("bestmove") -> synchronized(lock) {
                searching = false
                val fen = pendingFen
                if (fen != null) {
                    pendingFen = null
                    startSearch(fen, pendingMoveTime)
                }
            }
            line.startsWith("info ") && " pv " in line -> {
                val stale = synchronized(lock) { !searching || pendingFen != null }
                if (stale) return
                parseInfo(line)?.let { pv -> _lines.update { it + (pv.multiPv to pv) } }
            }
        }
    }

    private fun parseInfo(line: String): PvLine? {
        val t = line.split(' ')
        var depth = 0
        var multi = 1
        var nodes = 0L
        var nps = 0L
        var score: Score? = null
        var pv = emptyList<UciMove>()

        var i = 1
        while (i < t.size) {
            when (t[i]) {
                "depth" -> depth = t.getOrNull(i + 1)?.toIntOrNull() ?: depth
                "multipv" -> multi = t.getOrNull(i + 1)?.toIntOrNull() ?: multi
                "nodes" -> nodes = t.getOrNull(i + 1)?.toLongOrNull() ?: nodes
                "nps" -> nps = t.getOrNull(i + 1)?.toLongOrNull() ?: nps
                "score" -> {
                    val kind = t.getOrNull(i + 1)
                    val raw = t.getOrNull(i + 2)?.toIntOrNull()
                    if (raw != null) {
                        val v = if (whiteToMove) raw else -raw
                        score = when (kind) {
                            "cp" -> Score.Cp(v)
                            "mate" -> Score.Mate(v)
                            else -> null
                        }
                    }
                    i += 2
                }
                "pv" -> {
                    pv = t.drop(i + 1).mapNotNull { runCatching { UciMove.parse(it) }.getOrNull() }
                    i = t.size
                }
            }
            i++
        }
        val s = score ?: return null
        if (pv.isEmpty()) return null
        return PvLine(multi, depth, s, pv, nodes, nps)
    }

    private fun send(cmd: String) {
        val w = writer ?: return
        synchronized(w) {
            try {
                w.write(cmd)
                w.newLine()
                w.flush()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        send("quit")
        process?.destroy()
        process = null
        writer = null
    }
}
