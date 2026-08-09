package com.xvd.app

import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.swig.address
import com.frostwire.jlibtorrent.swig.error_code
import com.frostwire.jlibtorrent.swig.tcp_endpoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object JavaTrackerAnnouncer {

    private const val HTTP_TRACKERS = "http://tracker.dler.org:80/announce,https://tracker.dler.org:443/announce,https://tracker.opentrackr.org:443/announce,http://tracker.openbittorrent.com:80/announce"

    private val announcing = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun trackerList(): List<String> = HTTP_TRACKERS.split(",")

    private var peerId: ByteArray? = null

    private fun ensurePeerId(): ByteArray {
        peerId?.let { return it }
        val charset = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder("-JD0001-")
        repeat(12) { sb.append(charset.random()) }
        val id = sb.toString().toByteArray(Charsets.US_ASCII)
        peerId = id
        return id
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun encodeBytes(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append('%').append(Character.forDigit(v shr 4, 16)).append(Character.forDigit(v and 0xf, 16))
        }
        return sb.toString()
    }

    fun announce(hex: String) {
        val key = hex
        if (!announcing.add(key)) return
        Thread {
            try {
                val h = TorrentEngine.find(hex) ?: return@Thread
                if (!h.isValid()) return@Thread
                val infohash = hexToBytes(hex)
                val pid = ensurePeerId()
                val port = TorrentEngine.nativeListenPort
                var injected = 0
                for (url in trackerList()) {
                    val peers = announceOnce(url, infohash, pid, port)
                    if (peers != null && peers.isNotEmpty()) {
                        injected += injectPeers(h, peers)
                    }
                }
                if (injected > 0) {
                    TorrentEngine.recordEngineInfo("java上报成功, 注入 $injected 个peer")
                } else {
                    TorrentEngine.recordEngineInfo("java上报: 无peer返回")
                }
            } catch (ignored: Exception) {
            } finally {
                announcing.remove(key)
            }
        }.start()
    }

    private fun announceOnce(
        url: String,
        infohash: ByteArray,
        pid: ByteArray,
        port: Int
    ): List<Pair<String, Int>>? {
        var conn: HttpURLConnection? = null
        return try {
            val u = "$url?info_hash=${encodeBytes(infohash)}&peer_id=${encodeBytes(pid)}&port=$port&uploaded=0&downloaded=0&left=1073741824&compact=1&event=started"
            conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 200) return null
            val body = conn.inputStream.use { it.readBytes() }
            parseCompactPeers(body)
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun parseCompactPeers(body: ByteArray): List<Pair<String, Int>> {
        val list = mutableListOf<Pair<String, Int>>()
        try {
            val idx = indexOfAscii(body, "5:peers")
            if (idx < 0) return list
            var i = idx + 7
            var len = 0
            var hasLen = false
            while (i < body.size) {
                val c = body[i].toInt().toChar()
                if (c.isDigit()) {
                    len = len * 10 + (c - '0')
                    hasLen = true
                    i++
                } else if (c == ':') {
                    i++
                    break
                } else {
                    return list
                }
            }
            if (!hasLen) return list
            var k = i
            while (k + 6 <= i + len && k + 6 <= body.size) {
                val ip = "${body[k].toInt() and 0xff}.${body[k + 1].toInt() and 0xff}.${body[k + 2].toInt() and 0xff}.${body[k + 3].toInt() and 0xff}"
                val p = ((body[k + 4].toInt() and 0xff) shl 8) or (body[k + 5].toInt() and 0xff)
                list.add(ip to p)
                k += 6
            }
        } catch (ignored: Exception) {
        }
        return list
    }

    private fun indexOfAscii(body: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..body.size - n.size) {
            for (j in n.indices) {
                if (body[i + j] != n[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun injectPeers(h: TorrentHandle, peers: List<Pair<String, Int>>): Int {
        var n = 0
        for ((ip, p) in peers) {
            if (p == 0) continue
            try {
                val ec = error_code()
                val addr = address.from_string(ip, ec)
                if (ec.value() == 0) {
                    h.swig().connect_peer(tcp_endpoint(addr, p))
                    n++
                }
            } catch (ignored: Exception) {
            }
        }
        return n
    }
}
