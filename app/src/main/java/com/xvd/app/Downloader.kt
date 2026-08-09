package com.xvd.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Downloader {

    interface Callback {
        fun onProgress(downloaded: Long, total: Long)
        fun onSuccess(uri: Uri, fileName: String)
        fun onError(message: String)
    }

    fun download(context: Context, url: String, displayName: String, callback: Callback) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "XVideoDownloader/1.0")
                val code = conn.responseCode
                if (code !in 200..299) {
                    callback.onError("下载失败，HTTP $code")
                    return@Thread
                }
                val total = conn.contentLengthLong
                val input = conn.inputStream
                val tempFile = File(context.cacheDir, "$displayName.tmp")
                var lastEmit = 0L
                var downloaded = 0L
                FileOutputStream(tempFile).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= 250 || downloaded == total) {
                            lastEmit = now
                            callback.onProgress(downloaded, total)
                        }
                    }
                }
                input.close()

                val uri = if (Build.VERSION.SDK_INT >= 29) {
                    saveToMediaStore(context, tempFile, displayName)
                } else {
                    saveLegacy(context, tempFile, displayName)
                }
                callback.onSuccess(uri, displayName)
            } catch (e: IOException) {
                callback.onError("下载出错：${e.message}")
            } catch (e: Exception) {
                callback.onError("错误：${e.message}")
            } finally {
                try {
                    conn?.disconnect()
                } catch (ignored: Exception) {
                }
            }
        }.start()
    }

    private fun saveToMediaStore(context: Context, temp: File, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/XVideoDownloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在存储中创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法打开输出流")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            throw e
        } finally {
            temp.delete()
        }
        return uri
    }

    private fun saveLegacy(context: Context, temp: File, name: String): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "XVideoDownloader"
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建下载目录")
        val target = File(dir, "$name.mp4")
        temp.copyTo(target, overwrite = true)
        temp.delete()
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
    }
}
