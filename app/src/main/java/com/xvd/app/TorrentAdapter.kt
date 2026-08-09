package com.xvd.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.Locale

class TorrentAdapter(
    private var items: List<TorrentItem>,
    private val onToggle: (TorrentItem) -> Unit,
    private val onDelete: (TorrentItem) -> Unit,
    private val onPlay: (TorrentItem) -> Unit
) : RecyclerView.Adapter<TorrentAdapter.VH>() {

    class VH(val view: View) : RecyclerView.ViewHolder(view)

    fun submit(list: List<TorrentItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_torrent, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val v = holder.view
        val name = v.findViewById<TextView>(R.id.tvTorrentName)
        val status = v.findViewById<TextView>(R.id.tvTorrentStatus)
        val progress = v.findViewById<ProgressBar>(R.id.pbTorrent)
        val speed = v.findViewById<TextView>(R.id.tvTorrentSpeed)
        val btnToggle = v.findViewById<MaterialButton>(R.id.btnTorrentToggle)
        val btnDelete = v.findViewById<MaterialButton>(R.id.btnTorrentDelete)
        val btnPlay = v.findViewById<MaterialButton>(R.id.btnTorrentPlay)

        name.text = item.name.ifBlank { "获取中…" }
        status.text = if (item.isFinished) item.state else "${item.state} ${item.progressPercent()}%"
        progress.progress = item.progressPercent()
        speed.text = "↓${formatRate(item.downloadRate)}/s ↑${formatRate(item.uploadRate)}/s · 种子${item.numSeeds} 用户${item.numPeers}"

        val running = RUNNING_STATES.contains(item.state)
        btnToggle.text = if (running) "暂停" else "继续"
        btnToggle.setOnClickListener { onToggle(item) }
        btnDelete.setOnClickListener { onDelete(item) }
        btnPlay.isEnabled = item.hasVideo
        btnPlay.setOnClickListener { onPlay(item) }
    }

    private fun formatRate(bytes: Long): String {
        return when {
            bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    companion object {
        private val RUNNING_STATES = setOf(
            "下载中", "获取元数据", "校验中", "校验断点", "分配空间", "做种中"
        )
    }
}
