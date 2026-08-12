package com.nathanhanapps.appdual

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip

class AppAdapter(
    private val onItemClick: (AppItem) -> Unit,
    private val onLongPress: (AppItem) -> Unit,
    private val onSelectionChanged: (Set<String>) -> Unit
) : ListAdapter<AppItem, AppAdapter.VH>(DIFF) {

    enum class DualFilter { ALL, DUAL_ONLY, MAIN_ONLY }

    private var fullList: List<AppItem> = emptyList()
    private var currentQuery = ""
    private var dualFilter = DualFilter.ALL

    var batchMode: Boolean = false
        private set
    private val selected = mutableSetOf<String>()

    fun setBatchMode(enabled: Boolean) {
        if (batchMode == enabled) return
        batchMode = enabled
        if (!enabled) selected.clear()
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selected.toSet())
    }

    fun selectedPackages(): Set<String> = selected.toSet()

    /** Replaces the current selection wholesale (used by list import). */
    fun setSelectedPackages(pkgs: Set<String>) {
        selected.clear()
        selected.addAll(pkgs)
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selected.toSet())
    }

    fun toggleSelection(packageName: String) {
        if (!selected.remove(packageName)) selected.add(packageName)
        val idx = currentList.indexOfFirst { it.packageName == packageName }
        if (idx >= 0) notifyItemChanged(idx)
        onSelectionChanged(selected.toSet())
    }

    /** Selects every app in the currently filtered/visible list. */
    fun selectAll() {
        selected.addAll(currentList.map { it.packageName })
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selected.toSet())
    }

    /** Flips selected/unselected for every app in the currently filtered/visible list. */
    fun invertSelection() {
        currentList.forEach { item ->
            if (!selected.remove(item.packageName)) selected.add(item.packageName)
        }
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selected.toSet())
    }

    /** Clears the selection without leaving batch mode. */
    fun deselectAll() {
        selected.clear()
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selected.toSet())
    }

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
        return VH(v, onItemClick, onLongPress, ::toggleSelection)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item, batchMode, selected.contains(item.packageName))
    }

    class VH(
        itemView: View,
        private val onItemClick: (AppItem) -> Unit,
        private val onLongPress: (AppItem) -> Unit,
        private val onToggle: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivIcon:     ImageView        = itemView.findViewById(R.id.ivIcon)
        private val tvLabel:    TextView         = itemView.findViewById(R.id.tvLabel)
        private val tvPkg:      TextView         = itemView.findViewById(R.id.tvPkg)
        private val chipStatus: Chip             = itemView.findViewById(R.id.chipStatus)
        private val cbSelect:   MaterialCheckBox = itemView.findViewById(R.id.cbSelect)

        fun bind(item: AppItem, batchMode: Boolean, isSelected: Boolean) {
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
            chipStatus.isVisible = !batchMode

            cbSelect.isVisible = batchMode
            cbSelect.isChecked = isSelected

            itemView.setOnClickListener {
                if (batchMode) onToggle(item.packageName) else onItemClick(item)
            }
            itemView.setOnLongClickListener {
                onLongPress(item)
                true
            }
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
