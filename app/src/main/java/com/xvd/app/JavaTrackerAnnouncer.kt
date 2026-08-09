package com.xvd.app

import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.swig.address
import com.frostwire.jlibtorrent.swig.error_code
import com.frostwire.jlibtorrent.swig.tcp_endpoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object JavaTrackerAnnouncer {

    private const val HTTP_TRACKERS = "http://tracker.dler.org:80/announce,http://tracker.openbittorrent.com:80/announce,https://tracker.dler.org:443/announce,https://tracker.opentrackr.org:443/announce,https://tracker.bittor.pw:443/announce"

    private const val UDP_TRACKERS = "udp://tracker.dler.org:6969,udp://tracker.opentrackr.org:1337,udp://open.demonii.com:1337,udp://tracker.openbittorrent.com:6969,udp://exodus.desync.com:6969,udp://explodie.org:6969"

    private val announcing = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun trackerList(): List<String> = HTTP_TRACKERS.split(",") + UDP_TRACKERS.split(",")

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
                val allPeers = mutableListOf<Pair<String, Int>>()
                for (url in trackerList()) {
                    var r: List<Pair<String, Int>>? = null
                    if (url.startsWith("udp://")) {
                        r = udpAnnounceOnce(url, infohash, pid, port)
                    } else if (url.contains("://")) {
                        val u = java.net.URI(url)
                        val ip = resolveDoh(u.host)
                        if (ip != null && ip != u.host) {
                            val tag = compactUrl(url)
                            r = announceOnceAtIp(url, ip, infohash, pid, port)
                            if (r != null) TorrentEngine.recordJavaAnnounce("java上报 $tag @$ip: 返回${r.size}个")
                        } else {
                            r = announceOnce(url, infohash, pid, port)
                        }
                    }
                    when {
                        r == null -> TorrentEngine.recordJavaAnnounce("java上报 ${compactUrl(url)}: 网络失败")
                        r.isEmpty() -> TorrentEngine.recordJavaAnnounce("java上报 ${compactUrl(url)}: 返回0个peer")
                        else -> {
                            allPeers.addAll(r)
                            TorrentEngine.recordJavaAnnounce("java上报 ${compactUrl(url)}: 返回${r.size}个")
                        }
                    }
                }
                val reachable = probeReachable(allPeers.distinct().filter { it.second in 1..65535 })
                TorrentEngine.recordJavaAnnounce("连接探测: 共${allPeers.size}个 可达${reachable.size}个 " + reachable.take(8).joinToString(" ") { "${it.first}:${it.second}" })
                if (reachable.isNotEmpty()) {
                    injected += injectPeers(h, reachable)
                    TorrentEngine.recordJavaAnnounce("注入可达peer: ${injected}个")
                }
                val st2 = try { h.status() } catch (e: Exception) { null }
                val pi = try { h.peerInfo() } catch (e: Exception) { null }
                val lp = if (st2 != null) "发现${st2.listPeers()} 连接${st2.numPeers()}" else "状态读取失败"
                val pc = pi?.size ?: -1
                TorrentEngine.recordJavaAnnounce("注入后: $lp Peers列表=$pc")
                if (allPeers.isNotEmpty()) JavaPeerClient.attempt(hex, allPeers, pid)
                if (injected == 0) TorrentEngine.recordJavaAnnounce("java上报: 本轮未注入peer")
            } catch (ignored: Exception) {
            } finally {
                announcing.remove(key)
            }
        }.start()
    }

    fun probeSources(): List<Pair<String, String>> {
        val pid = ensurePeerId()
        val fake = hexToBytes("0000000000000000000000000000000000000000")
        val port = TorrentEngine.nativeListenPort
        return trackerList().map { url ->
            val r = if (url.startsWith("udp://")) {
                udpAnnounceOnce(url, fake, pid, port)
            } else {
                announceOnce(url, fake, pid, port)
            }
            val result = when {
                r == null -> "失败(超时/网络)"
                r.isEmpty() -> "200 返回0个peer"
                else -> "200 返回${r.size}个peer"
            }
            compactUrl(url) to result
        }
    }

    private fun compactUrl(url: String): String {
        return try {
            val u = java.net.URI(url)
            var port = u.port
            if (port == -1) port = if (u.scheme == "https") 443 else if (u.scheme == "udp") 6969 else 80
            val tag = when (u.scheme) {
                "https" -> "s"
                "udp" -> "u"
                else -> "h"
            }
            "${u.host}:$port[$tag]"
        } catch (e: Exception) {
            url
        }
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

    private fun resolveDoh(host: String): String? {
        return try {
            val conn = URL("https://dns.google/resolve?name=$host&type=A").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            val body = if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8) else null
            conn.disconnect()
            if (body == null) return null
            val m = Regex("\"data\"\\s*:\\s*\"(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\"").find(body)
            m?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun announceOnceAtIp(
        url: String,
        ip: String,
        infohash: ByteArray,
        pid: ByteArray,
        port: Int
    ): List<Pair<String, Int>>? {
        var conn: HttpURLConnection? = null
        return try {
            val u = java.net.URI(url)
            var p = u.port
            if (p == -1) p = if (u.scheme == "https") 443 else 80
            val path = if (u.path.isNullOrEmpty()) "/announce" else u.path
            val query = "info_hash=${encodeBytes(infohash)}&peer_id=${encodeBytes(pid)}&port=$port&uploaded=0&downloaded=0&left=1073741824&compact=1&event=started"
            val target = "${u.scheme}://$ip:$p$path?$query"
            conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Host", u.host + if (u.port != -1) ":${u.port}" else "")
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

    private fun udpAnnounceOnce(url: String, infohash: ByteArray, pid: ByteArray, port: Int): List<Pair<String, Int>>? {
        return try {
            val u = java.net.URI(url)
            val host = u.host
            val rport = if (u.port == -1) 6969 else u.port
            val sock = java.net.DatagramSocket()
            try {
                sock.soTimeout = 5000
                val addr = java.net.InetAddress.getByName(host)
                val rnd = java.util.Random()
                val txConnect = rnd.nextInt(Int.MAX_VALUE)
                val connReq = ByteArray(16)
                connReq[0] = 0
                connReq[1] = 0
                connReq[2] = 0
                connReq[3] = 4
                connReq[4] = 0x17.toByte()
                connReq[5] = 0x27.toByte()
                connReq[6] = 0x10.toByte()
                connReq[7] = 0x80.toByte()
                writeIntBE(connReq, 8, 0)
                writeIntBE(connReq, 12, txConnect)
                sock.send(java.net.DatagramPacket(connReq, connReq.size, addr, rport))
                val respBuf = ByteArray(2048)
                var packet = java.net.DatagramPacket(respBuf, respBuf.size)
                sock.receive(packet)
                if (packet.length < 16 || readIntBE(respBuf, 0) != 0 || readIntBE(respBuf, 4) != txConnect) return null
                val connectionId = ByteArray(8)
                System.arraycopy(respBuf, 8, connectionId, 0, 8)
                val txAnnounce = rnd.nextInt(Int.MAX_VALUE)
                val req = ByteArray(98)
                System.arraycopy(connectionId, 0, req, 0, 8)
                writeIntBE(req, 8, 1)
                writeIntBE(req, 12, txAnnounce)
                System.arraycopy(infohash, 0, req, 16, 20)
                System.arraycopy(pid, 0, req, 36, 20)
                writeLongBE(req, 56, 0L)
                writeLongBE(req, 64, 1073741824L)
                writeLongBE(req, 72, 0L)
                writeIntBE(req, 80, 2)
                writeIntBE(req, 84, 0)
                writeIntBE(req, 88, rnd.nextInt())
                writeIntBE(req, 92, -1)
                req[96] = ((port shr 8) and 0xff).toByte()
                req[97] = (port and 0xff).toByte()
                sock.send(java.net.DatagramPacket(req, req.size, addr, rport))
                packet = java.net.DatagramPacket(respBuf, respBuf.size)
                sock.receive(packet)
                if (packet.length < 20 || readIntBE(respBuf, 0) != 1 || readIntBE(respBuf, 4) != txAnnounce) return null
                val list = mutableListOf<Pair<String, Int>>()
                var k = 20
                while (k + 6 <= packet.length) {
                    val ip = "${respBuf[k].toInt() and 0xff}.${respBuf[k + 1].toInt() and 0xff}.${respBuf[k + 2].toInt() and 0xff}.${respBuf[k + 3].toInt() and 0xff}"
                    val p = ((respBuf[k + 4].toInt() and 0xff) shl 8) or (respBuf[k + 5].toInt() and 0xff)
                    list.add(ip to p)
                    k += 6
                }
                list
            } finally {
                sock.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeIntBE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun writeLongBE(b: ByteArray, off: Int, v: Long) {
        writeIntBE(b, off, (v ushr 32).toInt())
        writeIntBE(b, off + 4, v.toInt())
    }

    private fun readIntBE(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
    }

    private val reachPool = java.util.concurrent.Executors.newFixedThreadPool(6)

    fun probeReachable(peers: List<Pair<String, Int>>): List<Pair<String, Int>> {
        if (peers.isEmpty()) return emptyList()
        val candidates = peers.distinct().take(24)
        val result = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Int>>()
        val futures = mutableListOf<java.util.concurrent.Future<*>>()
        for ((ip, p) in candidates) {
            futures.add(reachPool.submit {
                var s: java.net.Socket? = null
                try {
                    s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress(ip, p), 1500)
                    result.add(ip to p)
                } catch (ignored: Exception) {
                } finally {
                    try {
                        s?.close()
                    } catch (ignored: Exception) {
                    }
                }
            })
        }
        for (f in futures) {
            try {
                f.get(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (ignored: Exception) {
            }
        }
        return result.toList()
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
            } catch (e: Exception) {
                TorrentEngine.recordJavaAnnounce("connect_peer异常 $ip:$p: ${e.message}")
            }
        }
        return n
    }
}
