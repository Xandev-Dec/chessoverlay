package com.example.chessoverlay.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.chessoverlay.core.PvLine
import com.example.chessoverlay.engine.UciEngine
import com.example.chessoverlay.vision.PositionTracker
import com.example.chessoverlay.vision.TemplateBoardRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service foreground: menangkap layar (MediaProjection), mengenali papan, menjalankan engine,
 * dan menggambar hasilnya lewat window overlay.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private lateinit var overlay: OverlayView
    private lateinit var controls: LinearLayout

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var engine: UciEngine? = null
    private val recognizer = TemplateBoardRecognizer()
    private val tracker = PositionTracker()

    private var screenW = 0
    private var screenH = 0
    private var captureJob: Job? = null
    @Volatile private var paused = false
    @Volatile private var learnRequested = false
    @Volatile private var whiteAtBottom = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtraCompat<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        setUpWindow()
        setUpProjection(resultCode, data)
        startEngine()
        startCaptureLoop()
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- foreground

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Chess Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Chess Overlay aktif")
            .setContentText("Menganalisis papan di layar")
            .addAction(0, "Hentikan", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    // ---------------------------------------------------------------- windows

    @Suppress("DEPRECATION")
    private fun setUpWindow() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        overlay = OverlayView(this)
        loadPrefs()
        overlay.onBoardSelected = { rect ->
            overlay.boardRect = rect
            setOverlayTouchable(false)
            savePrefs()
            toast("Papan diatur. Tampilkan posisi awal, lalu tekan 'Belajar'.")
        }
        wm.addView(overlay, overlayParams(touchable = false))

        val ov = overlay  // di dalam apply{}, "overlay" terbaca sebagai View.overlay
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC202020.toInt())
            addButton("Papan") {
                ov.calibrating = true
                setOverlayTouchable(true)
            }
            addButton("Belajar") { learnRequested = true; toast("Mempelajari tampilan papan...") }
            addButton("Balik") {
                whiteAtBottom = !whiteAtBottom
                ov.flipped = !whiteAtBottom
                tracker.reset()
                savePrefs()
            }
            addButton("Giliran") {
                tracker.setSideToMove(!tracker.whiteToMove)
                tracker.currentFen()?.let { engine?.analyze(it) }
                toast(if (tracker.whiteToMove) "Giliran: Putih" else "Giliran: Hitam")
            }
            addButton("Jeda") {
                paused = !paused
                if (paused) engine?.stop()
                tracker.reset()
                ov.statusText = if (paused) "Dijeda" else ""
            }
            addButton("X") { stopSelf() }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        enableDrag(controls, p)
        wm.addView(controls, p)
    }

    private fun overlayParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun setOverlayTouchable(touchable: Boolean) {
        wm.updateViewLayout(overlay, overlayParams(touchable))
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit) {
        addView(Button(context).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(18, 6, 18, 6)
            setOnClickListener { onClick() }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(view: LinearLayout, params: WindowManager.LayoutParams) {
        var startX = 0f; var startY = 0f; var px = 0; var py = 0
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; px = params.x; py = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = px + (e.rawX - startX).toInt()
                    params.y = py + (e.rawY - startY).toInt()
                    wm.updateViewLayout(view, params)
                }
            }
            false
        }
    }

    // ---------------------------------------------------------------- capture

    @SuppressLint("WrongConstant")
    private fun setUpProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
        projection = mp
        // Android 14+: callback wajib didaftarkan sebelum membuat VirtualDisplay.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        virtualDisplay = mp.createVirtualDisplay(
            "chess-capture", screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch {
            var lastFen: String? = null
            while (isActive) {
                delay(CAPTURE_INTERVAL_MS)
                val rect = overlay.boardRect
                if (paused || rect == null || overlay.calibrating) continue

                // Overlay ikut tertangkap MediaProjection; sembunyikan sesaat agar panah
                // tidak mengotori pengenalan papan.
                withContext(Dispatchers.Main) { overlay.alpha = 0f }
                delay(HIDE_SETTLE_MS)
                val frame = grabFrame()
                withContext(Dispatchers.Main) { overlay.alpha = 1f }
                if (frame == null) continue
                val board = cropBoard(frame, rect)
                frame.recycle()
                if (board == null) continue

                val samples = recognizer.sample(board)
                board.recycle()

                if (learnRequested) {
                    learnRequested = false
                    val error = recognizer.learnFromStart(samples, whiteAtBottom)
                    tracker.reset()
                    toast(error ?: "Berhasil dipelajari. Analisis dimulai.")
                    continue
                }
                if (!recognizer.isCalibrated) {
                    overlay.postStatus("Belum dikalibrasi - tekan 'Belajar' pada posisi awal")
                    continue
                }

                val pieces = recognizer.recognize(samples, whiteAtBottom)
                if (pieces == null) {
                    overlay.postStatus("Papan belum terbaca - cek garis grid pas dengan papan (atur ulang 'Papan'), atau tunggu animasi")
                    continue
                }
                val fen = tracker.submit(pieces)
                if (fen != null && fen != lastFen) {
                    lastFen = fen
                    overlay.postStatus("")
                    engine?.analyze(fen)
                }
            }
        }
    }

    private fun grabFrame(): Bitmap? {
        val image: Image = reader?.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888,
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            return bmp
        } finally {
            image.close()
        }
    }

    private fun cropBoard(frame: Bitmap, r: RectF): Bitmap? {
        val x = r.left.toInt().coerceIn(0, frame.width - 1)
        val y = r.top.toInt().coerceIn(0, frame.height - 1)
        val w = r.width().toInt().coerceAtMost(frame.width - x)
        val h = r.height().toInt().coerceAtMost(frame.height - y)
        if (w < 64 || h < 64) return null
        return Bitmap.createBitmap(frame, x, y, w, h)
    }

    private fun OverlayView.postStatus(text: String) {
        if (statusText != text) main.post { statusText = text }
    }

    // ---------------------------------------------------------------- engine

    private fun startEngine() {
        val binary = File(applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!binary.exists()) {
            toast("libstockfish.so tidak ada di jniLibs/arm64-v8a (lihat README)")
            stopSelf()
            return
        }
        val e = UciEngine(binary, scope)
        engine = e
        scope.launch {
            try {
                withContext(Dispatchers.IO) { e.start(multiPv = 3, threads = 2, hashMb = 64) }
            } catch (ex: Exception) {
                toast("Engine gagal start: ${ex.message}")
                stopSelf()
                return@launch
            }
            e.lines.collectLatest { map ->
                val list: List<PvLine> = map.values.sortedBy { it.multiPv }
                val whiteTurn = tracker.whiteToMove
                main.post {
                    overlay.whiteToMove = whiteTurn
                    overlay.lines = list
                }
            }
        }
    }

    // ---------------------------------------------------------------- prefs

    private fun savePrefs() {
        val r = overlay.boardRect ?: return
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putFloat("l", r.left).putFloat("t", r.top).putFloat("r", r.right).putFloat("b", r.bottom)
            .putBoolean("white_bottom", whiteAtBottom)
            .apply()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        whiteAtBottom = p.getBoolean("white_bottom", true)
        overlay.flipped = !whiteAtBottom
        if (p.contains("l")) {
            overlay.boardRect = RectF(p.getFloat("l", 0f), p.getFloat("t", 0f), p.getFloat("r", 0f), p.getFloat("b", 0f))
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        captureJob?.cancel()
        scope.cancel()
        engine?.close()
        virtualDisplay?.release()
        reader?.close()
        projection?.stop()
        runCatching { wm.removeView(overlay) }
        runCatching { wm.removeView(controls) }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.example.chessoverlay.STOP"
        private const val CHANNEL_ID = "chess_overlay"
        private const val CAPTURE_INTERVAL_MS = 500L
        private const val HIDE_SETTLE_MS = 90L
    }
}
