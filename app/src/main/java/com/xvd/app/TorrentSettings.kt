package com.xvd.app

import android.content.Context
import android.content.SharedPreferences
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.swig.settings_pack

object TorrentSettings {

    private const val PREFS = "torrent_settings"
    private lateinit var prefs: SharedPreferences

    // libtorrent proxy_type
    private const val TYPE_NONE = 0
    private const val TYPE_SOCKS5 = 2
    private const val TYPE_SOCKS5_PW = 3
    private const val TYPE_HTTP = 4
    private const val TYPE_HTTP_PW = 5

    data class ProxyConfig(
        val enabled: Boolean = false,
        val type: String = "socks5",
        val host: String = "",
        val port: String = "",
        val user: String = "",
        val pass: String = ""
    )

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun proxyConfig(): ProxyConfig {
        return ProxyConfig(
            enabled = prefs.getBoolean("proxy_enabled", false),
            type = prefs.getString("proxy_type", "socks5") ?: "socks5",
            host = prefs.getString("proxy_host", "") ?: "",
            port = prefs.getString("proxy_port", "") ?: "",
            user = prefs.getString("proxy_user", "") ?: "",
            pass = prefs.getString("proxy_pass", "") ?: ""
        )
    }

    fun saveProxy(cfg: ProxyConfig) {
        prefs.edit()
            .putBoolean("proxy_enabled", cfg.enabled)
            .putString("proxy_type", cfg.type)
            .putString("proxy_host", cfg.host)
            .putString("proxy_port", cfg.port)
            .putString("proxy_user", cfg.user)
            .putString("proxy_pass", cfg.pass)
            .apply()
    }

    fun applyTo(settings: SettingsPack) {
        val cfg = proxyConfig()
        if (!cfg.enabled || cfg.host.isBlank() || cfg.port.isBlank()) {
            settings.setInteger(settings_pack.int_types.proxy_type.swigValue(), TYPE_NONE)
            return
        }
        val port = cfg.port.toIntOrNull() ?: 0
        val hasAuth = cfg.user.isNotBlank()
        val type = when (cfg.type) {
            "http" -> if (hasAuth) TYPE_HTTP_PW else TYPE_HTTP
            else -> if (hasAuth) TYPE_SOCKS5_PW else TYPE_SOCKS5
        }
        settings.setInteger(settings_pack.int_types.proxy_type.swigValue(), type)
        settings.setInteger(settings_pack.int_types.proxy_port.swigValue(), port)
        settings.setString(settings_pack.string_types.proxy_hostname.swigValue(), cfg.host)
        settings.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(), true)
        if (hasAuth) {
            settings.setString(settings_pack.string_types.proxy_username.swigValue(), cfg.user)
            settings.setString(settings_pack.string_types.proxy_password.swigValue(), cfg.pass)
        }
    }
}
