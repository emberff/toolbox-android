package com.xvd.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ModuleAdapter(
    private val items: List<ModuleItem>,
    private val onClick: (ModuleItem) -> Unit
) : RecyclerView.Adapter<ModuleAdapter.VH>() {

    class VH(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_module, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val v = holder.view
        v.findViewById<ImageView>(R.id.ivModuleIcon).setImageResource(item.iconRes)
        v.findViewById<TextView>(R.id.tvModuleTitle).text = item.title
        v.findViewById<TextView>(R.id.tvModuleSubtitle).text = item.subtitle
        v.findViewById<MaterialCardView>(R.id.cvModule).setOnClickListener { onClick(item) }
    }
}
