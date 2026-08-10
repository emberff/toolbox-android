package com.xvd.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BackgroundRun {

    fun isExempt(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun requestExempt(context: Context): Boolean {
        if (isExempt(context)) return true
        return try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun statusText(context: Context): String =
        if (isExempt(context)) "允许后台：已开启" else "允许后台：未开启（后台任务可能被系统清理）"
}
