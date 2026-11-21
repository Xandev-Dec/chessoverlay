package com.example.chessoverlay

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.chessoverlay.overlay.OverlayService

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                startForegroundService(svc)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Izin perekaman layar ditolak", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Chess Overlay Analysis"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "1. Beri izin 'tampil di atas aplikasi lain'\n" +
                "2. Mulai overlay lalu buka aplikasi catur\n" +
                "3. 'Papan': ketuk 2 sudut papan\n" +
                "4. Pada posisi awal tekan 'Belajar'\n" +
                "5. Panah & bar evaluasi muncul otomatis\n\n" +
                "Gunakan untuk analisis/latihan dan partai non-kompetitif. " +
                "Jangan dipakai pada partai online berperingkat - itu melanggar aturan fair play."
            textSize = 14f
            setPadding(0, pad, 0, pad)
        })
        root.addView(Button(this).apply {
            text = "Beri izin overlay"
            setOnClickListener { openOverlaySettings() }
        })
        root.addView(Button(this).apply {
            text = "Mulai overlay"
            setOnClickListener { startOverlay() }
        })
        root.addView(Button(this).apply {
            text = "Hentikan"
            setOnClickListener {
                startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            }
        })
        setContentView(root)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Beri izin overlay dulu", Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
