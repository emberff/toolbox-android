package com.xvd.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {

    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_DOWNLOAD = "download"
    const val CHANNEL_INFO = "info"
    const val CHANNEL_TORRENT = "torrent"
    const val NOTIF_SERVICE = 1
    const val NOTIF_DOWNLOAD = 2
    const val NOTIF_INFO = 3
    const val NOTIF_TORRENT = 4

    const val ACTION_PROCESS = "com.xvd.app.action.PROCESS"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "监听服务", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "后台监听剪贴板的常驻通知"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOAD, "下载任务", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "视频下载进度"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INFO, "提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "下载完成与错误提示"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TORRENT, "种子下载", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "种子 / 磁力下载进度"
            }
        )
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun serviceNotification(context: Context): Notification {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val processIntent = PendingIntent.getService(
            context, 1,
            Intent(context, ClipboardMonitorService::class.java).setAction(ACTION_PROCESS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("X视频下载器运行中")
            .setContentText("正在监听剪贴板，复制 X 链接即可自动下载")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "立即读取", processIntent)
            .addAction(0, "打开", openIntent)
            .build()
    }

    fun showDownloadProgress(context: Context, progress: Int, text: String) {
        if (!canNotify(context)) return
        val indeterminate = progress < 0
        val b = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("正在下载视频")
            .setContentText(text)
            .setOnlyAlertOnce(true)
        if (indeterminate) {
            b.setProgress(0, 0, true)
        } else {
            b.setProgress(100, progress, false)
        }
        NotificationManagerCompat.from(context).notify(NOTIF_DOWNLOAD, b.build())
    }

    fun showDownloadDone(context: Context, uri: Uri, fileName: String) {
        if (!canNotify(context)) return
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("video", uri)
        }
        val pi = PendingIntent.getActivity(
            context, 2,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            NOTIF_DOWNLOAD,
            NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("下载完成")
                .setContentText("$fileName.mp4")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("$fileName.mp4\n已保存到 下载/XVideoDownloader")
                )
                .setContentIntent(pi)
                .addAction(0, "打开视频", pi)
                .setAutoCancel(true)
                .build()
        )
    }

    fun showInfo(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(
            NOTIF_INFO,
            NotificationCompat.Builder(context, CHANNEL_INFO)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        )
    }

    fun hideDownload(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_DOWNLOAD)
    }

    fun torrentServiceNotification(context: Context, active: Int, rate: Long): Notification {
        val openIntent = PendingIntent.getActivity(
            context, 3,
            Intent(context, TorrentActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context, 4,
            Intent(context, TorrentService::class.java).setAction(TorrentService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (active > 0) "进行中: $active 个任务, 总速度 ${formatRate(rate)}/s" else "等待添加任务"
        return NotificationCompat.Builder(context, CHANNEL_TORRENT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("种子下载器")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    fun updateTorrent(context: Context, notification: Notification) {
        if (canNotify(context)) {
            NotificationManagerCompat.from(context).notify(NOTIF_TORRENT, notification)
        }
    }

    fun hideTorrent(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_TORRENT)
    }

    fun showTorrentFinished(context: Context, name: String) {
        if (!canNotify(context)) return
        val openIntent = PendingIntent.getActivity(
            context, 5,
            Intent(context, TorrentActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            NOTIF_TORRENT,
            NotificationCompat.Builder(context, CHANNEL_TORRENT)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("种子下载完成")
                .setContentText(name)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n已保存到 下载/种子下载"))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun formatRate(bytes: Long): String {
        return when {
            bytes >= 1048576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
