package com.nathanhanapps.appdual

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

class WorkspaceAdapter(
    private val onStart:  (WorkspaceInfo) -> Unit,
    private val onStop:   (WorkspaceInfo) -> Unit,
    private val onRemove: (WorkspaceInfo) -> Unit
) : ListAdapter<WorkspaceInfo, WorkspaceAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workspace, parent, false)
        return VH(v, onStart, onStop, onRemove)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        itemView: View,
        private val onStart:  (WorkspaceInfo) -> Unit,
        private val onStop:   (WorkspaceInfo) -> Unit,
        private val onRemove: (WorkspaceInfo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvName:       TextView       = itemView.findViewById(R.id.tvWsName)
        private val tvMeta:       TextView       = itemView.findViewById(R.id.tvWsMeta)
        private val chipRunning:  Chip           = itemView.findViewById(R.id.chipWsRunning)
        private val btnStartStop: MaterialButton = itemView.findViewById(R.id.btnWsStartStop)
        private val btnRemove:    MaterialButton = itemView.findViewById(R.id.btnWsRemove)

        fun bind(ws: WorkspaceInfo) {
            val ctx = itemView.context
            tvName.text = ws.displayName
            tvMeta.text = ctx.getString(R.string.workspace_meta_format, ws.userId, ws.flags)

            chipRunning.text      = if (ws.isRunning) ctx.getString(R.string.running) else ctx.getString(R.string.stopped)
            chipRunning.isChecked = ws.isRunning

            btnStartStop.text = if (ws.isRunning) ctx.getString(R.string.stop_button) else ctx.getString(R.string.start_button)
            btnStartStop.setOnClickListener { if (ws.isRunning) onStop(ws) else onStart(ws) }
            btnRemove.setOnClickListener { onRemove(ws) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WorkspaceInfo>() {
            override fun areItemsTheSame(a: WorkspaceInfo, b: WorkspaceInfo) = a.userId == b.userId
            override fun areContentsTheSame(a: WorkspaceInfo, b: WorkspaceInfo) = a == b
        }
    }
}
