package com.xvd.app

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

object JavaPeerClient {

    private const val PIECE_SIZE = 16384
    private val busy = AtomicBoolean(false)
    private val tried = Collections.synchronizedSet(mutableSetOf<String>())

    fun attempt(hex: String, peers: List<Pair<String, Int>>, peerId: ByteArray) {
        if (busy.get()) return
        val all = peers.distinct().filter { it.second in 1..65535 }
        if (all.isEmpty()) return
        Thread {
            if (!busy.compareAndSet(false, true)) return@Thread
            try {
                val infohash = hexToBytes(hex)
                var attempt = 0
                for ((ip, p) in all) {
                    if (attempt >= 8) break
                    val key = "$ip:$p"
                    if (!tried.add(key)) continue
                    attempt++
                    try {
                        val info = fetchFromPeer(ip, p, infohash, peerId)
                        if (info != null) {
                            TorrentEngine.recordJavaAnnounce("元数据: 抓取成功 ${info.size}字节 ($key)")
                            TorrentManager.adoptTorrentFile(hex, info)
                            return@Thread
                        }
                        TorrentEngine.recordJavaAnnounce("元数据: $key 无结果")
                    } catch (e: Exception) {
                        TorrentEngine.recordJavaAnnounce("元数据: $key 异常 ${e.message}")
                    }
                }
                TorrentEngine.recordJavaAnnounce("元数据: 本轮未获元数据")
            } finally {
                busy.set(false)
            }
        }.start()
    }

