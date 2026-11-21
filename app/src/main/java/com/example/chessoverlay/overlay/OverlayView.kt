package com.example.chessoverlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.example.chessoverlay.core.PvLine
import com.example.chessoverlay.core.Square
import com.example.chessoverlay.core.UciMove
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * View layar-penuh transparan: menggambar panah langkah terbaik, bar evaluasi, dan teks analisis
 * di atas papan. Tembus sentuhan kecuali saat mode kalibrasi.
 */
class OverlayView(context: Context) : View(context) {

    var boardRect: RectF? = null
        set(value) { field = value; invalidate() }
    var flipped: Boolean = false
        set(value) { field = value; invalidate() }
    var lines: List<PvLine> = emptyList()
        set(value) { field = value; invalidate() }
    var statusText: String = ""
        set(value) { field = value; invalidate() }
    var whiteToMove: Boolean = true
        set(value) { field = value; invalidate() }
    var calibrating: Boolean = false
        set(value) { field = value; tapPoints.clear(); invalidate() }

    /** Dipanggil setelah pengguna menandai dua sudut papan. */
    var onBoardSelected: ((RectF) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val tapPoints = ArrayList<PointF>()

    private val arrowColors = intArrayOf(
        Color.argb(210, 34, 197, 94),   // terbaik: hijau
        Color.argb(170, 59, 130, 246),  // kedua: biru
        Color.argb(140, 245, 158, 11),  // ketiga: oranye
    )

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(205, 18, 18, 18)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * density
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = Color.argb(230, 220, 220, 220)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 2f * density
    }

    override fun onDraw(canvas: Canvas) {
        val board = boardRect

        if (calibrating) {
            canvas.drawColor(Color.argb(90, 0, 0, 0))
            textPaint.color = Color.WHITE
            canvas.drawText(
                "Ketuk sudut kiri-atas lalu kanan-bawah papan (${tapPoints.size}/2)",
                16f * density, 96f * density, textPaint,
            )
            tapPoints.forEach { canvas.drawCircle(it.x, it.y, 8f * density, edgePaint) }
            return
        }
        if (board == null) {
            canvas.drawText("Papan belum diatur", 16f * density, 96f * density, textPaint)
            return
        }

        canvas.drawRect(board, edgePaint)
        val sq = board.width() / 8f
        // Grid tipis: bantu memastikan batas papan pas (garis harus sejajar kotak sebenarnya).
        for (i in 1 until 8) {
            canvas.drawLine(board.left + i * sq, board.top, board.left + i * sq, board.bottom, gridPaint)
            canvas.drawLine(board.left, board.top + i * sq, board.right, board.top + i * sq, gridPaint)
        }

        // Panah dari yang terlemah dulu agar langkah terbaik tampil di atas.
        val top = lines.sortedBy { it.multiPv }.take(arrowColors.size)
        for (line in top.reversed()) {
            val move = line.moves.firstOrNull() ?: continue
            drawArrow(canvas, board, sq, move, arrowColors[line.multiPv - 1])
        }

        drawEvalBar(canvas, board, lines.minByOrNull { it.multiPv })
        drawInfo(canvas, board, top)
    }

    private fun center(board: RectF, sq: Float, s: Square): PointF {
        val x = if (flipped) 7 - s.file else s.file
        val y = if (flipped) s.rank else 7 - s.rank
        return PointF(board.left + (x + 0.5f) * sq, board.top + (y + 0.5f) * sq)
    }

    private fun drawArrow(canvas: Canvas, board: RectF, sq: Float, move: UciMove, color: Int) {
        val a = center(board, sq, move.from)
        val b = center(board, sq, move.to)
        arrowPaint.color = color
        headPaint.color = color
        arrowPaint.strokeWidth = sq * 0.16f

        val ang = atan2(b.y - a.y, b.x - a.x)
        val headLen = sq * 0.42f
        val headHalf = sq * 0.26f

        // Garis berhenti di pangkal kepala panah.
        val bx = b.x - cos(ang) * headLen * 0.8f
        val by = b.y - sin(ang) * headLen * 0.8f
        canvas.drawLine(a.x, a.y, bx, by, arrowPaint)

        val baseX = b.x - cos(ang) * headLen
        val baseY = b.y - sin(ang) * headLen
        val path = Path().apply {
            moveTo(b.x, b.y)
            lineTo(baseX + sin(ang) * headHalf, baseY - cos(ang) * headHalf)
            lineTo(baseX - sin(ang) * headHalf, baseY + cos(ang) * headHalf)
            close()
        }
        canvas.drawPath(path, headPaint)
    }

