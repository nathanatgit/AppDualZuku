package com.nathanhanapps.appdualzuku

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip

class AppAdapter(
    private val onItemClick: (AppItem) -> Unit
) : ListAdapter<AppItem, AppAdapter.VH>(DIFF) {

    private var fullList: List<AppItem> = emptyList()

    fun submitFullList(list: List<AppItem>) {
        fullList = list
        submitList(list)
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            submitList(fullList)
            return
        }
        val filtered = fullList.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        submitList(filtered)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v, onItemClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(itemView: View, private val onItemClick: (AppItem) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val tvLabel: TextView = itemView.findViewById(R.id.tvLabel)
        private val tvPkg: TextView = itemView.findViewById(R.id.tvPkg)
        private val chipStatus: Chip = itemView.findViewById(R.id.chipStatus)

        fun bind(item: AppItem) {
            ivIcon.setImageDrawable(item.icon)
            tvLabel.text = item.label
            tvPkg.text = item.packageName

            // For now we only show "Main only" (dual status comes later)
            chipStatus.text = if (item.isDual) "Dual" else "Main only"

            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
                // icon Drawable compare isn’t stable; compare important fields
                return oldItem.packageName == newItem.packageName &&
                        oldItem.label == newItem.label &&
                        oldItem.isDual == newItem.isDual
            }
        }
    }
}
