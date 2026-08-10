package com.xvd.app

import com.frostwire.jlibtorrent.swig.byte_vector
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object JavaDownloader {

    data class TorrentData(
        val pieceLength: Int,
        val totalSize: Long,
        val infoHashes: List<ByteArray>,
        val name: String
    )

    private val running = AtomicBoolean(false)
    private val downloadedPieces = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    fun start(hex: String) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val td = loadTorrent(hex) ?: return@Thread
                val infohash = JavaPeerClient.hexToBytes(hex)
                val pid = JavaTrackerAnnouncer.peerId()
                val budget = System.currentTimeMillis() + 30 * 60 * 1000L
                var rounds = 0
                while (System.currentTimeMillis() < budget) {
                    val have = countHave(hex, td.infoHashes.size)
                    if (have >= td.infoHashes.size) {
                        TorrentEngine.recordJavaAnnounce("数据: 全部${td.infoHashes.size}片已下载")
                        return@Thread
                    }
                    if (rounds > 0 && have == 0) {
                        TorrentEngine.recordJavaAnnounce("数据: 进度停滞, 等待后重试")
                    }
                    rounds++
                    val known = JavaTrackerAnnouncer.knownReachablePeers()
                    val fresh = JavaTrackerAnnouncer.announceForPeers(hex)
                    val combined = (known + fresh).distinct().filter { it.second in 1..65535 }
                    val reachable = JavaTrackerAnnouncer.probeReachable(combined)
                    if (reachable.isEmpty()) {
                        TorrentEngine.recordJavaAnnounce("数据: 本轮无可达peer (已知${known.size} 新${fresh.size})")
                        Thread.sleep(10000)
                        continue
                    }
                    TorrentEngine.recordJavaAnnounce("数据: 可达${reachable.size}个, 开始下载 (已有$have/${td.infoHashes.size})")
                    var completed = false
                    for ((ip, p) in reachable) {
                        val sess = JavaPeerClient.connectHandshake(ip, p, infohash, pid)
                        if (sess == null) continue
                        TorrentEngine.recordJavaAnnounce("数据: 已连 $ip:$p, 开始下载")
                        try {
                            val before = countHave(hex, td.infoHashes.size)
                            val ok = JavaPeerClient.downloadFromPeer(
                                sess,
                                td.pieceLength,
                                td.totalSize,
                                td.infoHashes,
                                havePieces = { i -> isHave(hex, i) },
                                onPiece = { piece, data ->
                                    val ok = addPiece(hex, piece, data)
                                    if (ok) downloadedPieces[piece] = true
                                    val cur = downloadedPieces.size
                                    if (cur % 20 == 0) {
                                        TorrentEngine.recordJavaAnnounce("数据: 已下载$cur/${td.infoHashes.size}片")
                                    }
                                    ok
                                }
                            )
                            if (ok) {
                                completed = true
                                break
                            }
                            val after = countHave(hex, td.infoHashes.size)
                            if (after > before) {
                                TorrentEngine.recordJavaAnnounce("数据: $ip:$p 下载部分后中断, 已${after}片, 继续其他peer")
                            } else {
                                TorrentEngine.recordJavaAnnounce("数据: $ip:$p 无进展, 换peer")
                            }
                        } finally {
                            try {
                                sess.socket?.close()
                            } catch (ignored: Exception) {
                            }
                        }
                    }
                    if (completed) {
                        TorrentEngine.recordJavaAnnounce("数据: 下载完成")
                        return@Thread
                    }
                    Thread.sleep(10000)
                }
                TorrentEngine.recordJavaAnnounce("数据: 超时结束")
            } catch (e: Exception) {
                TorrentEngine.recordJavaAnnounce("数据: 异常 ${e.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    private fun loadTorrent(hex: String): TorrentData? {
        return try {
            val f = File(TorrentManager.torrentFilePath(hex))
            if (!f.exists()) return null
            parseTorrent(f.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    private fun isHave(hex: String, piece: Int): Boolean {
        downloadedPieces[piece]?.let { return it }
        return try {
            val h = TorrentEngine.find(hex)
            h != null && h.isValid() && h.swig().have_piece(piece)
        } catch (e: Exception) {
            false
        }
    }

    private fun countHave(hex: String, total: Int): Int {
        return try {
            val h = TorrentEngine.find(hex)
            if (h == null || !h.isValid()) return downloadedPieces.size
            var n = 0
            for (i in 0 until total) {
                if (downloadedPieces[i] == true || h.swig().have_piece(i)) n++
            }
            n
        } catch (e: Exception) {
            downloadedPieces.size
        }
    }

    private fun addPiece(hex: String, piece: Int, data: ByteArray): Boolean {
        return try {
            val h = TorrentEngine.find(hex) ?: return false
            if (!h.isValid()) return false
            val bv = byte_vector()
            for (b in data) bv.push_back(b)
            h.swig().add_piece_bytes(piece, bv)
            true
        } catch (e: Exception) {
            TorrentEngine.recordJavaAnnounce("数据: add_piece异常 片$piece ${e.message}")
            false
        }
    }

    fun parseTorrent(bytes: ByteArray): TorrentData? {
        return try {
            val root = Benc(bytes).decode() as? Map<*, *> ?: return null
            val info = root["info"] as? Map<*, *> ?: return null
            val pieceLength = (info["piece length"] as? Long)?.toInt() ?: return null
            val pieces = info["pieces"] as? ByteArray ?: return null
            val hashes = ArrayList<ByteArray>()
            var i = 0
            while (i + 20 <= pieces.size) {
                hashes.add(pieces.copyOfRange(i, i + 20))
                i += 20
            }
            val name = when (val v = info["name"]) {
                is ByteArray -> String(v, Charsets.UTF_8)
                is String -> v
                else -> "?"
            }
            val totalSize = if (info.containsKey("length")) {
                info["length"] as? Long ?: 0L
            } else {
                (info["files"] as? List<*>)?.sumOf { (it as? Map<*, *>)?.get("length") as? Long ?: 0L } ?: 0L
            }
            TorrentData(pieceLength, totalSize, hashes, name)
        } catch (e: Exception) {
            null
        }
    }

    private class Benc(private val data: ByteArray) {
        private var p = 0

        fun decode(): Any? = parseValue()

        private fun parseValue(): Any? {
            if (p >= data.size) return null
            val c = data[p++].toInt() and 0xff
            return when (c) {
                'd'.code -> {
                    val m = LinkedHashMap<String, Any>()
                    while (p < data.size && data[p] != 'e'.code.toByte()) {
                        val key = parseString() ?: return null
                        val v = parseValue() ?: return null
                        m[key] = v
                    }
                    if (p >= data.size) return null
                    p++
                    m
                }
                'l'.code -> {
                    val list = mutableListOf<Any>()
                    while (p < data.size && data[p] != 'e'.code.toByte()) {
                        list.add(parseValue() ?: return null)
                    }
                    if (p >= data.size) return null
                    p++
                    list
                }
                'i'.code -> {
                    var neg = false
                    if (p < data.size && data[p] == '-'.code.toByte()) {
                        neg = true
                        p++
                    }
                    var v = 0L
                    while (p < data.size && data[p] != 'e'.code.toByte()) {
                        if (data[p] < '0'.code.toByte() || data[p] > '9'.code.toByte()) return null
                        v = v * 10 + (data[p] - '0'.code.toByte())
                        p++
                    }
                    if (p >= data.size) return null
                    p++
                    if (neg) -v else v
                }
                in '0'.code..'9'.code -> {
                    p--
                    parseByteString()
                }
                else -> null
            }
        }

        private fun parseString(): String? {
            val b = parseByteString() ?: return null
            return String(b, Charsets.UTF_8)
        }

        private fun parseByteString(): ByteArray? {
            var len = 0
            while (p < data.size && data[p] in '0'.code.toByte()..'9'.code.toByte()) {
                len = len * 10 + (data[p] - '0'.code.toByte())
                p++
            }
            if (p >= data.size || data[p] != ':'.code.toByte()) return null
            p++
            if (p + len > data.size) return null
            val out = data.copyOfRange(p, p + len)
            p += len
            return out
        }
    }
}
