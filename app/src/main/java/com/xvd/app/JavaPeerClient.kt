package com.xvd.app

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
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
            val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
            try {
                val infohash = hexToBytes(hex)
                val fresh = all.filter { tried.add("${it.first}:${it.second}") }.take(8)
                if (fresh.isEmpty()) return@Thread
                val success = AtomicBoolean(false)
                for ((ip, p) in fresh) {
                    if (success.get()) break
                    executor.submit {
                        if (success.get()) return@submit
                        try {
                            val info = fetchFromPeer(ip, p, infohash, peerId)
                            if (info != null && success.compareAndSet(false, true)) {
                                TorrentEngine.recordJavaAnnounce("元数据: 抓取成功 ${info.size}字节 ($ip:$p)")
                                TorrentManager.adoptTorrentFile(hex, info)
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
                executor.shutdown()
                executor.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS)
                if (!success.get()) TorrentEngine.recordJavaAnnounce("元数据: 本轮未获元数据")
            } finally {
                busy.set(false)
            }
        }.start()
    }

    private class Session(
        val input: InputStream,
        val output: OutputStream,
        val enc: RC4?,
        val dec: RC4?,
        val pending: Pending,
        val encrypted: Boolean
    )

    private fun fetchFromPeer(ip: String, port: Int, infohash: ByteArray, peerId: ByteArray): ByteArray? {
        val enc = try {
            fetchEncrypted(ip, port, infohash, peerId)
        } catch (e: Exception) {
            null
        }
        if (enc != null) return enc
        return try {
            fetchPlain(ip, port, infohash, peerId)
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchPlain(ip: String, port: Int, infohash: ByteArray, peerId: ByteArray): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 8000)
            socket.soTimeout = 12000
            socket.tcpNoDelay = true
            val sess = Session(socket.getInputStream(), socket.getOutputStream(), null, null, Pending(), false)
            sess.output.write(buildHandshake(infohash, peerId))
            sess.output.flush()
            val reply = readDecrypted(sess, 68) ?: return null
            if (reply[0].toInt() != 19 || reply[1].toInt() != 'B'.code || reply[2].toInt() != 'i'.code) return null
            if (!reply.copyOfRange(28, 48).contentEquals(infohash)) return null
            metadataExchange(sess, infohash, peerId)
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun fetchEncrypted(ip: String, port: Int, infohash: ByteArray, peerId: ByteArray): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 8000)
            socket.soTimeout = 12000
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val rnd = java.security.SecureRandom()
            val prime = BigInteger(1, hexToBytes(DH_PRIME_HEX))
            val secret = BigInteger(160, rnd)
            val pub = BigInteger.TWO.modPow(secret, prime)
            val pubBytes = export96(pub)
            val pad1 = rnd.nextInt(512)
            output.write(pubBytes)
            output.write(randBytes(rnd, pad1))
            output.flush()

            val peerPub = ByteArray(96)
            readExact(input, peerPub, 0, 96)
            val peerKey = BigInteger(1, peerPub)
            if (peerKey < BigInteger.TWO || peerKey >= prime - BigInteger.ONE) return null
            val shared = peerKey.modPow(secret, prime)
            val s = export96(shared)

            val outKey = sha1(concat3("keyA".toByteArray(), s, infohash))
            val inKey = sha1(concat3("keyB".toByteArray(), s, infohash))
            val enc = RC4(outKey)
            enc.discard(1024)
            val dec = RC4(inKey)
            dec.discard(1024)

            val syncHash = sha1(concat("req1".toByteArray(), s))
            val obfsc = xor(sha1(concat("req2".toByteArray(), infohash)), sha1(concat("req3".toByteArray(), s)))
            val pad2 = rnd.nextInt(512)
            val tail = ByteArray(16 + pad2)
            rnd.nextBytes(tail)
            java.util.Arrays.fill(tail, 0, 8, 0)
            putInt(tail, 8, 0x02)
            putShort(tail, 12, pad2)
            putShort(tail, 14 + pad2, 68)
            enc.crypt(tail, 0, tail.size)
            output.write(syncHash)
            output.write(obfsc)
            output.write(tail)

            val handshake = buildHandshake(infohash, peerId)
            enc.crypt(handshake, 0, handshake.size)
            output.write(handshake)
            output.flush()

            val searchDec = dec.copy()
            val vcKey = ByteArray(8)
            searchDec.crypt(vcKey, 0, 8)
            val buf = ByteArray(2048)
            var total = 0
            var vcPos = -1
            while (true) {
                val n = input.read(buf, total, buf.size - total)
                if (n < 0) return null
                total += n
                vcPos = indexOfSeq(buf, total, vcKey)
                if (vcPos >= 0) break
                if (total >= buf.size) return null
            }
            val tail2 = buf.copyOfRange(vcPos, total)
            dec.crypt(tail2, 0, tail2.size)
            val cryptoSelect = getInt(tail2, 8)
            val lenPad = getShort(tail2, 12)
            if (cryptoSelect == 0) return null
            val rc4Mode = cryptoSelect == 0x02
            val pending = Pending()
            val hp = 14 + lenPad
            if (tail2.size > hp) pending.add(tail2, hp, tail2.size - hp)
            val peerHs = readDecrypted(Session(input, output, enc, dec, pending, rc4Mode), 68) ?: return null
            if (peerHs[0].toInt() != 19) return null
            if (!peerHs.copyOfRange(28, 48).contentEquals(infohash)) return null

            metadataExchange(Session(input, output, enc, dec, pending, rc4Mode), infohash, peerId)
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun metadataExchange(sess: Session, infohash: ByteArray, peerId: ByteArray): ByteArray? {
        sendMessage(sess, 20, byteArrayOf(0) + "d1:md11:ut_metadatai1eee".toByteArray(Charsets.US_ASCII))
        var metadataSize = -1
        var utMetadata = -1
        var got = false
        var requested = false
        val pieces = mutableMapOf<Int, ByteArray>()
        val deadline = System.currentTimeMillis() + 25000
        while (System.currentTimeMillis() < deadline) {
            if (got && !requested) {
                requested = true
                val total = (metadataSize + PIECE_SIZE - 1) / PIECE_SIZE
                for (pi in 0 until total) sendMetadataRequest(sess, utMetadata, pi)
            }
            val msg = readMessage(sess) ?: break
            if (msg.isEmpty()) continue
            val id = msg[0].toInt() and 0xff
            if (id == 20) {
                if (msg.size < 2) continue
                val extId = msg[1].toInt() and 0xff
                val payload = msg.copyOfRange(2, msg.size)
                if (extId == 0) {
                    metadataSize = findBencInt(payload, "metadata_size") ?: -1
                    utMetadata = findBencInt(payload, "ut_metadata") ?: -1
                    if (metadataSize > 0 && utMetadata > 0) got = true
                } else if (extId == utMetadata || extId == 1) {
                    val pr = parseMetadataPiece(payload)
                    if (pr != null && pr.second.isNotEmpty()) {
                        val (piece, data) = pr
                        if (!pieces.containsKey(piece)) {
                            pieces[piece] = data
                            if (pieces.size >= (metadataSize + PIECE_SIZE - 1) / PIECE_SIZE) break
                        }
                    }
                }
            }
        }
        if (pieces.isEmpty()) return null
        val info = assemble(metadataSize, pieces) ?: return null
        if (!isValidInfo(info)) return null
        return info
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

    private class Pending {
        private var buf = ByteArray(16384)
        private var start = 0
        private var len = 0

        fun available(): Int = len

        fun add(d: ByteArray, o: Int, n: Int) {
            if (len + n > buf.size) {
                val nb = ByteArray(maxOf(buf.size * 2, len + n))
                System.arraycopy(buf, start, nb, 0, len)
                buf = nb
                start = 0
            }
            if (start + len + n > buf.size) {
                System.arraycopy(buf, start, buf, 0, len)
                start = 0
            }
            System.arraycopy(d, o, buf, start + len, n)
            len += n
        }

        fun take(n: Int): ByteArray {
            val r = ByteArray(n)
            System.arraycopy(buf, start, r, 0, n)
            start += n
            len -= n
            if (len == 0) start = 0
            return r
        }
    }

    private fun readDecrypted(sess: Session, n: Int): ByteArray? {
        while (sess.pending.available() < n) {
            val raw = ByteArray(4096)
            val r = sess.input.read(raw)
            if (r < 0) return null
            if (sess.encrypted) sess.dec?.crypt(raw, 0, r)
            sess.pending.add(raw, 0, r)
        }
        return sess.pending.take(n)
    }

    private fun readMessage(sess: Session): ByteArray? {
        val lb = readDecrypted(sess, 4) ?: return null
        val len = getInt(lb, 0)
        if (len == 0) return ByteArray(0)
        if (len > 1024 * 1024) return null
        return readDecrypted(sess, len)
    }

    private fun sendMessage(sess: Session, id: Int, payload: ByteArray) {
        val len = payload.size + 1
        val hdr = byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(), id.toByte())
        if (sess.encrypted) {
            sess.enc?.crypt(hdr, 0, hdr.size)
            sess.enc?.crypt(payload, 0, payload.size)
        }
        sess.output.write(hdr)
        sess.output.write(payload)
        sess.output.flush()
    }

    private fun buildHandshake(infohash: ByteArray, peerId: ByteArray): ByteArray {
        val b = ByteArray(68)
        b[0] = 19
        val proto = "BitTorrent protocol".toByteArray(Charsets.US_ASCII)
        System.arraycopy(proto, 0, b, 1, proto.size)
        b[25] = 0x10.toByte()
        b[27] = 0x05.toByte()
        System.arraycopy(infohash, 0, b, 28, 20)
        System.arraycopy(peerId, 0, b, 48, 20)
        return b
    }

    private fun sendMetadataRequest(sess: Session, utMetadata: Int, piece: Int) {
        val payload = "d8:msg_typei0e5:piecei${piece}ee".toByteArray(Charsets.US_ASCII)
        sendMessage(sess, 20, byteArrayOf(utMetadata.toByte()) + payload)
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

    private fun skipBencString(d: ByteArray, p0: Int): Pair<Int, Int>? {
        var p = p0
        var len = 0
        while (p < d.size && d[p] in '0'.code.toByte()..'9'.code.toByte()) {
            len = len * 10 + (d[p] - '0'.code.toByte())
            p++
        }
        if (p >= d.size || d[p] != ':'.code.toByte()) return null
        p++
        if (p + len > d.size) return null
        return len to (p + len)
    }

    private fun skipValue(d: ByteArray, p0: Int): Int? {
        if (p0 >= d.size) return null
        val c = d[p0].toInt().toChar()
        return when {
            c == 'i' -> {
                var p = p0 + 1
                while (p < d.size && d[p] != 'e'.code.toByte()) p++
                if (p >= d.size) null else p + 1
            }
            c == 'l' -> {
                var p = p0 + 1
                while (p < d.size && d[p] != 'e'.code.toByte()) {
                    p = skipValue(d, p) ?: return null
                }
                if (p >= d.size) null else p + 1
            }
            c == 'd' -> {
                var p = p0 + 1
                while (p < d.size && d[p] != 'e'.code.toByte()) {
                    val k = skipBencString(d, p) ?: return null
                    p = k.second
                    p = skipValue(d, p) ?: return null
                }
                if (p >= d.size) null else p + 1
            }
            else -> skipBencString(d, p0)?.second
        }
    }

    private fun parseMetadataPiece(payload: ByteArray): Pair<Int, ByteArray>? {
        val keyPiece = "piece".toByteArray(Charsets.US_ASCII)
        return try {
            var p = 0
            if (payload.size < 2 || payload[p] != 'd'.code.toByte()) return null
            p++
            var piece = -1
            while (p < payload.size && payload[p] != 'e'.code.toByte()) {
                val k = skipBencString(payload, p) ?: return null
                val keyStart = k.second - k.first
                p = k.second
                if (p >= payload.size) return null
                val isPiece = k.first == keyPiece.size && rangeEquals(payload, keyStart, keyPiece)
                if (isPiece && payload[p] == 'i'.code.toByte()) {
                    p++
                    var v = 0
                    while (p < payload.size && payload[p] != 'e'.code.toByte()) {
                        v = v * 10 + (payload[p] - '0'.code.toByte())
                        p++
                    }
                    if (p >= payload.size) return null
                    p++
                    piece = v
                } else {
                    p = skipValue(payload, p) ?: return null
                }
            }
            if (p >= payload.size) return null
            p++
            if (piece < 0) return null
            piece to payload.copyOfRange(p, payload.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun rangeEquals(d: ByteArray, off: Int, needle: ByteArray): Boolean {
        for (i in needle.indices) {
            if (d[off + i] != needle[i]) return false
        }
        return true
    }

    private class RC4(private val key: ByteArray) {
        private val s = IntArray(256)
        private var x = 0
        private var y = 0

        init {
            for (i in 0..255) s[i] = i
            var j = 0
            for (i in 0..255) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
                val t = s[i]
                s[i] = s[j]
                s[j] = t
            }
        }

        fun copy(): RC4 {
            val c = RC4(ByteArray(1))
            System.arraycopy(s, 0, c.s, 0, 256)
            c.x = x
            c.y = y
            return c
        }

        fun crypt(d: ByteArray, off: Int, len: Int) {
            for (n in 0 until len) {
                x = (x + 1) and 0xff
                y = (y + s[x]) and 0xff
                val t = s[x]
                s[x] = s[y]
                s[y] = t
                d[off + n] = (d[off + n].toInt() xor s[(s[x] + s[y]) and 0xff]).toByte()
            }
        }

        fun discard(n: Int) {
            val t = ByteArray(n)
            crypt(t, 0, n)
        }
    }

    private fun sha1(vararg parts: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val r = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, r, 0, a.size)
        System.arraycopy(b, 0, r, a.size, b.size)
        return r
    }

    private fun concat3(a: ByteArray, b: ByteArray, c: ByteArray): ByteArray {
        val r = ByteArray(a.size + b.size + c.size)
        System.arraycopy(a, 0, r, 0, a.size)
        System.arraycopy(b, 0, r, a.size, b.size)
        System.arraycopy(c, 0, r, a.size + b.size, c.size)
        return r
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val r = ByteArray(a.size)
        for (i in a.indices) r[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return r
    }

    private fun export96(k: BigInteger): ByteArray {
        var b = k.toByteArray()
        if (b.size > 1 && b[0].toInt() == 0) b = b.copyOfRange(1, b.size)
        val r = ByteArray(96)
        if (b.size <= 96) System.arraycopy(b, 0, r, 96 - b.size, b.size)
        else System.arraycopy(b, b.size - 96, r, 0, 96)
        return r
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun putShort(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun getInt(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
    }

    private fun getShort(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
    }

    private fun indexOfSeq(buf: ByteArray, len: Int, needle: ByteArray): Int {
        outer@ for (i in 0..len - needle.size) {
            for (j in needle.indices) {
                if (buf[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun randBytes(rnd: java.security.SecureRandom, n: Int): ByteArray {
        val b = ByteArray(n)
        rnd.nextBytes(b)
        return b
    }

    private val DH_PRIME_HEX = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563"

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
