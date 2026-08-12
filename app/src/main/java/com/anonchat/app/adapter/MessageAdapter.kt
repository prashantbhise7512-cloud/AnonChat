package com.anonchat.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.R
import com.anonchat.app.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val currentUserId: String
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_MINE = 1
        private const val VIEW_TYPE_OTHER = 2
        private const val VIEW_TYPE_SYSTEM = 3
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.senderId == "system" -> VIEW_TYPE_SYSTEM
            item.senderId == currentUserId -> VIEW_TYPE_MINE
            else -> VIEW_TYPE_OTHER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_MINE -> MineViewHolder(inflater.inflate(R.layout.item_message_mine, parent, false))
            VIEW_TYPE_SYSTEM -> SystemViewHolder(inflater.inflate(R.layout.item_message_system, parent, false))
            else -> OtherViewHolder(inflater.inflate(R.layout.item_message_other, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is MineViewHolder -> holder.bind(message)
            is OtherViewHolder -> holder.bind(message)
            is SystemViewHolder -> holder.bind(message)
        }
    }

    class MineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvTick: TextView = itemView.findViewById(R.id.tvTick)

        fun bind(message: ChatMessage) {
            tvMessage.text = message.message
            tvTimestamp.text = formatTime(message.timestamp)
            when (message.status) {
                "read" -> {
                    tvTick.text = "✓✓"
                    tvTick.setTextColor(android.graphics.Color.parseColor("#34B7F1"))
                }
                "delivered" -> {
                    tvTick.text = "✓✓"
                    tvTick.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
                }
                else -> {
                    tvTick.text = "✓"
                    tvTick.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
                }
            }
        }
    }

    class OtherViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(message: ChatMessage) {
            // Hide sender name — it's shown in the chat header already
            tvSender.visibility = android.view.View.GONE
            tvMessage.text = message.message
            tvTimestamp.text = formatTime(message.timestamp)
        }
    }

    class SystemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSystemMessage: TextView = itemView.findViewById(R.id.tvSystemMessage)

        fun bind(message: ChatMessage) {
            tvSystemMessage.text = message.message
            if (message.status == "system_green") {
                tvSystemMessage.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                tvSystemMessage.setTextColor(android.graphics.Color.parseColor("#666666"))
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
