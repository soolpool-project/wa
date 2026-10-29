package com.example.autofarmbs

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min

class FarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var overlay: View? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("farm", "AutoFarm", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "farm").setContentTitle("AutoFarm BS работает")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = i.getIntExtra("code", 0)
        val data = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java)
                   else @Suppress("DEPRECATION") i.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)

        addOverlay()
        Bus.running.value = true
        scope.launch {
            var cfg = Config()
            val store = ConfigStore(this@FarmService)
            cfg = store.flow.first()
            launch { store.flow.collect { cfg = it } }
            try {
                launchGame(this@FarmService)
                delay(4000)                                   // ждём, пока игра развернётся в landscape
                val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                val cap = ScreenCapture(mp, max(b.width(), b.height()), min(b.width(), b.height()),
                    resources.displayMetrics.densityDpi)
                try { Orchestrator(this@FarmService, cap, Vision(this@FarmService)) { cfg }.run() }
                finally { cap.close() }
            } catch (e: Exception) {
                Bus.log("Фатальная ошибка: ${e.message}")
            } finally {
                mp.stop(); Bus.running.value = false; stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val btn = Button(this).apply { text = "■ СТОП"; setOnClickListener { Bus.running.value = false } }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 40 }
        getSystemService(WindowManager::class.java).addView(btn, lp)
        overlay = btn
    }

    override fun onDestroy() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        scope.cancel(); Bus.running.value = false
        super.onDestroy()
    }
}