    private fun drawEvalBar(canvas: Canvas, board: RectF, best: PvLine?) {
        val w = 12f * density
        val bar = RectF(board.left - w - 4f * density, board.top, board.left - 4f * density, board.bottom)
        fill.color = Color.argb(230, 40, 40, 40)
        canvas.drawRect(bar, fill)
        if (best == null) return

        val whiteFrac = (best.score.barValue() + 1f) / 2f
        val h = bar.height() * whiteFrac
        fill.color = Color.argb(240, 245, 245, 245)
        if (flipped) canvas.drawRect(bar.left, bar.top, bar.right, bar.top + h, fill)
        else canvas.drawRect(bar.left, bar.bottom - h, bar.right, bar.bottom, fill)
        canvas.drawRect(bar, edgePaint)
    }

    private fun drawInfo(canvas: Canvas, board: RectF, top: List<PvLine>) {
        val rows = top.sortedBy { it.multiPv }
        val gap = 6f * density

        if (rows.isEmpty()) {
            if (statusText.isNotEmpty()) drawStatus(canvas, board, board.bottom + gap)
            return
        }

        val pad = 8f * density
        val titleH = 20f * density
        val subH = 16f * density
        val headerH = 18f * density
        val panelH = pad * 2 + headerH + rows.size * (titleH + subH)
        val panelW = board.width()

        val above = board.top - panelH - gap >= 0f
        val panelTop = if (above) board.top - panelH - gap else board.bottom + gap
        val panel = RectF(board.left, panelTop, board.left + panelW, panelTop + panelH)
        canvas.drawRoundRect(panel, 10f * density, 10f * density, panelPaint)

        val textW = panelW - pad * 2
        var y = panel.top + pad + 12f * density
        val turn = if (whiteToMove) "Putih" else "Hitam"
        subPaint.color = Color.argb(255, 235, 235, 235)
        canvas.drawText(fit("Giliran $turn  -  kedalaman ${rows[0].depth}", subPaint, textW), panel.left + pad, y, subPaint)
        subPaint.color = Color.argb(230, 200, 200, 200)
        y += headerH - 4f * density

        for (line in rows) {
            val color = arrowColors[(line.multiPv - 1).coerceIn(0, arrowColors.lastIndex)] or (0xFF shl 24)
            val move = line.moves.firstOrNull()
            val white = line.score.whiteWinPercent()
            val wTxt = Math.round(white).toInt()
            val bTxt = 100 - wTxt

            y += titleH - 4f * density
            titlePaint.color = color
            val moveTxt = move?.let { "${it.from}-${it.to}${it.promotion ?: ""}" } ?: "?"
            canvas.drawText(
                fit("${line.multiPv}. $moveTxt   Putih $wTxt%  |  Hitam $bTxt%", titlePaint, textW),
                panel.left + pad, y, titlePaint,
            )

            y += subH - 2f * density
            val pv = line.moves.take(6).joinToString(" ")
            canvas.drawText(
                fit("Eval ${line.score.display()}  -  $pv", subPaint, textW),
                panel.left + pad, y, subPaint,
            )
            y += 6f * density - 4f * density
        }

        if (statusText.isNotEmpty()) {
            val statusTop = if (above) board.bottom + gap else panel.bottom + gap
            drawStatus(canvas, board, statusTop)
        }
    }

    private fun drawStatus(canvas: Canvas, board: RectF, top: Float) {
        val pad = 8f * density
        val h = 22f * density
        val r = RectF(board.left, top, board.right, top + h)
        canvas.drawRoundRect(r, 8f * density, 8f * density, panelPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 12f * density
        canvas.drawText(fit(statusText, textPaint, r.width() - pad * 2), r.left + pad, r.bottom - 6f * density, textPaint)
        textPaint.textSize = 13f * density
    }

    /** Potong teks dengan "..." bila melebihi lebar. */
    private fun fit(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        val n = paint.breakText(text, true, maxW - paint.measureText("..."), null)
        return text.take(n.coerceAtLeast(0)) + "..."
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrating) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            tapPoints.add(PointF(event.x, event.y))
            if (tapPoints.size == 2) {
                val (p, q) = tapPoints
                val left = min(p.x, q.x)
                val top = min(p.y, q.y)
                val side = max(abs(p.x - q.x), abs(p.y - q.y))
                val rect = RectF(left, top, left + side, top + side)
                tapPoints.clear()
                calibrating = false
                onBoardSelected?.invoke(rect)
            }
            invalidate()
        }
        return true
    }
}
