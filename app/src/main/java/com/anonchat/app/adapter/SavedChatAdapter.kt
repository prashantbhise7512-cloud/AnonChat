package com.anonchat.app.adapter

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.AuthActivity
import com.anonchat.app.R
import com.anonchat.app.model.SavedChat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import de.hdodenhof.circleimageview.CircleImageView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SavedChatAdapter(
    private var chats: List<SavedChat>,
    private val onClick: (SavedChat) -> Unit,
    private val onProfileClick: (SavedChat) -> Unit
) : RecyclerView.Adapter<SavedChatAdapter.ViewHolder>() {

    fun updateChats(newChats: List<SavedChat>) {
        chats = newChats
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_chat, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(chats[position])
    }

    override fun getItemCount() = chats.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: CircleImageView = itemView.findViewById(R.id.ivChatAvatar)
        private val tvChatName: TextView = itemView.findViewById(R.id.tvChatName)
        private val tvChatPreview: TextView = itemView.findViewById(R.id.tvChatPreview)
        private val tvChatDate: TextView = itemView.findViewById(R.id.tvChatDate)
        private val tvChatCount: TextView = itemView.findViewById(R.id.tvChatCount)
        private val tvUnreadBadge: TextView = itemView.findViewById(R.id.tvUnreadBadge)

        fun bind(chat: SavedChat) {
            val partnerName = chat.messages.firstOrNull { it.senderName != chat.userName }?.senderName ?: "AnonUser"
            tvChatName.text = partnerName
            val lastMsg = chat.messages.lastOrNull()
            tvChatPreview.text = if (lastMsg != null) {
                "${lastMsg.senderName}: ${lastMsg.message}"
            } else "No messages"

            // Show date of last message
            val displayTimestamp = lastMsg?.timestamp ?: chat.savedAt
            tvChatDate.text = formatDate(displayTimestamp)

            // Calculate unread messages
            val prefs = itemView.context.getSharedPreferences("anonchat_prefs", android.content.Context.MODE_PRIVATE)
            val lastReadAt = prefs.getLong("read_time_${chat.id}", 0L)
            val currentUserId = com.anonchat.app.TestSession.currentUserId(itemView.context) ?: ""
            val unreadCount = chat.messages.count { it.timestamp > lastReadAt && it.senderId != currentUserId }

            if (unreadCount > 0) {
                tvUnreadBadge.text = unreadCount.toString()
                tvUnreadBadge.visibility = View.VISIBLE
                tvChatCount.visibility = View.GONE
            } else {
                tvUnreadBadge.visibility = View.GONE
                tvChatCount.visibility = View.GONE
            }

            // Default avatar with gender-based border color
            ivAvatar.setImageResource(R.drawable.ic_default_avatar)
            when (chat.partnerGender) {
                "Female" -> ivAvatar.borderColor = android.graphics.Color.parseColor("#E91E63")
                else -> ivAvatar.borderColor = itemView.resources.getColor(R.color.primary, itemView.context.theme)
            }

            if (!chat.partnerAvatar.isNullOrEmpty()) {
                try {
                    val bytes = Base64.decode(chat.partnerAvatar, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ivAvatar.setImageBitmap(bitmap)
                } catch (_: Exception) {}
            }

            // Fetch partner's avatar from Firebase on demand if not already available locally
            val partnerUid = chat.partnerAccountId
            if (partnerUid != null && !AuthActivity.TEST_MODE) {
                FirebaseDatabase.getInstance().reference
                    .child("users").child(partnerUid).child("avatar")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val avatarData = snapshot.getValue(String::class.java) ?: return
                            try {
                                val bytes = Base64.decode(avatarData, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ivAvatar.setImageBitmap(bitmap)
                            } catch (_: Exception) {}
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
            }

            tvChatName.setOnClickListener { onProfileClick(chat) }
            ivAvatar.setOnClickListener { onProfileClick(chat) }
            itemView.setOnClickListener { onClick(chat) }
        }

        private fun formatDate(timestamp: Long): String {
            val date = Calendar.getInstance().apply { timeInMillis = timestamp }
            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

            return when {
                date.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    date.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
                date.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                    date.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
                else -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }
}
