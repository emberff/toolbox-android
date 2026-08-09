package com.xvd.app

import com.frostwire.jlibtorrent.TorrentInfo

object TorrentMediaPicker {

    data class MediaFile(
        val index: Int,
        val path: String,
        val size: Long,
        val name: String,
        val exoSupported: Boolean
    )

    private val VIDEO_EXTS = setOf(
        "mp4", "m4v", "webm", "ts", "3gp", "mov", "mkv", "avi", "flv", "wmv", "mpg", "mpeg", "f4v"
    )

    private val EXO_EXTS = setOf("mp4", "m4v", "webm", "ts", "3gp")

    fun pick(ti: TorrentInfo): MediaFile? {
        val fs = ti.files()
        var best: MediaFile? = null
        for (i in 0 until fs.numFiles()) {
            val path = fs.filePath(i)
            if (path.isBlank()) continue
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in VIDEO_EXTS) continue
            val size = fs.fileSize(i)
            if (best == null || size > best.size) {
                best = MediaFile(
                    index = i,
                    path = path,
                    size = size,
                    name = path.substringAfterLast('/').ifBlank { path },
                    exoSupported = ext in EXO_EXTS
                )
            }
        }
        return best
    }
}
