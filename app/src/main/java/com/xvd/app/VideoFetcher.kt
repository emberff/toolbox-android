package com.xvd.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class VideoInfo(
    val url: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val duration: Double,
    val thumbnail: String?
)

object VideoFetcher {

    private const val UA = "Mozilla/5.0 (Linux; Android 14; XVideoDownloader/1.0)"

    fun fetch(username: String?, statusId: String): VideoInfo? {
        val handle = username?.takeIf { it.isNotBlank() } ?: "twitter"
        val json = getText("https://api.fxtwitter.com/$handle/status/$statusId") ?: return null
        return try {
            val root = JSONObject(json)
            if (root.optInt("code", 200) != 200) return null
            val tweet = root.optJSONObject("tweet") ?: return null
            val media = tweet.optJSONObject("media") ?: return null
            val videos = media.optJSONArray("videos") ?: return null
            var best: VideoInfo? = null
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val variants = v.optJSONArray("variants")
                var url = v.optString("url")
                var bitrate = v.optInt("bitrate")
                if (variants != null) {
                    for (j in 0 until variants.length()) {
                        val va = variants.optJSONObject(j) ?: continue
                        val ct = va.optString("content_type")
                        if (ct.contains("mp4", ignoreCase = true)) {
                            val u = va.optString("url")
                            val b = va.optInt("bitrate")
                            if (u.isNotBlank() && (b > bitrate || bitrate <= 0)) {
                                url = u
                                bitrate = b
                            }
                        }
                    }
                }
                if (url.isBlank()) continue
                val info = VideoInfo(
                    url = url,
                    width = v.optInt("width"),
                    height = v.optInt("height"),
                    bitrate = bitrate,
                    duration = v.optDouble("duration"),
                    thumbnail = v.optString("thumbnail_url").ifBlank { null }
                )
                if (best == null || info.bitrate > best.bitrate) best = info
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    private fun getText(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) {
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }
}
