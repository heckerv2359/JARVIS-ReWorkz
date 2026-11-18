package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.model.ChatStatus
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.ItemNairaChatMessageBinding

class JarvisChatAdapter(
    private val onEditUserMessage: ((ChatTurn) -> Unit)? = null,
    private val onRegenerate: ((ChatTurn) -> Unit)? = null,
    private val onSpeak: ((ChatTurn) -> Unit)? = null
) : ListAdapter<ChatTurn, JarvisChatAdapter.MessageViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        private const val VOICE_USER_PLACEHOLDER = "(Audio query)"
        private const val VOICE_REPLY_PLACEHOLDER = "(Audio response)"
        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemNairaChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemNairaChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val defaultAssistantColor = binding.tvAssistantMessage.currentTextColor

        init {
            binding.tvAssistantMessage.movementMethod = LinkMovementMethod.getInstance()
            binding.tvAssistantMessage.setLinkTextColor(0xFF6EA8FE.toInt())
        }

        fun bind(turn: ChatTurn) {
            val context = binding.root.context
            val attachments = turn.attachments.orEmpty()

            // User bubble
            val userText = turn.userTranscript
            if (userText.isNotBlank() || attachments.isNotEmpty()) {
                binding.layoutUserContainer.visibility = View.VISIBLE
                if (userText.isNotBlank()) {
                    binding.tvUserMessage.visibility = View.VISIBLE
                    binding.tvUserMessage.text = userText
                } else {
                    binding.tvUserMessage.visibility = View.GONE
                }
                bindAttachments(context, attachments)

                binding.btnUserCopy.visibility = if (userText.isNotBlank()) View.VISIBLE else View.GONE
                binding.btnUserCopy.setOnClickListener {
                    copyToClipboard(context, "User message", userText)
                }

                val editable = userText != VOICE_USER_PLACEHOLDER
                binding.btnUserEdit.visibility = if (editable) View.VISIBLE else View.GONE
                binding.btnUserEdit.setOnClickListener {
                    onEditUserMessage?.invoke(turn)
                }
            } else {
                binding.layoutUserContainer.visibility = View.GONE
            }

            // Assistant bubble
            val streaming = turn.status == ChatStatus.STREAMING
            val failed = turn.status == ChatStatus.ERROR
            val stopped = turn.status == ChatStatus.STOPPED
            val reply = turn.jarvisResponse
            if (reply.isNotBlank() || streaming || stopped) {
                binding.layoutAssistantContainer.visibility = View.VISIBLE

                val shown = SpannableStringBuilder()
                if (streaming && reply.isBlank()) {
                    shown.append("Thinking...")
                } else if (failed) {
                    shown.append(reply)
                } else {
                    shown.append(MarkdownRenderer.render(reply) { code ->
                        copyToClipboard(context, "Code", code)
                    })
                }
                if (streaming && reply.isNotBlank()) shown.append(" \u258D")
                if (stopped) {
                    val start = shown.length
                    shown.append(if (reply.isBlank()) "Stopped" else "\n\nStopped")
                    shown.setSpan(StyleSpan(Typeface.ITALIC), start, shown.length, 0)
                }
                binding.tvAssistantMessage.text = shown
                binding.tvAssistantMessage.setTextColor(if (failed) 0xFFFF6B6B.toInt() else defaultAssistantColor)

                val actionable = !streaming && reply.isNotBlank()
                binding.btnAssistantCopy.visibility = if (actionable) View.VISIBLE else View.GONE
                binding.btnAssistantPlay.visibility = if (actionable && !failed) View.VISIBLE else View.GONE
                val canRegen = !streaming && !failed && reply != VOICE_REPLY_PLACEHOLDER &&
                    userText != VOICE_USER_PLACEHOLDER
                binding.btnAssistantRegen.visibility = if (canRegen) View.VISIBLE else View.GONE
                binding.btnAssistantRetry.visibility = if (failed || stopped) View.VISIBLE else View.GONE

                binding.btnAssistantCopy.setOnClickListener {
                    copyToClipboard(context, "Jarvis response", reply)
                }
                binding.btnAssistantPlay.setOnClickListener {
                    onSpeak?.invoke(turn)
                }
                binding.btnAssistantRegen.setOnClickListener {
                    onRegenerate?.invoke(turn)
                }
                binding.btnAssistantRetry.setOnClickListener {
                    onRegenerate?.invoke(turn)
                }
            } else {
                binding.layoutAssistantContainer.visibility = View.GONE
            }
        }

        private fun bindAttachments(context: Context, paths: List<String>) {
            val box = binding.layoutAttachments
            box.removeAllViews()
            if (paths.isEmpty()) {
                box.visibility = View.GONE
                return
            }
            box.visibility = View.VISIBLE
            val density = context.resources.displayMetrics.density
            val size = (88 * density).toInt()
            val gap = (6 * density).toInt()
            for (path in paths) {
                if (ChatMedia.isImage(path)) {
                    val iv = ImageView(context)
                    val lp = LinearLayout.LayoutParams(size, size)
                    lp.marginStart = gap
                    iv.layoutParams = lp
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    val bg = GradientDrawable()
                    bg.cornerRadius = 14 * density
                    bg.setColor(0x22FFFFFF)
                    iv.background = bg
                    iv.clipToOutline = true
                    iv.setImageBitmap(ChatMedia.thumbnail(path, 320))
                    box.addView(iv)
                } else {
                    val chip = TextView(context)
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.marginStart = gap
                    lp.gravity = Gravity.CENTER_VERTICAL
                    chip.layoutParams = lp
                    chip.text = ChatMedia.displayName(path)
                    chip.textSize = 13f
                    chip.maxLines = 1
                    chip.maxWidth = (180 * density).toInt()
                    chip.setTextColor(defaultAssistantColor)
                    val pad = (10 * density).toInt()
                    chip.setPadding(pad, pad / 2, pad, pad / 2)
                    val bg = GradientDrawable()
                    bg.cornerRadius = 14 * density
                    bg.setColor(0x22808080)
                    chip.background = bg
                    box.addView(chip)
                }
            }
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
}
