package com.jarvis.assistant.ui.chat

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ConversationInfo
import com.jarvis.assistant.data.model.GeminiConstants

/** Small dialogs for the chat screen, built in code so they follow the app theme. */
object ChatDialogs {

    private val PERSONAS = listOf(
        GeminiConstants.PERSONALITY_ASSISTANT,
        GeminiConstants.PERSONALITY_TRINA,
        GeminiConstants.PERSONALITY_PROFESSIONAL,
        GeminiConstants.PERSONALITY_FOCUS,
        GeminiConstants.PERSONALITY_COMPANION,
        GeminiConstants.PERSONALITY_GIRLFRIEND,
        GeminiConstants.PERSONALITY_BOYFRIEND
    )

    fun personaLabel(personality: String): String = personality.removeSuffix(" Mode").ifBlank { "Jarvis" }

    fun showPersonaPicker(context: Context, onChanged: () -> Unit) {
        val prefs = JarvisApp.instance.preferences
        val current = PERSONAS.indexOf(prefs.personality).coerceAtLeast(0)
        val labels = PERSONAS.map { personaLabel(it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Persona")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.personality = PERSONAS[which]
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showModePicker(context: Context, onChanged: () -> Unit) {
        val labels = arrayOf("Chat", "Code", "Search the web", "Think carefully")
        AlertDialog.Builder(context)
            .setTitle("Mode")
            .setSingleChoiceItems(labels, ChatPrefs.mode.coerceIn(0, 3)) { dialog, which ->
                ChatPrefs.mode = which
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showSettings(context: Context, onChanged: () -> Unit) {
        val builder = AlertDialog.Builder(context)
        val ctx = builder.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(8), dp(20), dp(8))

        fun label(text: String): TextView {
            val t = TextView(ctx)
            t.text = text
            t.textSize = 13f
            t.alpha = 0.7f
            t.setPadding(0, dp(14), 0, dp(4))
            return t
        }

        fun textField(hint: String, value: String, multiline: Boolean): EditText {
            val e = EditText(ctx)
            e.hint = hint
            e.setText(value)
            e.textSize = 14f
            if (multiline) {
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                e.minLines = 2
                e.maxLines = 5
            } else {
                e.inputType = InputType.TYPE_CLASS_TEXT
                e.maxLines = 1
            }
            return e
        }

        fun switch(text: String, on: Boolean): SwitchCompat {
            val s = SwitchCompat(ctx)
            s.text = text
            s.isChecked = on
            s.setPadding(0, dp(8), 0, dp(8))
            return s
        }

        root.addView(label("Chat model (leave empty for automatic)"))
        val modelField = textField("e.g. gemini-flash-latest", ChatPrefs.model, false)
        root.addView(modelField)

        root.addView(label("Reply length"))
        val lengthSpinner = Spinner(ctx)
        lengthSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("Short", "Normal", "Detailed"))
        lengthSpinner.setSelection(ChatPrefs.length.coerceIn(0, 2))
        root.addView(lengthSpinner)

        val tempLabel = label("")
        fun tempText(progress: Int) = "Creativity: " + String.format("%.1f", progress / 10f) + "  (lower is more precise)"
        val tempBar = SeekBar(ctx)
        tempBar.max = 20
        tempBar.progress = (ChatPrefs.temperature * 10f).toInt().coerceIn(0, 20)
        tempLabel.text = tempText(tempBar.progress)
        tempBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tempLabel.text = tempText(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(tempLabel)
        root.addView(tempBar)

        val searchSwitch = switch("Google Search for every reply", ChatPrefs.searchOn)
        val speakSwitch = switch("Read replies aloud", ChatPrefs.autoSpeak)
        val offlineSwitch = switch("Offline helper when there is no internet", ChatPrefs.offlineFallback)
        root.addView(searchSwitch)
        root.addView(speakSwitch)
        root.addView(offlineSwitch)

        root.addView(label("Always reply in this language (optional)"))
        val langField = textField("e.g. English, Hindi, Filipino", ChatPrefs.languageOverride, false)
        root.addView(langField)

        root.addView(label("Custom instructions (optional)"))
        val customField = textField("e.g. Keep answers short. Explain like I am a beginner.", ChatPrefs.customInstructions, true)
        root.addView(customField)

        val scroll = ScrollView(ctx)
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        builder.setTitle("Chat settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                ChatPrefs.model = modelField.text.toString()
                ChatPrefs.length = lengthSpinner.selectedItemPosition
                ChatPrefs.temperature = tempBar.progress / 10f
                ChatPrefs.searchOn = searchSwitch.isChecked
                ChatPrefs.autoSpeak = speakSwitch.isChecked
                ChatPrefs.offlineFallback = offlineSwitch.isChecked
                ChatPrefs.languageOverride = langField.text.toString()
                ChatPrefs.customInstructions = customField.text.toString()
                if (!ChatPrefs.autoSpeak) ChatSpeaker.stop()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Lists every conversation with search, rename, pin, export and delete. */
    fun showConversations(context: Context, onChanged: () -> Unit) {
        val repo = JarvisApp.instance.chatRepository
        val builder = AlertDialog.Builder(context)
        val ctx = builder.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(8), dp(16), 0)

        val search = EditText(ctx)
        search.hint = "Search chats"
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.maxLines = 1
        root.addView(search)

        val list = ListView(ctx)
        var infos: List<ConversationInfo> = emptyList()
        list.adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_2, android.R.id.text1, ArrayList()) {
            override fun getCount(): Int = infos.size
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: android.view.LayoutInflater.from(ctx).inflate(android.R.layout.simple_list_item_2, parent, false)
                val info = infos[position]
                val active = info.id == repo.activeConversationId()
                v.findViewById<TextView>(android.R.id.text1).text =
                    (if (info.pinned) "\uD83D\uDCCC " else "") + info.title + (if (active) "  (open)" else "")
                v.findViewById<TextView>(android.R.id.text2).text =
                    info.turnCount.toString() + " messages  " + info.preview
                return v
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

        val dialog = builder.setTitle("Chats")
            .setView(root)
            .setNeutralButton("New chat") { _, _ ->
                repo.newConversation()
                onChanged()
            }
            .setNegativeButton("Close", null)
            .create()

        fun refresh() {
            infos = repo.listConversations(search.text.toString())
            (list.adapter as ArrayAdapter<*>).notifyDataSetChanged()
        }
        refresh()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val info = infos.getOrNull(position) ?: return@setOnItemClickListener
            repo.switchTo(info.id)
            onChanged()
            dialog.dismiss()
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            val info = infos.getOrNull(position) ?: return@setOnItemLongClickListener true
            val items = arrayOf("Rename", if (info.pinned) "Unpin" else "Pin to top", "Export as text", "Delete")
            AlertDialog.Builder(ctx)
                .setTitle(info.title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> promptRename(ctx, info) { refresh(); onChanged() }
                        1 -> { repo.togglePin(info.id); refresh() }
                        2 -> export(ctx, info.id)
                        3 -> AlertDialog.Builder(ctx)
                            .setTitle("Delete chat")
                            .setMessage("Delete \"" + info.title + "\" and its attachments?")
                            .setPositiveButton("Delete") { _, _ ->
                                repo.delete(info.id)
                                refresh()
                                onChanged()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        else -> {}
                    }
                }
                .show()
            true
        }

        dialog.show()
    }

    private fun promptRename(ctx: Context, info: ConversationInfo, done: () -> Unit) {
        val input = EditText(ctx)
        input.setText(info.title)
        input.setSelection(input.text.length)
        input.inputType = InputType.TYPE_CLASS_TEXT
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(ctx)
            .setTitle("Rename chat")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                JarvisApp.instance.chatRepository.rename(info.id, input.text.toString())
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun export(ctx: Context, id: String) {
        val text = JarvisApp.instance.chatRepository.exportText(id)
        if (text.isBlank()) {
            Toast.makeText(ctx, "This chat is empty.", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, text)
        try {
            ctx.startActivity(Intent.createChooser(send, "Export chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "No app can receive the export.", Toast.LENGTH_SHORT).show()
        }
    }
}
