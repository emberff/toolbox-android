package com.xvd.app

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.util.Locale

object ClipboardProcessor {

    @Volatile
    private var lastProcessed = ""

    fun process(context: Context, force: Boolean = false) {
        processText(context, readClipboard(context), force)
    }

    fun processText(context: Context, text: String?, force: Boolean = false) {
        if (text.isNullOrBlank()) {
            if (force) {
                DownloadBus.addLog("未识别到链接")
                NotificationHelper.showInfo(context, "读取失败", "剪贴板为空或未输入链接（Android 10+ 后台读取需悬浮窗权限）")
            }
            return
        }
        val parsed = TweetParser.findFirst(text)
        if (parsed == null) {
            if (force) DownloadBus.addLog("剪贴板/输入中没有 X/推特 视频链接")
            return
        }
        if (!force && parsed.statusId == lastProcessed) return
        lastProcessed = parsed.statusId

        Thread {
            DownloadBus.addLog("检测到链接: ${parsed.original}")
            NotificationHelper.showInfo(context, "检测到 X 链接", "正在解析视频地址…")

            val info = VideoFetcher.fetch(parsed.username, parsed.statusId)
            if (info == null) {
                val msg = "该推文没有视频，或解析失败（网络/服务异常）"
                DownloadBus.addLog("解析失败")
                NotificationHelper.showInfo(context, "解析失败", msg)
                return@Thread
            }

            val resolution = if (info.width > 0 && info.height > 0) "${info.width}x${info.height}" else "未知分辨率"
            DownloadBus.addLog("获取到视频: $resolution ${info.bitrate / 1000}kbps")
            val fileName = "xvideo_${parsed.statusId}_${System.currentTimeMillis() / 1000}"

            NotificationHelper.showDownloadProgress(context, -1, "开始下载…")
            Downloader.download(context, info.url, fileName, object : Downloader.Callback {
                override fun onProgress(downloaded: Long, total: Long) {
                    val pct = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                    val downloadedMb = String.format(Locale.US, "%.1f", downloaded / 1048576.0)
                    val totalMb = if (total > 0) String.format(Locale.US, "%.1f", total / 1048576.0) else ""
                    val txt = if (total > 0) "$downloadedMb / $totalMb MB" else "$downloadedMb MB"
                    NotificationHelper.showDownloadProgress(context, pct, txt)
                    DownloadBus.setProgress(pct.coerceAtLeast(0), txt)
                }

                override fun onSuccess(uri: Uri, fileName: String) {
                    DownloadBus.addLog("下载完成: $fileName.mp4")
                    DownloadBus.setDone("下载完成")
                    NotificationHelper.showDownloadDone(context, uri, fileName)
                }

                override fun onError(message: String) {
                    DownloadBus.addLog("下载失败: $message")
                    DownloadBus.setError(message)
                    NotificationHelper.showInfo(context, "下载失败", message)
                }
            })
        }.start()
    }

    fun readClipboard(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        } catch (e: Exception) {
            null
        }
    }
}
