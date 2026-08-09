package com.xvd.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logText: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var overlayStatus: TextView
    private lateinit var notifyStatus: TextView
    private lateinit var batteryStatus: TextView

    private val notifyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        logText = findViewById(R.id.logText)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        overlayStatus = findViewById(R.id.overlayStatus)
        notifyStatus = findViewById(R.id.notifyStatus)
        batteryStatus = findViewById(R.id.batteryStatus)

        val btnReadNow = findViewById<MaterialButton>(R.id.btnReadNow)
        val btnOverlay = findViewById<MaterialButton>(R.id.btnOverlay)
        val btnNotify = findViewById<MaterialButton>(R.id.btnNotify)
        val btnBattery = findViewById<MaterialButton>(R.id.btnBattery)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }

        val etLink = findViewById<EditText>(R.id.etLink)
        findViewById<MaterialButton>(R.id.btnDownloadLink).setOnClickListener {
            val link = etLink.text.toString().trim()
            if (link.isBlank()) {
                Toast.makeText(this, "请输入 X/推特 视频链接", Toast.LENGTH_SHORT).show()
            } else {
                ClipboardProcessor.processText(this, link, force = true)
            }
        }

        btnStart.setOnClickListener { startMonitoring() }
        btnStop.setOnClickListener {
            stopService(Intent(this, ClipboardMonitorService::class.java))
            DownloadBus.addLog("已停止监听")
            refreshStatus()
        }
        btnReadNow.setOnClickListener {
            ClipboardProcessor.process(this, force = true)
        }
        btnOverlay.setOnClickListener { requestOverlayPermission() }
        btnNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                refreshPermissionStatus()
            }
        }
        btnBattery.setOnClickListener { requestBatteryOptimization() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }

        observeBus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshPermissionStatus()
        if (isServiceRunning()) {
            OverlayHelper(this).ensureOverlay()
        }
    }

    private fun observeBus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DownloadBus.state.collect { s ->
                        statusText.text = if (isServiceRunning()) "监听中（后台运行）" else "未运行"
                        if (s.running) {
                            progressBar.visibility = View.VISIBLE
                            progressText.visibility = View.VISIBLE
                            progressBar.progress = s.progress
                            progressText.text = s.message
                        } else if (s.progress == 100) {
                            progressBar.visibility = View.GONE
                            progressText.visibility = View.VISIBLE
                            progressText.text = s.message
                        } else {
                            progressBar.visibility = View.GONE
                            progressText.visibility = View.GONE
                            progressText.text = s.message
                        }
                    }
                }
                launch {
                    DownloadBus.log.collect { lines ->
                        if (lines.isEmpty()) {
                            logText.text = "暂无记录"
                        } else {
                            logText.text = lines.joinToString("\n")
                        }
                    }
                }
            }
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, ClipboardMonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            DownloadBus.addLog("已启动后台监听")
        } catch (e: Exception) {
            DownloadBus.addLog("启动失败: ${e.message}")
        }
        refreshStatus()
    }

    private fun isServiceRunning(): Boolean = ClipboardMonitorService.isRunning

    private fun refreshStatus() {
        val running = isServiceRunning()
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        statusText.text = if (running) "监听中（后台运行）" else "未运行"
    }

    private fun refreshPermissionStatus() {
        val overlay = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlay) "悬浮窗权限：已授予" else "悬浮窗权限：未授予"
        overlayStatus.setTextColor(
            ContextCompat.getColor(this,
                if (overlay) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )

        val notified = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        notifyStatus.text = if (notified) "通知权限：已授予" else "通知权限：未授予"
        notifyStatus.setTextColor(
            ContextCompat.getColor(this,
                if (notified) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignored = pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = if (ignored) "电池优化：已忽略" else "电池优化：未忽略"
        batteryStatus.setTextColor(
            ContextCompat.getColor(this,
                if (ignored) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            refreshPermissionStatus()
            return
        }
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun requestBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            refreshPermissionStatus()
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            DownloadBus.addLog("请手动在系统设置中关闭电池优化")
        }
    }
}
