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

    enum class DualFilter { ALL, DUAL_ONLY, MAIN_ONLY }

    private var fullList: List<AppItem> = emptyList()
    private var currentQuery = ""
    private var dualFilter = DualFilter.ALL

    fun submitFullList(list: List<AppItem>) {
        fullList = list
        applyFilters()
    }

    fun filter(query: String) {
        currentQuery = query.trim().lowercase()
        applyFilters()
    }

    fun setDualFilter(f: DualFilter) {
        dualFilter = f
        applyFilters()
    }

    private fun applyFilters() {
        var result = fullList

        if (currentQuery.isNotEmpty()) {
            result = result.filter {
                it.label.lowercase().contains(currentQuery) ||
                        it.packageName.lowercase().contains(currentQuery)
            }
        }

        result = when (dualFilter) {
            DualFilter.DUAL_ONLY -> result.filter { it.isDual }
            DualFilter.MAIN_ONLY -> result.filter { !it.isDual }
            DualFilter.ALL       -> result
        }

        submitList(result)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v, onItemClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(itemView: View, private val onItemClick: (AppItem) -> Unit) :
        RecyclerView.ViewHolder(itemView) {

        private val ivIcon:     ImageView = itemView.findViewById(R.id.ivIcon)
        private val tvLabel:    TextView  = itemView.findViewById(R.id.tvLabel)
        private val tvPkg:      TextView  = itemView.findViewById(R.id.tvPkg)
        private val chipStatus: Chip      = itemView.findViewById(R.id.chipStatus)

        fun bind(item: AppItem) {
            ivIcon.setImageDrawable(item.icon)
            tvLabel.text = item.label
            tvPkg.text   = item.packageName

            chipStatus.text = when (item.workspaceCount) {
                0    -> itemView.context.getString(R.string.main_only)
                1    -> itemView.context.getString(R.string.spaces_count_one)
                else -> itemView.context.getString(R.string.spaces_count, item.workspaceCount)
            }
            // Highlight chip when the app lives in at least one workspace
            chipStatus.isChecked = item.isDual

            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) =
                a.packageName == b.packageName

            override fun areContentsTheSame(a: AppItem, b: AppItem) =
                a.packageName      == b.packageName &&
                        a.label            == b.label &&
                        a.installedUserIds.size == b.installedUserIds.size && a.installedUserIds.containsAll(b.installedUserIds)
        }
    }
}