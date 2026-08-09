package com.xvd.app

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

class OverlayHelper(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    fun canDraw(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    fun ensureOverlay() {
        if (!canDraw()) return
        if (overlayView != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_pill, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = 8
        lp.y = 180
        view.setOnClickListener {
            try {
                context.startActivity(
                    Intent(context, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (ignored: Exception) {
            }
        }
        try {
            wm.addView(view, lp)
            overlayView = view
        } catch (e: Exception) {
            overlayView = null
        }
    }

    fun removeOverlay() {
        overlayView?.let { v ->
            try {
                wm.removeView(v)
            } catch (ignored: Exception) {
            }
        }
        overlayView = null
    }
}
