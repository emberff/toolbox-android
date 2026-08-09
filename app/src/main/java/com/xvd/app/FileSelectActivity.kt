package com.xvd.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class FileSelectActivity : AppCompatActivity() {

    private var mode = MODE_FILE
    private var torrentBytes: ByteArray? = null
    private var infoHash = ""
    private var torrentName = ""
    private var files: List<TorrentFileInfo> = emptyList()
    private val selected = mutableSetOf<Int>()
    private lateinit var adapter: FileSelectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_select)

        val title = findViewById<TextView>(R.id.tvFsTitle)
        val summary = findViewById<TextView>(R.id.tvFsSummary)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FILE
        torrentBytes = intent.getByteArrayExtra(EXTRA_BYTES)
        infoHash = intent.getStringExtra(EXTRA_INFO_HASH) ?: ""
        torrentName = intent.getStringExtra(EXTRA_NAME) ?: ""

        if (mode == MODE_FILE) {
            files = TorrentManager.torrentFileListFromBytes(torrentBytes ?: return finish())
                ?: run {
                    toast("无效的种子文件")
                    finish()
                    return
                }
        } else {
            files = TorrentManager.torrentFileList(infoHash)
                ?: run {
                    toast("元数据尚未就绪")
                    finish()
                    return
                }
        }

        title.text = torrentName.ifBlank { "选择文件" }
        files.forEach { selected.add(it.index) }

        val recycler = findViewById<RecyclerView>(R.id.rvFiles)
        adapter = FileSelectAdapter(files, selected)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<MaterialButton>(R.id.btnFsBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnFsAll).setOnClickListener {
            selected.clear()
            files.forEach { selected.add(it.index) }
            adapter.refreshSelection()
            updateSummary(summary)
        }
        findViewById<MaterialButton>(R.id.btnFsNone).setOnClickListener {
            selected.clear()
            adapter.refreshSelection()
            updateSummary(summary)
        }
        findViewById<MaterialButton>(R.id.btnFsConfirm).setOnClickListener { confirm() }

        updateSummary(summary)
    }

    private fun updateSummary(summary: TextView) {
        var total = 0L
        var chosen = 0L
        for (f in files) {
            if (f.index in selected) chosen += f.size
            total += f.size
        }
        summary.text = "已选 ${selected.size}/${files.size} 项 · ${formatSize(chosen)} / ${formatSize(total)}"
    }

    private fun confirm() {
        if (selected.isEmpty()) {
            toast("请至少选择一个文件")
            return
        }
        if (mode == MODE_FILE) {
            when (TorrentManager.addTorrentFile(torrentBytes ?: ByteArray(0), selected)) {
                0 -> {
                    toast("已添加任务")
                    ensureService()
                    finish()
                }
                2 -> toast("该种子已在列表中")
                else -> toast("无效的种子文件")
            }
        } else {
            TorrentManager.setFilePriorities(infoHash, selected)
            toast("已应用文件选择")
            finish()
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val MODE_FILE = "file"
        const val MODE_INFO_HASH = "infoHash"
        const val EXTRA_MODE = "mode"
        const val EXTRA_BYTES = "bytes"
        const val EXTRA_INFO_HASH = "infoHash"
        const val EXTRA_NAME = "name"
    }
}
