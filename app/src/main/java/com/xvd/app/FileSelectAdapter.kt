package com.xvd.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileSelectAdapter(
    private val files: List<TorrentFileInfo>,
    private val selected: MutableSet<Int>
) : RecyclerView.Adapter<FileSelectAdapter.VH>() {

    class VH(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_select, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = files.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = files[position]
        val v = holder.view
        val cb = v.findViewById<CheckBox>(R.id.cbFile)
        v.findViewById<TextView>(R.id.tvFileName).text = f.name
        v.findViewById<TextView>(R.id.tvFileSize).text = formatSize(f.size)
        cb.isChecked = f.index in selected
        v.setOnClickListener {
            cb.isChecked = !cb.isChecked
            onChecked(f.index, cb.isChecked)
        }
        cb.setOnCheckedChangeListener { _, checked ->
            onChecked(f.index, checked)
        }
    }

    private fun onChecked(index: Int, checked: Boolean) {
        if (checked) selected.add(index) else selected.remove(index)
    }

    fun refreshSelection() {
        notifyDataSetChanged()
    }
}
