package com.xvd.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import java.io.File

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val start = Intent(context, ClipboardMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(start)
                } else {
                    context.startService(start)
                }
            } catch (ignored: Exception) {
            }
            if (hasTorrents(context)) {
                try {
                    val start = Intent(context, TorrentService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(start)
                    } else {
                        context.startService(start)
                    }
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun hasTorrents(context: Context): Boolean {
        return try {
            val f = File(context.filesDir, "torrents.json")
            if (!f.exists()) return false
            JSONArray(f.readText()).length() > 0
        } catch (e: Exception) {
            false
        }
    }
}
