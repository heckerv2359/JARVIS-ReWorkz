package com.jarvis.assistant.ui.chat

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

    fun showSettings(context: Context, onBackup: () -> Unit, onRestore: () -> Unit, onChanged: () -> Unit) {
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

        root.addView(label("Backup"))
        val backupHint = TextView(ctx)
        backupHint.text = "Uninstalling the app deletes its data. Save a backup file to Downloads or Google Drive, then restore it after you reinstall. It holds your chats and API key, protected by a passphrase you choose."
        backupHint.textSize = 12f
        backupHint.alpha = 0.7f
        root.addView(backupHint)
        val backupBtn = Button(ctx)
        backupBtn.text = "Back up chats and API key"
        val restoreBtn = Button(ctx)
        restoreBtn.text = "Restore from backup"
        root.addView(backupBtn)
        root.addView(restoreBtn)

        val scroll = ScrollView(ctx)
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        var dlg: AlertDialog? = null
        backupBtn.setOnClickListener {
            dlg?.dismiss()
            onBackup()
        }
        restoreBtn.setOnClickListener {
            dlg?.dismiss()
            onRestore()
        }

        dlg = builder.setTitle("Chat settings")
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

    fun promptRename(ctx: Context, info: ConversationInfo, done: () -> Unit) {
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

    fun exportChat(ctx: Context, id: String) {
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
