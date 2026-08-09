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
import com.frostwire.jlibtorrent.swig.settings_pack
import com.frostwire.jlibtorrent.swig.string_int_pair

object TorrentEngine {

    @Volatile
    private var session: SessionManager? = null

    val isRunning: Boolean get() = session?.isRunning() == true

    val dhtRunning: Boolean get() = session?.isDhtRunning() == true

    val listenPorts: String
        get() = session?.listenEndpoints()?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"

    val nativeListenPort: Int
        get() = try {
            session?.swig()?.listen_port() ?: 0
        } catch (e: Exception) {
            0
        }

    val listening: Boolean
        get() = try {
            session?.swig()?.is_listening() ?: false
        } catch (e: Exception) {
            false
        }

    private val _engineErrors = ArrayDeque<String>()

    private val _javaAnnounceLog = ArrayDeque<String>()

    val engineErrors: List<String>
        get() = synchronized(_engineErrors) { _engineErrors.toList() }

    val javaAnnounceLog: List<String>
        get() = synchronized(_javaAnnounceLog) { _javaAnnounceLog.toList() }

    val lastEngineError: String
        get() = engineErrors.lastOrNull() ?: "无"

    private fun recordError(msg: String) {
        synchronized(_engineErrors) {
            if (_engineErrors.lastOrNull() == msg) return
            _engineErrors.addLast(msg)
            while (_engineErrors.size > 8) _engineErrors.removeFirst()
        }
    }

    fun recordEngineInfo(msg: String) = recordError(msg)

    fun recordJavaAnnounce(msg: String) {
        synchronized(_javaAnnounceLog) {
            _javaAnnounceLog.addLast(msg)
            while (_javaAnnounceLog.size > 16) _javaAnnounceLog.removeFirst()
        }
    }

    @Volatile
    private var lastRebindAttempt = 0L

    fun ensureListening() {
        val sm = session ?: return
        if (!sm.isRunning()) return
        if (listening) return
        val now = System.currentTimeMillis()
        if (now - lastRebindAttempt < 15000) return
        lastRebindAttempt = now
        try {
            val settings = SettingsPack().apply {
                setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0")
            }
            TorrentSettings.applyTo(settings)
            sm.applySettings(settings)
            recordError("检测到未监听, 已重试绑定监听端口")
        } catch (ignored: Exception) {
        }
    }

    fun start() {
        if (isRunning) return
        val sm = SessionManager()
        sm.addListener(listener)
        sm.start()
        sm.startDht()
        val settings = SettingsPack().apply {
            setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0")
            setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), false)
            setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        }
        TorrentSettings.applyTo(settings)
        try {
            sm.applySettings(settings)
        } catch (ignored: Exception) {
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
            val ip = resolveHostIp(host)
            if (ip != null) {
                try {
                    s.add_dht_node(string_int_pair(ip, port))
                } catch (ignored: Exception) {
                }
            }
        }
    }

    fun resolveHostIp(host: String): String? {
        return try {
            val addrs = java.net.InetAddress.getAllByName(host)
            addrs.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                ?: addrs.firstOrNull()?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    fun rewriteTrackerUrlToIp(url: String): String? {
        return try {
            val u = java.net.URI(url)
            val scheme = u.scheme ?: return null
            if (scheme == "https") return null
            val host = u.host ?: return null
            if (host.contains(":") || Regex("^[0-9.]+$").matches(host)) return null
            val ip = resolveHostIp(host) ?: return null
            var port = u.port
            if (port == -1) port = if (scheme == "http") 80 else 0
            val path = u.rawPath ?: "/"
            val query = if (u.rawQuery != null) "?${u.rawQuery}" else ""
            "$scheme://$ip:$port$path$query"
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        session?.let { sm ->
            sm.removeListener(listener)
            if (sm.isRunning()) sm.stop()
        }
        session = null
    }

    fun restart() {
        Thread {
            try {
                stop()
                Thread.sleep(300)
                start()
            } catch (ignored: Exception) {
            }
        }.start()
    }

    fun add(params: AddTorrentParams) {
        val s = session?.swig() ?: return
        try {
            val ec = com.frostwire.jlibtorrent.swig.error_code()
            s.add_torrent(params.swig(), ec)
        } catch (ignored: Exception) {
        }
    }

    fun forceReannounce(hex: String) {
        find(hex)?.let { h ->
            try {
                h.forceReannounce()
                h.forceDHTAnnounce()
            } catch (ignored: Exception) {
            }
        }
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
            AlertType.TORRENT_CHECKED.swig(),
            AlertType.LISTEN_SUCCEEDED.swig(),
            AlertType.LISTEN_FAILED.swig(),
            AlertType.TRACKER_ERROR.swig(),
            AlertType.TRACKER_REPLY.swig(),
            AlertType.TRACKER_WARNING.swig(),
            AlertType.PEER_ERROR.swig(),
            AlertType.PEER_CONNECT.swig(),
            AlertType.DHT_ERROR.swig(),
            AlertType.DHT_BOOTSTRAP.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.SESSION_ERROR.swig()
        )

        override fun alert(alert: Alert<*>) {
            try {
                when (alert) {
                    is AddTorrentAlert -> TorrentManager.onTorrentAdded(alert.handle())
                    is MetadataReceivedAlert -> TorrentManager.onMetadataReceived(alert.handle())
                    is TorrentFinishedAlert -> TorrentManager.onTorrentFinished(alert.handle())
                    is TorrentRemovedAlert -> TorrentManager.onTorrentRemoved(alert.handle())
                    is SaveResumeDataAlert -> TorrentManager.onSaveResumeData(alert)
                    is com.frostwire.jlibtorrent.alerts.ListenFailedAlert ->
                        recordError("监听失败: ${alert.error().message()} (${alert.listenInterface()})")
                    is com.frostwire.jlibtorrent.alerts.TrackerErrorAlert ->
                        recordError("Tracker错误: ${alert.message()}")
                    is com.frostwire.jlibtorrent.alerts.TrackerWarningAlert ->
                        recordError("Tracker警告: ${alert.message()}")
                    is com.frostwire.jlibtorrent.alerts.PeerErrorAlert ->
                        recordError("peer连接失败 ${alert.endpoint()}: ${alert.error().message()}")
                    is com.frostwire.jlibtorrent.alerts.PeerConnectAlert ->
                        recordError("peer已连接 ${alert.endpoint()}")
                    is com.frostwire.jlibtorrent.alerts.DhtErrorAlert ->
                        recordError("DHT错误: ${alert.message()}")
                    else -> {}
                }
            } catch (ignored: Exception) {
            }
        }
    }
}
