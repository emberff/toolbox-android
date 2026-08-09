package com.xvd.app

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TorrentService : Service() {

    companion object {
        const val ACTION_START = "com.xvd.app.action.TORRENT_START"
        const val ACTION_STOP = "com.xvd.app.action.TORRENT_STOP"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        TorrentManager.init(this)
        TorrentEngine.start()
        TorrentManager.resumeAll()
        startPolling()
        return START_STICKY
    }

    private fun startAsForeground() {
        val notif = NotificationHelper.torrentServiceNotification(this, 0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NotificationHelper.NOTIF_TORRENT, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.NOTIF_TORRENT, notif)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    TorrentManager.refreshFromEngine()
                    if (TorrentManager.isEmpty()) {
                        NotificationHelper.hideTorrent(this@TorrentService)
                        stopSelf()
                        break
                    }
                    val (active, rate) = TorrentManager.summary()
                    val notif = NotificationHelper.torrentServiceNotification(this@TorrentService, active, rate)
                    NotificationHelper.updateTorrent(this@TorrentService, notif)
                } catch (ignored: Exception) {
                }
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        Thread {
            try {
                TorrentManager.saveAllResume()
                TorrentEngine.stop()
            } catch (ignored: Exception) {
            }
        }.start()
        super.onDestroy()
    }
}