    private fun fetchFromPeer(ip: String, port: Int, infohash: ByteArray, peerId: ByteArray): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 8000)
            socket.soTimeout = 15000
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            sendHandshake(output, infohash, peerId)
            val reply = ByteArray(68)
            readExact(input, reply, 0, 68)
            if (reply[0].toInt() != 19 || reply[1].toInt() != 'B'.code || reply[2].toInt() != 'i'.code) return null
            if (!reply.copyOfRange(28, 48).contentEquals(infohash)) return null

            sendExtHandshake(output)

            var metadataSize = -1
            var utMetadata = -1
            var extHandshakeGot = false
            val pieces = mutableMapOf<Int, ByteArray>()
            var outstanding = 0
            val deadline = System.currentTimeMillis() + 60000
            var sent = 0

            while (System.currentTimeMillis() < deadline) {
                val msg = readMessage(input) ?: return null
                if (msg.isEmpty()) continue
                val id = msg[0].toInt() and 0xff
                when {
                    id == 20 -> {
                        if (msg.size < 2) continue
                        val extId = msg[1].toInt() and 0xff
                        val payload = msg.copyOfRange(2, msg.size)
                        if (extId == 0) {
                            metadataSize = findBencInt(payload, "metadata_size") ?: -1
                            utMetadata = findBencInt(payload, "ut_metadata") ?: -1
                            if (metadataSize > 0 && utMetadata > 0) {
                                extHandshakeGot = true
                                val total = (metadataSize + PIECE_SIZE - 1) / PIECE_SIZE
                                sendInterested(output)
                                while (outstanding < 6 && sent < total) {
                                    sendMetadataRequest(output, utMetadata, sent)
                                    outstanding++
                                    sent++
                                }
                            }
                        } else if (extId == utMetadata) {
                            val pr = parseMetadataPiece(payload)
                            if (pr != null && pr.second.isNotEmpty()) {
                                val (piece, data) = pr
                                if (!pieces.containsKey(piece)) {
                                    pieces[piece] = data
                                    outstanding = maxOf(0, outstanding - 1)
                                    val total = (metadataSize + PIECE_SIZE - 1) / PIECE_SIZE
                                    if (pieces.size < total && sent < total) {
                                        sendMetadataRequest(output, utMetadata, sent)
                                        outstanding++
                                        sent++
                                    }
                                    if (pieces.size >= total) break
                                }
                            }
                        }
                    }
                    id == 2 -> Unit
                    id == 1 -> Unit
                    id == 0 -> Unit
                    id == 3 -> Unit
                }
                if (!extHandshakeGot) {
                    val total = (metadataSize + PIECE_SIZE - 1) / PIECE_SIZE
                    if (pieces.size >= total && total > 0) break
                }
            }

            if (pieces.isEmpty()) return null
            val info = assemble(metadataSize, pieces) ?: return null
            if (!isValidInfo(info)) return null
            info
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun assemble(metadataSize: Int, pieces: Map<Int, ByteArray>): ByteArray? {
        val total = (metadataSize + PIECE_SIZE - 1) / PIECE_SIZE
        if (pieces.size < total) return null
        val out = ByteArray(metadataSize)
        var off = 0
        for (i in 0 until total) {
            val p = pieces[i] ?: return null
            System.arraycopy(p, 0, out, off, minOf(p.size, metadataSize - off))
            off += p.size
        }
        return out
    }

    private fun sendHandshake(output: OutputStream, infohash: ByteArray, peerId: ByteArray) {
        val b = ByteArray(68)
        b[0] = 19
        val proto = "BitTorrent protocol".toByteArray(Charsets.US_ASCII)
        System.arraycopy(proto, 0, b, 1, proto.size)
        b[25] = 0x10.toByte()
        System.arraycopy(infohash, 0, b, 28, 20)
        System.arraycopy(peerId, 0, b, 48, 20)
        output.write(b)
        output.flush()
    }

    private fun sendExtHandshake(output: OutputStream) {
        val payload = "d1:md11:ut_metadatai1eee".toByteArray(Charsets.US_ASCII)
        sendMessage(output, 20, byteArrayOf(0) + payload)
    }

    private fun sendInterested(output: OutputStream) {
        sendMessage(output, 2, ByteArray(0))
    }

    private fun sendMetadataRequest(output: OutputStream, utMetadata: Int, piece: Int) {
        val payload = "d8:msg_typei0e5:piecei${piece}ee".toByteArray(Charsets.US_ASCII)
        sendMessage(output, 20, byteArrayOf(utMetadata.toByte()) + payload)
    }

    private fun sendMessage(output: OutputStream, id: Int, payload: ByteArray) {
        val len = payload.size + 1
        output.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        output.write(id)
        output.write(payload)
        output.flush()
    }

    private fun readMessage(input: InputStream): ByteArray? {
        val lenBuf = ByteArray(4)
        readExact(input, lenBuf, 0, 4)
        val len = ((lenBuf[0].toInt() and 0xff) shl 24) or ((lenBuf[1].toInt() and 0xff) shl 16) or
            ((lenBuf[2].toInt() and 0xff) shl 8) or (lenBuf[3].toInt() and 0xff)
        if (len == 0) return ByteArray(0)
        if (len > 1024 * 1024) return null
        val msg = ByteArray(len)
        readExact(input, msg, 0, len)
        return msg
    }

    private fun readExact(input: InputStream, buf: ByteArray, off: Int, n: Int) {
        var read = 0
        while (read < n) {
            val r = input.read(buf, off + read, n - read)
            if (r < 0) throw EOFException()
            read += r
        }
    }

    private fun findBencInt(data: ByteArray, key: String): Int? {
        val kb = key.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..data.size - kb.size) {
            for (j in kb.indices) {
                if (data[i + j] != kb[j]) continue@outer
            }
            var p = i + kb.size
            if (p >= data.size) return null
            if (data[p] != 'i'.code.toByte()) continue@outer
            p++
            var v = 0
            while (p < data.size && data[p] != 'e'.code.toByte()) {
                if (data[p] < '0'.code.toByte() || data[p] > '9'.code.toByte()) return null
                v = v * 10 + (data[p] - '0'.code.toByte())
                p++
            }
            if (p >= data.size) return null
            return v
        }
        return null
    }

    private fun parseMetadataPiece(payload: ByteArray): Pair<Int, ByteArray>? {
        return try {
            var p = 0
            if (payload.size < 2 || payload[p] != 'd'.code.toByte()) return null
            p++
            fun skipBencString(): Int {
                var len = 0
                while (p < payload.size && payload[p] in '0'.code.toByte()..'9'.code.toByte()) {
                    len = len * 10 + (payload[p] - '0'.code.toByte())
                    p++
                }
                if (p >= payload.size || payload[p] != ':'.code.toByte()) return -1
                p++
                return p + len
            }
            var q = skipBencString()
            if (q < 0) return null
            p = q
            if (p >= payload.size || payload[p] != 'i'.code.toByte()) return null
            p++
            while (p < payload.size && payload[p] != 'e'.code.toByte()) p++
            p++
            q = skipBencString()
            if (q < 0) return null
            p = q
            if (p >= payload.size || payload[p] != 'i'.code.toByte()) return null
            p++
            var piece = 0
            while (p < payload.size && payload[p] != 'e'.code.toByte()) {
                piece = piece * 10 + (payload[p] - '0'.code.toByte())
                p++
            }
            if (p >= payload.size) return null
            p++
            if (p >= payload.size || payload[p] != 'e'.code.toByte()) return null
            p++
            piece to payload.copyOfRange(p, payload.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun isValidInfo(info: ByteArray): Boolean {
        return try {
            val decoded = bencodeDecode(info) as? Map<*, *> ?: return false
            val sub = decoded["info"] as? Map<*, *> ?: return false
            sub["pieces"] is ByteArray && sub["piece length"] is Long && sub["name"] != null
        } catch (e: Exception) {
            false
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

    private fun bencodeDecode(data: ByteArray): Any? = Benc(data).decode()

    fun buildTorrentFile(infoBytes: ByteArray, trackers: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('d'.code)
        out.write("4:info".toByteArray(Charsets.US_ASCII))
        out.write(infoBytes)
        val announce = "http://tracker.dler.org:80/announce"
        out.write("8:announce".toByteArray(Charsets.US_ASCII))
        writeBencString(out, announce)
        out.write("13:announce-list".toByteArray(Charsets.US_ASCII))
        out.write('l'.code)
        for (t in (listOf(announce) + trackers).distinct()) {
            out.write('l'.code)
            writeBencString(out, t)
            out.write('e'.code)
        }
        out.write('e'.code)
        out.write("13:creation date".toByteArray(Charsets.US_ASCII))
        out.write("i".toByteArray(Charsets.US_ASCII))
        out.write((System.currentTimeMillis() / 1000).toString().toByteArray(Charsets.US_ASCII))
        out.write("e".toByteArray(Charsets.US_ASCII))
        out.write('e'.code)
        return out.toByteArray()
    }

    private fun writeBencString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        out.write(b.size.toString().toByteArray(Charsets.US_ASCII))
        out.write(':'.code)
        out.write(b)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
