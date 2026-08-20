package com.anonchat.app.adapter

import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.R
import com.anonchat.app.model.ChatMessage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val currentUserId: String,
    var onMessageOptionsListener: ((ChatMessage) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_MINE = 1
        private const val VIEW_TYPE_OTHER = 2
        private const val VIEW_TYPE_SYSTEM = 3
        private const val VIEW_TYPE_VOICE_MINE = 4
        private const val VIEW_TYPE_VOICE_OTHER = 5
        private const val VIEW_TYPE_PHOTO_MINE = 6
        private const val VIEW_TYPE_PHOTO_OTHER = 7

        private var activePlayer: MediaPlayer? = null
        private var activePlayingId: String? = null
        private var activeUpdateRunnable: Runnable? = null
        private val handler = Handler(Looper.getMainLooper())
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.senderId == "system" -> VIEW_TYPE_SYSTEM
            item.type == "voice" && item.senderId == currentUserId -> VIEW_TYPE_VOICE_MINE
            item.type == "voice" -> VIEW_TYPE_VOICE_OTHER
            item.type == "photo" && item.senderId == currentUserId -> VIEW_TYPE_PHOTO_MINE
            item.type == "photo" -> VIEW_TYPE_PHOTO_OTHER
            item.senderId == currentUserId -> VIEW_TYPE_MINE
            else -> VIEW_TYPE_OTHER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_MINE -> MineViewHolder(inflater.inflate(R.layout.item_message_mine, parent, false))
            VIEW_TYPE_OTHER -> OtherViewHolder(inflater.inflate(R.layout.item_message_other, parent, false))
            VIEW_TYPE_SYSTEM -> SystemViewHolder(inflater.inflate(R.layout.item_message_system, parent, false))
            VIEW_TYPE_VOICE_MINE -> VoiceMineViewHolder(inflater.inflate(R.layout.item_message_voice_mine, parent, false))
            VIEW_TYPE_VOICE_OTHER -> VoiceOtherViewHolder(inflater.inflate(R.layout.item_message_voice_other, parent, false))
            VIEW_TYPE_PHOTO_MINE -> PhotoMineViewHolder(inflater.inflate(R.layout.item_message_photo_mine, parent, false))
            else -> PhotoOtherViewHolder(inflater.inflate(R.layout.item_message_photo_other, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)

        if (holder !is SystemViewHolder) {
            holder.itemView.setOnLongClickListener {
                onMessageOptionsListener?.invoke(message)
                true
            }
        }

        when (holder) {
            is MineViewHolder -> holder.bind(message)
            is OtherViewHolder -> holder.bind(message)
            is SystemViewHolder -> holder.bind(message)
            is VoiceMineViewHolder -> holder.bind(message)
            is VoiceOtherViewHolder -> holder.bind(message)
            is PhotoMineViewHolder -> holder.bind(message)
            is PhotoOtherViewHolder -> holder.bind(message)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        stopActivePlayer()
    }

    private fun stopActivePlayer() {
        activeUpdateRunnable?.let { handler.removeCallbacks(it) }
        activeUpdateRunnable = null
        try {
            if (activePlayer?.isPlaying == true) {
                activePlayer?.stop()
            }
            activePlayer?.release()
        } catch (_: Exception) {}
        activePlayer = null
        activePlayingId = null
    }

    class MineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvTick: TextView = itemView.findViewById(R.id.tvTick)

        fun bind(message: ChatMessage) {
            if (message.isDeleted) {
                itemView.findViewById<View>(R.id.layoutQuoted)?.visibility = View.GONE
                tvMessage.text = "🚫 This message was deleted"
                tvMessage.setTypeface(null, Typeface.ITALIC)
                tvMessage.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
                tvTimestamp.text = formatTime(message.timestamp)
            } else {
                bindQuotedView(itemView, message)
                tvMessage.text = message.message
                tvMessage.setTypeface(null, Typeface.NORMAL)
                tvMessage.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                tvTimestamp.text = formatTimestamp(message.timestamp, message.isEdited)
            }
            bindTicks(tvTick, message.status)
        }
    }

    class OtherViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(message: ChatMessage) {
            tvSender.visibility = View.GONE
            if (message.isDeleted) {
                itemView.findViewById<View>(R.id.layoutQuoted)?.visibility = View.GONE
                tvMessage.text = "🚫 This message was deleted"
                tvMessage.setTypeface(null, Typeface.ITALIC)
                tvMessage.setTextColor(android.graphics.Color.parseColor("#888888"))
                tvTimestamp.text = formatTime(message.timestamp)
            } else {
                bindQuotedView(itemView, message)
                tvMessage.text = message.message
                tvMessage.setTypeface(null, Typeface.NORMAL)
                tvMessage.setTextColor(android.graphics.Color.parseColor("#212121"))
                tvTimestamp.text = formatTimestamp(message.timestamp, message.isEdited)
            }
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

    inner class VoiceMineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnPlayPause: ImageView = itemView.findViewById(R.id.btnPlayPause)
        private val seekBarAudio: SeekBar = itemView.findViewById(R.id.seekBarAudio)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvTick: TextView = itemView.findViewById(R.id.tvTick)

        fun bind(message: ChatMessage) {
            if (message.isDeleted) {
                itemView.findViewById<View>(R.id.layoutQuoted)?.visibility = View.GONE
                tvDuration.text = "🚫 Message deleted"
                btnPlayPause.isEnabled = false
                seekBarAudio.isEnabled = false
                tvTimestamp.text = formatTime(message.timestamp)
            } else {
                bindQuotedView(itemView, message)
                btnPlayPause.isEnabled = true
                seekBarAudio.isEnabled = true
                tvTimestamp.text = formatTimestamp(message.timestamp, message.isEdited)
                bindVoicePlayer(message, btnPlayPause, seekBarAudio, tvDuration)
            }
            bindTicks(tvTick, message.status)
        }
    }

    inner class VoiceOtherViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnPlayPause: ImageView = itemView.findViewById(R.id.btnPlayPause)
        private val seekBarAudio: SeekBar = itemView.findViewById(R.id.seekBarAudio)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(message: ChatMessage) {
            if (message.isDeleted) {
                itemView.findViewById<View>(R.id.layoutQuoted)?.visibility = View.GONE
                tvDuration.text = "🚫 Message deleted"
                btnPlayPause.isEnabled = false
                seekBarAudio.isEnabled = false
                tvTimestamp.text = formatTime(message.timestamp)
            } else {
                bindQuotedView(itemView, message)
                btnPlayPause.isEnabled = true
                seekBarAudio.isEnabled = true
                tvTimestamp.text = formatTimestamp(message.timestamp, message.isEdited)
                bindVoicePlayer(message, btnPlayPause, seekBarAudio, tvDuration)
            }
        }
    }

    private fun bindVoicePlayer(
        message: ChatMessage,
        btnPlayPause: ImageView,
        seekBarAudio: SeekBar,
        tvDuration: TextView
    ) {
        val totalSecs = (message.durationMs / 1000).toInt()
        tvDuration.text = formatDuration(totalSecs)
        seekBarAudio.max = if (message.durationMs > 0) message.durationMs.toInt() else 100

        val isThisPlaying = activePlayingId == message.id && activePlayer?.isPlaying == true
        btnPlayPause.setImageResource(if (isThisPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        if (!isThisPlaying) {
            seekBarAudio.progress = 0
        }

        btnPlayPause.setOnClickListener {
            if (activePlayingId == message.id && activePlayer != null) {
                if (activePlayer?.isPlaying == true) {
                    activePlayer?.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play)
                } else {
                    activePlayer?.start()
                    btnPlayPause.setImageResource(R.drawable.ic_pause)
                    startProgressTracker(message.id, seekBarAudio, tvDuration, btnPlayPause)
                }
            } else {
                startAudioPlayback(message, btnPlayPause, seekBarAudio, tvDuration)
            }
        }

        seekBarAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && activePlayingId == message.id && activePlayer != null) {
                    activePlayer?.seekTo(progress)
                    tvDuration.text = formatDuration(progress / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun startAudioPlayback(
        message: ChatMessage,
        btnPlayPause: ImageView,
        seekBarAudio: SeekBar,
        tvDuration: TextView
    ) {
        stopActivePlayer()

        val audioBase64 = message.audioData ?: return
        val context = btnPlayPause.context
        val tempFile = File(context.cacheDir, "voice_${message.id}.m4a")

        try {
            if (!tempFile.exists() || tempFile.length() == 0L) {
                val bytes = Base64.decode(audioBase64, Base64.DEFAULT)
                FileOutputStream(tempFile).use { it.write(bytes) }
            }

            val player = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
            }

            activePlayer = player
            activePlayingId = message.id
            btnPlayPause.setImageResource(R.drawable.ic_pause)

            seekBarAudio.max = player.duration
            startProgressTracker(message.id, seekBarAudio, tvDuration, btnPlayPause)

            player.setOnCompletionListener {
                btnPlayPause.setImageResource(R.drawable.ic_play)
                seekBarAudio.progress = 0
                tvDuration.text = formatDuration((message.durationMs / 1000).toInt())
                stopActivePlayer()
                notifyDataSetChanged()
            }
        } catch (_: Exception) {
            btnPlayPause.setImageResource(R.drawable.ic_play)
            stopActivePlayer()
        }
    }

    private fun startProgressTracker(
        messageId: String,
        seekBarAudio: SeekBar,
        tvDuration: TextView,
        btnPlayPause: ImageView
    ) {
        activeUpdateRunnable?.let { handler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                val player = activePlayer
                if (activePlayingId == messageId && player != null && player.isPlaying) {
                    val currentPos = player.currentPosition
                    seekBarAudio.progress = currentPos
                    tvDuration.text = formatDuration(currentPos / 1000)
                    handler.postDelayed(this, 100)
                }
            }
        }
        activeUpdateRunnable = runnable
        handler.post(runnable)
    }

    inner class PhotoMineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPhotoMsg: ImageView = itemView.findViewById(R.id.ivPhotoMsg)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val ivStatus: ImageView = itemView.findViewById(R.id.ivStatus)

        fun bind(message: ChatMessage) {
            bindQuotedView(itemView, message)
            tvTimestamp.text = formatTimestamp(message.timestamp, message.isEdited)
            bindTicks(ivStatus, message.status)

            val base64 = message.imageData
            if (!base64.isNullOrEmpty()) {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ivPhotoMsg.setImageBitmap(bitmap)
                } catch (_: Exception) {
                    ivPhotoMsg.setImageResource(R.drawable.ic_default_avatar)
                }
            } else {
                ivPhotoMsg.setImageResource(R.drawable.ic_default_avatar)
            }

            ivPhotoMsg.setOnClickListener {
                if (!base64.isNullOrEmpty()) {
                    val context = itemView.context
                    val intent = android.content.Intent(context, com.anonchat.app.PhotoViewActivity::class.java).apply {
                        putExtra(com.anonchat.app.PhotoViewActivity.EXTRA_IMAGE_BASE64, base64)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    inner class PhotoOtherViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPhotoMsg: ImageView = itemView.findViewById(R.id.ivPhotoMsg)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(message: ChatMessage) {
            bindQuotedView(itemView, message)
            tvTimestamp.text = formatTimestamp(message.timestamp, message.isEdited)

            val base64 = message.imageData
            if (!base64.isNullOrEmpty()) {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ivPhotoMsg.setImageBitmap(bitmap)
                } catch (_: Exception) {
                    ivPhotoMsg.setImageResource(R.drawable.ic_default_avatar)
                }
            } else {
                ivPhotoMsg.setImageResource(R.drawable.ic_default_avatar)
            }

            ivPhotoMsg.setOnClickListener {
                if (!base64.isNullOrEmpty()) {
                    val context = itemView.context
                    val intent = android.content.Intent(context, com.anonchat.app.PhotoViewActivity::class.java).apply {
                        putExtra(com.anonchat.app.PhotoViewActivity.EXTRA_IMAGE_BASE64, base64)
                    }
                    context.startActivity(intent)
                }
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

private fun bindQuotedView(itemView: View, message: ChatMessage) {
    val layoutQuoted = itemView.findViewById<View>(R.id.layoutQuoted) ?: return
    val tvQuotedSender = itemView.findViewById<TextView>(R.id.tvQuotedSender) ?: return
    val tvQuotedText = itemView.findViewById<TextView>(R.id.tvQuotedText) ?: return

    if (!message.replyToText.isNullOrEmpty() && !message.isDeleted) {
        layoutQuoted.visibility = View.VISIBLE
        tvQuotedSender.text = message.replyToSender ?: "User"
        tvQuotedText.text = message.replyToText
    } else {
        layoutQuoted.visibility = View.GONE
    }
}

private fun bindTicks(view: View, status: String) {
    if (view is TextView) {
        when (status) {
            "read" -> {
                view.text = "✓✓"
                view.setTextColor(android.graphics.Color.parseColor("#34B7F1"))
            }
            "delivered" -> {
                view.text = "✓✓"
                view.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
            }
            else -> {
                view.text = "✓"
                view.setTextColor(android.graphics.Color.parseColor("#99FFFFFF"))
            }
        }
    } else if (view is ImageView) {
        when (status) {
            "read" -> {
                view.setImageResource(R.drawable.ic_check_sent)
                view.setColorFilter(android.graphics.Color.parseColor("#34B7F1"))
            }
            else -> {
                view.setImageResource(R.drawable.ic_check_sent)
                view.setColorFilter(android.graphics.Color.parseColor("#99FFFFFF"))
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long, isEdited: Boolean): String {
    val timeStr = formatTime(timestamp)
    return if (isEdited) "$timeStr • Edited" else timeStr
}

private fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", m, s)
}
