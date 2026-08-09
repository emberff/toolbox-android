package com.xvd.app

import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.alerts.AddTorrentAlert
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert
import com.frostwire.jlibtorrent.alerts.SaveResumeDataAlert
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert
import com.frostwire.jlibtorrent.alerts.TorrentRemovedAlert
import com.frostwire.jlibtorrent.swig.string_int_pair

object TorrentEngine {

    @Volatile
    private var session: SessionManager? = null

    val isRunning: Boolean get() = session?.isRunning() == true

    fun start() {
        if (isRunning) return
        val sm = SessionManager()
        sm.addListener(listener)
        sm.start()
        sm.startDht()
        try {
            sm.applySettings(SettingsPack().apply { enableDht(true) })
        } catch (ignored: Exception) {
        }
        addDhtBootstrapNodes(sm)
        session = sm
    }

    private fun addDhtBootstrapNodes(sm: SessionManager) {
        val s = sm.swig()
        val nodes = listOf(
            "router.bittorrent.com" to 6881,
            "dht.transmissionbt.com" to 6881,
            "router.utorrent.com" to 6881,
            "dht.aelitis.com" to 6881,
            "dht.libtorrent.org" to 25401
        )
        for ((host, port) in nodes) {
            try {
                s.add_dht_node(string_int_pair(host, port))
            } catch (ignored: Exception) {
            }
        }
    }

    fun stop() {
        session?.let { sm ->
            sm.removeListener(listener)
            if (sm.isRunning()) sm.stop()
        }
        session = null
    }

    fun add(params: AddTorrentParams) {
        session?.swig()?.async_add_torrent(params.swig())
    }

    fun find(hex: String): TorrentHandle? {
        val sm = session ?: return null
        return try {
            sm.find(Sha1Hash(hex))?.takeIf { it.isValid() }
        } catch (e: Exception) {
            null
        }
    }

    fun pause(handle: TorrentHandle) {
        if (handle.isValid()) {
            handle.pause()
            handle.saveResumeData()
        }
    }

    fun resume(handle: TorrentHandle) {
        if (handle.isValid()) handle.resume()
    }

    fun remove(handle: TorrentHandle) {
        if (handle.isValid()) session?.remove(handle)
    }

    fun refreshStatus() {
        session?.postTorrentUpdates()
    }

    fun saveAllResume(handles: List<TorrentHandle>) {
        handles.forEach { h ->
            if (h.isValid() && h.needSaveResumeData()) h.saveResumeData()
        }
    }

    fun applyFilePriorities(handle: TorrentHandle, selected: Set<Int>) {
        if (!handle.isValid()) return
        val ti = handle.torrentFile() ?: return
        val n = ti.files().numFiles()
        for (i in 0 until n) {
            val p = if (i in selected) Priority.NORMAL else Priority.IGNORE
            try {
                handle.filePriority(i, p)
            } catch (ignored: Exception) {
            }
        }
    }

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.ADD_TORRENT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.TORRENT_REMOVED.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.TORRENT_CHECKED.swig()
        )

        override fun alert(alert: Alert<*>) {
            try {
                when (alert) {
                    is AddTorrentAlert -> TorrentManager.onTorrentAdded(alert.handle())
                    is MetadataReceivedAlert -> TorrentManager.onMetadataReceived(alert.handle())
                    is TorrentFinishedAlert -> TorrentManager.onTorrentFinished(alert.handle())
                    is TorrentRemovedAlert -> TorrentManager.onTorrentRemoved(alert.handle())
                    is SaveResumeDataAlert -> TorrentManager.onSaveResumeData(alert)
                    else -> {}
                }
            } catch (ignored: Exception) {
            }
        }
    }
}
