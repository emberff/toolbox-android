package com.xvd.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class TorrentActivity : AppCompatActivity() {

    private lateinit var etMagnet: EditText
    private lateinit var tvStorageStatus: TextView
    private lateinit var btnStorage: MaterialButton
    private lateinit var adapter: TorrentAdapter

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onTorrentFilePicked(uri)
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStorageStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_torrent)

        TorrentManager.init(this)

        etMagnet = findViewById(R.id.etMagnet)
        tvStorageStatus = findViewById(R.id.tvStorageStatus)
        btnStorage = findViewById(R.id.btnStorage)
        val recycler = findViewById<RecyclerView>(R.id.rvTorrents)
        val tvEmpty = findViewById<TextView>(R.id.tvTorrentEmpty)

        findViewById<MaterialButton>(R.id.btnTorrentBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnAddMagnet).setOnClickListener { addMagnet() }
        findViewById<MaterialButton>(R.id.btnPickFile).setOnClickListener { pickFile() }
        btnStorage.setOnClickListener { requestStorageAccess() }

        adapter = TorrentAdapter(
            emptyList(),
            onToggle = { item ->
                val running = item.state == "下载中" || item.state == "获取元数据" ||
                    item.state == "校验中" || item.state == "校验断点" ||
                    item.state == "分配空间" || item.state == "做种中"
                if (running) TorrentManager.pause(item.infoHash) else {
                    ensureService()
                    TorrentManager.resume(item.infoHash)
                }
            },
            onDelete = { TorrentManager.remove(it.infoHash) },
            onPlay = { onPlay(it) },
            onFiles = { item ->
                if (!item.hasMetadata) {
                    toast("元数据尚未就绪，请稍候")
                } else {
                    startActivity(
                        Intent(this, FileSelectActivity::class.java)
                            .putExtra(FileSelectActivity.EXTRA_MODE, FileSelectActivity.MODE_INFO_HASH)
                            .putExtra(FileSelectActivity.EXTRA_INFO_HASH, item.infoHash)
                            .putExtra(FileSelectActivity.EXTRA_NAME, item.name)
                    )
                }
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TorrentManager.torrents.collect { list ->
                        adapter.submit(list)
                        tvEmpty.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    val tvEngine = findViewById<TextView>(R.id.tvEngineStatus)
                    while (true) {
                        val dht = if (TorrentEngine.isRunning) {
                            if (TorrentEngine.dhtRunning) "运行" else "未运行"
                        } else {
                            "-"
                        }
                        val ports = if (TorrentEngine.isRunning) TorrentEngine.listenPorts else "-"
                        tvEngine.text = "引擎: ${if (TorrentEngine.isRunning) "运行" else "未运行"} · DHT: $dht · 监听: $ports"
                        delay(2000)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStorageStatus()
        if (TorrentManager.torrents.value.isNotEmpty()) ensureService()
    }

    private fun addMagnet() {
        val m = etMagnet.text.toString().trim()
        if (m.isBlank()) {
            toast("请输入磁力链接")
            return
        }
        if (!m.startsWith("magnet:", ignoreCase = true)) {
            toast("链接需以 magnet: 开头")
            return
        }
        when (TorrentManager.addMagnet(m)) {
            0 -> {
                toast("已添加任务")
                etMagnet.text?.clear()
                ensureService()
            }
            2 -> toast("该种子已在列表中")
            else -> toast("无效的磁力链接")
        }
    }

    private fun pickFile() {
        filePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream"))
    }

    private fun onTorrentFilePicked(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            startActivity(
                Intent(this, FileSelectActivity::class.java)
                    .putExtra(FileSelectActivity.EXTRA_MODE, FileSelectActivity.MODE_FILE)
                    .putExtra(FileSelectActivity.EXTRA_BYTES, bytes)
            )
        } catch (e: Exception) {
            toast("读取文件失败: ${e.message}")
        }
    }

    private fun ensureService() {
        val intent = Intent(this, TorrentService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun hasStorageAccess(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT == 29 -> true
            else -> ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            Build.VERSION.SDK_INT < 29 -> {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> toast("Android 10 已自动授予此应用的存储访问权限")
        }
    }

    private fun refreshStorageStatus() {
        val ok = hasStorageAccess()
        tvStorageStatus.text = if (ok) {
            "存储权限：已就绪"
        } else {
            "存储权限：未授予（下载将无法写入公共目录）"
        }
        tvStorageStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (ok) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        btnStorage.isEnabled = !ok
    }

    private fun onPlay(item: TorrentItem) {
        val handle = TorrentEngine.find(item.infoHash)
        val ti = handle?.torrentFile()
        val media = ti?.let { TorrentMediaPicker.pick(it) }
        if (media == null) {
            toast("该任务没有可播放的视频")
            return
        }
        if (media.exoSupported) {
            startActivity(
                Intent(this, TorrentPlayerActivity::class.java)
                    .putExtra(TorrentPlayerActivity.EXTRA_INFO_HASH, item.infoHash)
            )
        } else {
            if (!item.isFinished) {
                toast("该格式需下载完成后用系统播放器播放")
                return
            }
            openWithSystemPlayer(media.path)
        }
    }

    private fun openWithSystemPlayer(relativePath: String) {
        val file = File(TorrentManager.saveDirPath(), relativePath)
        if (!file.exists()) {
            toast("文件不存在")
            return
        }
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("video", uri)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("没有可用的播放器")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
