package com.xvd.app

import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
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
import com.frostwire.jlibtorrent.swig.settings_pack
import com.frostwire.jlibtorrent.swig.string_int_pair

object TorrentEngine {

    @Volatile
    private var session: SessionManager? = null

    val isRunning: Boolean get() = session?.isRunning() == true

    val dhtRunning: Boolean get() = session?.isDhtRunning() == true

    val listenPorts: String
        get() = session?.listenEndpoints()?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"

    fun start() {
        if (isRunning) return
        val settings = SettingsPack().apply {
            enableDht(true)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            setBoolean(settings_pack.bool_types.use_dht_as_fallback.swigValue(), true)
            setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_outgoing_tcp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_incoming_tcp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
            setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0")
            setString(
                settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                DHT_BOOTSTRAP_NODES.joinToString(",") { "${it.first}:${it.second}" }
            )
            setString(settings_pack.string_types.user_agent.swigValue(), "toolbox/1.1.0 libtorrent/1.2")
        }
        val sm = SessionManager()
        sm.addListener(listener)
        sm.start(SessionParams(settings))
        if (!sm.isDhtRunning()) {
            try {
                sm.startDht()
            } catch (ignored: Exception) {
            }
        }
        addDhtBootstrapNodes(sm)
        session = sm
    }

    private val DHT_BOOTSTRAP_NODES = listOf(
        "router.bittorrent.com" to 6881,
        "dht.transmissionbt.com" to 6881,
        "router.utorrent.com" to 6881,
        "dht.aelitis.com" to 6881,
        "dht.libtorrent.org" to 25401
    )

    private fun addDhtBootstrapNodes(sm: SessionManager) {
        val s = sm.swig()
        for ((host, port) in DHT_BOOTSTRAP_NODES) {
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
