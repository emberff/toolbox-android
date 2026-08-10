package com.xvd.app

import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ClipboardMonitorService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var clipboard: ClipboardManager
    private var overlayHelper: OverlayHelper? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        ClipboardProcessor.process(this)
    }

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        overlayHelper = OverlayHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startAsForeground()
        if (intent?.action == NotificationHelper.ACTION_PROCESS) {
            ClipboardProcessor.process(this, force = true)
        }
        overlayHelper?.ensureOverlay()
        try {
            clipboard.addPrimaryClipChangedListener(clipListener)
        } catch (ignored: Exception) {
        }
        ClipboardProcessor.process(this)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notif = NotificationHelper.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NotificationHelper.NOTIF_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIF_SERVICE, notif)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        try {
            clipboard.removePrimaryClipChangedListener(clipListener)
        } catch (ignored: Exception) {
        }
        overlayHelper?.removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
