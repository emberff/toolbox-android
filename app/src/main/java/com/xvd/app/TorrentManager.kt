package com.xvd.app

import android.content.Context
import android.os.Build
import android.os.Environment
import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.TorrentStatus
import com.frostwire.jlibtorrent.alerts.SaveResumeDataAlert
import com.frostwire.jlibtorrent.swig.add_torrent_params
import com.frostwire.jlibtorrent.swig.byte_vector
import com.frostwire.jlibtorrent.swig.error_code
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object TorrentManager {

    private val lock = Any()
    private lateinit var appContext: Context

    private val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "https://tracker.tamersunion.org:443/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.stealth.si:80/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "https://opentracker.i2p.rocks:443/announce",
        "https://tracker.gbitt.info:443/announce",
        "https://p4p.arenabg.com:1337/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "udp://tracker.bittor.pw:1337/announce",
        "udp://tracker.leechers-paradise.org:6969/announce",
        "udp://tracker.dler.org:6969/announce"
    )

    val torrents = MutableStateFlow<List<TorrentItem>>(emptyList())

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        load()
    }

    fun saveDir(): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, "种子下载")
    }

    fun saveDirPath(): String = saveDir().absolutePath

    fun snapshot(): List<TorrentItem> = synchronized(lock) { items.values.toList() }

    fun isEmpty(): Boolean = synchronized(lock) { items.isEmpty() }

    fun summary(): Pair<Int, Long> {
        var active = 0
        var rate = 0L
        synchronized(lock) {
            for (item in items.values) {
                if (!item.isFinished) active++
                rate += item.downloadRate
            }
        }
        return active to rate
    }

    fun addMagnet(magnet: String): Int {
        val m = magnet.trim()
        return try {
            val atp = AddTorrentParams.parseMagnetUri(m)
            val hex = atp.infoHash().toHex()
            if (hex.isBlank()) return 1
            synchronized(lock) {
                if (items.containsKey(hex)) return 2
                items[hex] = TorrentItem(
                    infoHash = hex,
                    name = "获取中…",
                    savePath = saveDirPath(),
                    source = "magnet",
                    sourceData = m,
                    state = "等待中",
                    progress = 0f,
                    downloadRate = 0, uploadRate = 0,
                    totalDone = 0, totalWanted = 0,
                    numPeers = 0, numSeeds = 0
                )
            }
            atp.savePath(saveDirPath())
            withPublicTrackers(atp)
            TorrentEngine.add(atp)
            emit()
            persist()
            0
        } catch (e: Exception) {
            1
        }
    }

    fun addTorrentFile(bytes: ByteArray, selectedIndices: Set<Int>? = null): Int {
        val ti = try {
            TorrentInfo.bdecode(bytes)
        } catch (e: Exception) {
            return 1
        } ?: return 1
        val hex = ti.infoHash().toHex()
        if (hex.isBlank()) return 1
        synchronized(lock) {
            if (items.containsKey(hex)) return 2
            items[hex] = TorrentItem(
                infoHash = hex,
                name = ti.name().ifBlank { "获取中…" },
                savePath = saveDirPath(),
                source = "file",
                sourceData = "",
                state = "等待中",
                progress = 0f,
                downloadRate = 0, uploadRate = 0,
                totalDone = 0, totalWanted = 0,
                numPeers = 0, numSeeds = 0
            )
        }
        val dir = File(appContext.filesDir, "torrents")
        dir.mkdirs()
        File(dir, "$hex.torrent").writeBytes(bytes)
        val atp = AddTorrentParams.createInstance().apply {
            torrentInfo(ti)
            savePath(saveDirPath())
        }
        withPublicTrackers(atp)
        selectedIndices?.let { selected ->
            val n = ti.files().numFiles()
            val bv = byte_vector()
            for (i in 0 until n) {
                bv.push_back(if (i in selected) Priority.NORMAL.swig().toByte() else Priority.IGNORE.swig().toByte())
            }
            try {
                atp.swig().set_file_priorities2(bv)
            } catch (ignored: Exception) {
            }
        }
        TorrentEngine.add(atp)
        emit()
        persist()
        return 0
    }

    fun setFilePriorities(hex: String, selectedIndices: Set<Int>) {
        TorrentEngine.find(hex)?.let { TorrentEngine.applyFilePriorities(it, selectedIndices) }
    }

    fun torrentFileListFromBytes(bytes: ByteArray): List<TorrentFileInfo>? {
        val ti = try {
            TorrentInfo.bdecode(bytes)
        } catch (e: Exception) {
            return null
        } ?: return null
        return torrentFileList(ti)
    }

    fun torrentFileList(hex: String): List<TorrentFileInfo>? {
        val handle = TorrentEngine.find(hex) ?: return null
        val ti = handle.torrentFile() ?: return null
        return torrentFileList(ti)
    }

    fun torrentFileList(ti: TorrentInfo): List<TorrentFileInfo> {
        val fs = ti.files()
        val list = mutableListOf<TorrentFileInfo>()
        for (i in 0 until fs.numFiles()) {
            val path = fs.filePath(i)
            list.add(
                TorrentFileInfo(
                    index = i,
                    path = path,
                    name = path.substringAfterLast('/').ifBlank { path },
                    size = fs.fileSize(i)
                )
            )
        }
        return list
    }

    private fun withPublicTrackers(atp: AddTorrentParams) {
        try {
            val current = atp.trackers().toMutableList()
            for (t in PUBLIC_TRACKERS) {
                if (t !in current) current.add(t)
            }
            atp.trackers(current)
        } catch (ignored: Exception) {
        }
    }

    fun pause(hex: String) {
        TorrentEngine.find(hex)?.let { TorrentEngine.pause(it) }
    }

    fun resume(hex: String) {
        TorrentEngine.find(hex)?.let { TorrentEngine.resume(it) }
    }

    fun remove(hex: String) {
        TorrentEngine.find(hex)?.let { TorrentEngine.remove(it) }
        synchronized(lock) { items.remove(hex) }
        deleteResume(hex)
        File(appContext.filesDir, "torrents/$hex.torrent").delete()
        emit()
        persist()
    }

    fun resumeAll() {
        val list = snapshot()
        for (item in list) {
            try {
                val resumeFile = File(appContext.filesDir, "resume/${item.infoHash}.resume")
                val fromResume = if (resumeFile.exists()) {
                    readResumeData(resumeFile.readBytes())?.let { p ->
                        AddTorrentParams(p).apply {
                            if (savePath().isBlank()) savePath(item.savePath)
                        }
                    }
                } else {
                    null
                }
                val atp = fromResume ?: when (item.source) {
                    "file" -> {
                        val tf = File(appContext.filesDir, "torrents/${item.infoHash}.torrent")
                        if (!tf.exists()) continue
                        val ti = TorrentInfo.bdecode(tf.readBytes()) ?: continue
                        AddTorrentParams.createInstance().apply {
                            torrentInfo(ti)
                            savePath(item.savePath)
                        }
                    }
                    else -> AddTorrentParams.parseMagnetUri(item.sourceData)
                }
                withPublicTrackers(atp)
                TorrentEngine.add(atp)
            } catch (ignored: Exception) {
            }
        }
    }

    fun refreshFromEngine() {
        TorrentEngine.refreshStatus()
        for (item in snapshot()) {
            val h = TorrentEngine.find(item.infoHash) ?: continue
            val st = h.status()
            var name = item.name
            if ((name.isBlank() || name == "获取中…") && st.name().isNotBlank()) {
                name = st.name()
            }
            var hasVideo = item.hasVideo
            var hasMetadata = item.hasMetadata
            val tf = h.torrentFile()
            if (tf != null) {
                hasMetadata = true
                if (!hasVideo) {
                    hasVideo = TorrentMediaPicker.pick(tf) != null
                }
            }
            val updated = item.copy(
                name = name,
                state = statusText(st.state()),
                progress = st.progress(),
                downloadRate = st.downloadRate().toLong(),
                uploadRate = st.uploadRate().toLong(),
                totalDone = st.totalDone(),
                totalWanted = st.totalWanted(),
                numPeers = st.numPeers(),
                numSeeds = st.numSeeds(),
                hasVideo = hasVideo,
                hasMetadata = hasMetadata
            )
            synchronized(lock) { items[item.infoHash] = updated }
        }
        emit()
    }

    fun saveAllResume() {
        val handles = snapshot().mapNotNull { TorrentEngine.find(it.infoHash) }
        TorrentEngine.saveAllResume(handles)
    }

    fun onTorrentAdded(handle: TorrentHandle) {
        if (!handle.isValid()) return
        val hex = handle.infoHash().toHex()
        synchronized(lock) {
            if (items.containsKey(hex)) return
            items[hex] = TorrentItem(
                infoHash = hex,
                name = "获取中…",
                savePath = saveDirPath(),
                source = "magnet",
                sourceData = "",
                state = "等待中",
                progress = 0f,
                downloadRate = 0, uploadRate = 0,
                totalDone = 0, totalWanted = 0,
                numPeers = 0, numSeeds = 0
            )
        }
        emit()
        persist()
    }

    fun onMetadataReceived(handle: TorrentHandle) {
        if (!handle.isValid()) return
        val hex = handle.infoHash().toHex()
        val name = handle.torrentFile()?.name() ?: return
        synchronized(lock) {
            val cur = items[hex] ?: return
            items[hex] = cur.copy(name = name)
        }
        emit()
        persist()
    }

    fun onTorrentFinished(handle: TorrentHandle) {
        if (!handle.isValid()) return
        val hex = handle.infoHash().toHex()
        val name = handle.torrentFile()?.name() ?: handle.status().name()
        NotificationHelper.showTorrentFinished(appContext, name.ifBlank { "种子任务" })
        handle.saveResumeData()
        synchronized(lock) {
            val cur = items[hex] ?: return
            items[hex] = cur.copy(state = "已完成", progress = 1f)
        }
        emit()
        persist()
    }

    fun onTorrentRemoved(handle: TorrentHandle) {
        val hex = try {
            handle.infoHash().toHex()
        } catch (e: Exception) {
            return
        }
        synchronized(lock) { items.remove(hex) }
        deleteResume(hex)
        File(appContext.filesDir, "torrents/$hex.torrent").delete()
        emit()
        persist()
    }

    fun onSaveResumeData(alert: SaveResumeDataAlert) {
        try {
            val hex = alert.handle().infoHash().toHex()
            val bv = add_torrent_params.write_resume_data_buf(alert.params().swig())
            val bytes = ByteArray(bv.size().toInt())
            for (i in bytes.indices) bytes[i] = bv.get(i)
            val dir = File(appContext.filesDir, "resume")
            dir.mkdirs()
            File(dir, "$hex.resume").writeBytes(bytes)
        } catch (ignored: Exception) {
        }
    }

    private val items = LinkedHashMap<String, TorrentItem>()

    private fun emit() {
        torrents.value = synchronized(lock) { items.values.toList() }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            for (item in snapshot()) {
                arr.put(JSONObject().apply {
                    put("infoHash", item.infoHash)
                    put("name", item.name)
                    put("savePath", item.savePath)
                    put("source", item.source)
                    put("sourceData", item.sourceData)
                })
            }
            File(appContext.filesDir, "torrents.json").writeText(arr.toString())
        } catch (ignored: Exception) {
        }
    }

    private fun load() {
        try {
            val f = File(appContext.filesDir, "torrents.json")
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            synchronized(lock) {
                items.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ih = o.optString("infoHash")
                    if (ih.isBlank()) continue
                    items[ih] = TorrentItem(
                        infoHash = ih,
                        name = o.optString("name", "获取中…").ifBlank { "获取中…" },
                        savePath = o.optString("savePath", saveDirPath()),
                        source = o.optString("source", "magnet"),
                        sourceData = o.optString("sourceData"),
                        state = "等待中",
                        progress = 0f,
                        downloadRate = 0, uploadRate = 0,
                        totalDone = 0, totalWanted = 0,
                        numPeers = 0, numSeeds = 0
                    )
                }
            }
            emit()
        } catch (ignored: Exception) {
        }
    }

    private fun readResumeData(bytes: ByteArray): add_torrent_params? {
        return try {
            val bv = byte_vector()
            for (b in bytes) bv.push_back(b)
            val ec = error_code()
            val atp = add_torrent_params.read_resume_data(bv, ec)
            if (ec.value() != 0) null else atp
        } catch (e: Exception) {
            null
        }
    }

    private fun deleteResume(hex: String) {
        File(appContext.filesDir, "resume/$hex.resume").delete()
    }

    private fun statusText(s: TorrentStatus.State): String = when (s) {
        TorrentStatus.State.CHECKING_FILES -> "校验中"
        TorrentStatus.State.DOWNLOADING_METADATA -> "获取元数据"
        TorrentStatus.State.DOWNLOADING -> "下载中"
        TorrentStatus.State.FINISHED -> "已完成"
        TorrentStatus.State.SEEDING -> "做种中"
        TorrentStatus.State.ALLOCATING -> "分配空间"
        TorrentStatus.State.CHECKING_RESUME_DATA -> "校验断点"
        else -> "未知"
    }
}
