package com.example.kakaotalkautobot

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class RoomTargetAdapter(
    rooms: List<AppSettings.RoomTarget>,
    private val context: Context,
    private val secondaryActionLabel: String,
    private val onRoomClick: (AppSettings.RoomTarget) -> Unit,
    private val onSecondaryActionClick: (AppSettings.RoomTarget) -> Unit,
    private val onDeleteClick: (AppSettings.RoomTarget) -> Unit,
    private val onToggleChanged: (AppSettings.RoomTarget, Boolean) -> Unit
) : RecyclerView.Adapter<RoomTargetAdapter.RoomTargetViewHolder>() {

    // Room settings and memory are saved independently. Capture both so a memory-only
    // edit invalidates its row, and never retain the caller's mutable list.
    private data class Row(val room: AppSettings.RoomTarget, val metadata: String)
    private var rows: List<Row> = snapshot(rooms)

    class RoomTargetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.bot_name)
        val metaText: TextView = itemView.findViewById(R.id.bot_meta)
        val enableSwitch: SwitchMaterial = itemView.findViewById(R.id.bot_switch)
        val secondaryButton: MaterialButton = itemView.findViewById(R.id.bot_secondary_action)
        val deleteButton: MaterialButton = itemView.findViewById(R.id.bot_delete)
        val root: View = itemView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomTargetViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.bot_item, parent, false)
        return RoomTargetViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoomTargetViewHolder, position: Int) {
        val row = rows[position]
        val room = row.room
        holder.nameText.text = room.name
        holder.metaText.text = row.metadata
        holder.secondaryButton.text = secondaryActionLabel

        holder.enableSwitch.setOnCheckedChangeListener(null)
        holder.enableSwitch.isChecked = room.isEnabled
        holder.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
            onToggleChanged(room, isChecked)
        }

        holder.root.setOnClickListener { onRoomClick(room) }
        holder.secondaryButton.setOnClickListener { onSecondaryActionClick(room) }
        holder.deleteButton.setOnClickListener { onDeleteClick(room) }
    }

    override fun getItemCount(): Int = rows.size

    fun replaceItems(newItems: List<AppSettings.RoomTarget>) {
        val oldRows = rows
        val newRows = snapshot(newItems)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldRows.size
            override fun getNewListSize(): Int = newRows.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldRows[oldItemPosition].room.configName == newRows[newItemPosition].room.configName

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldRows[oldItemPosition] == newRows[newItemPosition]
        })
        rows = newRows
        diff.dispatchUpdatesTo(this)
    }

    private fun snapshot(rooms: List<AppSettings.RoomTarget>): List<Row> =
        rooms.map { Row(it, buildMetaText(context, it)) }

    private fun buildMetaText(context: android.content.Context, room: AppSettings.RoomTarget): String {
        val memoryText = (BotManager.getConfig(context, room.configName)
            ?: BotManager.getConfigByRoomPattern(context, room.name))?.roomMemory
            ?: AppSettings.getRoomMemory(context, room.name)
        val memoryLabel = if (memoryText.isBlank()) {
            context.getString(R.string.room_memory_empty)
        } else {
            context.getString(R.string.room_memory_length, memoryText.length)
        }
        val statusLabel = context.getString(
            if (room.isEnabled) R.string.room_status_enabled else R.string.room_status_disabled
        )
        return context.getString(R.string.room_target_metadata, memoryLabel, statusLabel)
    }
}
