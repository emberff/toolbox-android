package com.xvd.app

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class TorrentStreamSource(
    private val infoHash: String
) : BaseDataSource(false) {

    private var size = 0L
    private var position = 0L
    private var raf: RandomAccessFile? = null

    class Factory(private val infoHash: String) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentStreamSource(infoHash)
    }

    override fun open(dataSpec: DataSpec): Long {
        val handle = TorrentEngine.find(infoHash)
            ?: throw IOException("种子未加载")
        if (!handle.isValid()) throw IOException("种子无效")

        TorrentManager.resume(infoHash)

        val ti = handle.torrentFile() ?: throw IOException("元数据未就绪")
        val media = TorrentMediaPicker.pick(ti) ?: throw IOException("未找到视频文件")
        if (media.size <= 0) throw IOException("视频文件为空")
        size = media.size

        prioritizeFile(handle, ti, media)

        position = dataSpec.position
        if (position >= size) throw IOException("超出文件范围")

        val initialEnd = minOf(size, position + 4L * ti.pieceLength().toLong())
        if (!waitForPieces(handle, ti, position, initialEnd)) {
            throw IOException("等待初始数据超时")
        }

        val disk = File(TorrentManager.saveDirPath(), media.path)
        val deadline = SystemClock.elapsedRealtime() + PIECE_WAIT_TIMEOUT
        while (!disk.exists()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw IOException("文件尚未生成")
            }
            Thread.sleep(100)
        }

        raf = RandomAccessFile(disk, "r").apply { seek(position) }
        return size
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remain = size - position
        if (remain <= 0) return C.RESULT_END_OF_INPUT

        val handle = TorrentEngine.find(infoHash) ?: return C.RESULT_END_OF_INPUT
        val ti = handle.torrentFile() ?: return C.RESULT_END_OF_INPUT

        var toRead = minOf(length.toLong(), remain).toInt()
        val pl = ti.pieceLength().toLong()
        val pieceEnd = (position / pl + 1) * pl
        if (position + toRead > pieceEnd) {
            toRead = (pieceEnd - position).toInt()
        }
        if (toRead <= 0) toRead = 1

        if (!waitForPieces(handle, ti, position, position + toRead)) {
            throw IOException("等待数据超时")
        }

        val file = raf ?: throw IOException("文件未打开")
        file.seek(position)
        val n = file.read(buffer, offset, toRead)
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        return n
    }

    override fun close() {
        raf?.let {
            try {
                it.close()
            } catch (ignored: Exception) {
            }
        }
        raf = null
    }

    override fun getUri(): Uri? = Uri.parse("torrent://$infoHash")

    private fun prioritizeFile(handle: TorrentHandle, ti: TorrentInfo, media: TorrentMediaPicker.MediaFile) {
        val fs = ti.files()
        val pl = ti.pieceLength().toLong()
        val start = fs.fileOffset(media.index)
        val first = (start / pl).toInt()
        val last = ((start + media.size - 1) / pl).toInt()
        for (p in first..last) {
            try {
                handle.piecePriority(p, Priority.SEVEN)
            } catch (ignored: Exception) {
            }
        }
    }

    private fun waitForPieces(handle: TorrentHandle, ti: TorrentInfo, start: Long, end: Long): Boolean {
        val pl = ti.pieceLength().toLong()
        val first = (start / pl).toInt()
        val last = ((end - 1) / pl).coerceAtLeast(first.toLong()).toInt()
        val deadline = SystemClock.elapsedRealtime() + PIECE_WAIT_TIMEOUT
        while (true) {
            val avail = handle.pieceAvailability()
            if (avail != null && avail.size > last) {
                var missing = false
                for (p in first..last) {
                    if (avail[p] != 1) {
                        missing = true
                        try {
                            handle.piecePriority(p, Priority.SEVEN)
                        } catch (ignored: Exception) {
                        }
                    }
                }
                if (!missing) return true
            }
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(100)
        }
    }

    companion object {
        private const val PIECE_WAIT_TIMEOUT = 5 * 60 * 1000L
    }
}
