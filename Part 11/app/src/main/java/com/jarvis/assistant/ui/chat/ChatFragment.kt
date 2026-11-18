package com.jarvis.assistant.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The written chat. Replies come from [ChatController] over plain HTTPS, never from the live voice session. */
class ChatFragment : Fragment() {

    private companion object {
        const val MAX_PENDING = 6
        const val VOICE_USER_PLACEHOLDER = "(Audio query)"
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private val repo get() = JarvisApp.instance.chatRepository

    private val pending = ArrayList<String>()
    private var editingTurnId: String? = null
    private var cameraFile: File? = null

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { turn -> startEditing(turn) },
        onRegenerate = { turn -> regenerate(turn) },
        onSpeak = { turn -> speakTurn(turn) }
    )

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) importUris(uris, false)
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris, true)
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        onPictureTaken(ok)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else toast("Camera permission is needed to take a photo.")
    }

    private val dictate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank() && _binding != null) {
                val current = binding.etMessageInput.text?.toString().orEmpty()
                val joined = if (current.isBlank()) heard else current.trimEnd() + " " + heard
                binding.etMessageInput.setText(joined)
                binding.etMessageInput.setSelection(joined.length)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        binding.btnSendMessage.setOnClickListener { onSendOrStop() }

        binding.btnChatNew.setOnClickListener {
            if (ChatController.isGenerating()) ChatController.stop()
            ChatSpeaker.stop()
            cancelEditing()
            repo.newConversation()
            toast("New chat started. Your old chat is saved in the Chats list.")
        }

        binding.btnChatMenu.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }

        binding.btnChatList.setOnClickListener {
            ChatDialogs.showConversations(requireContext()) { cancelEditing() }
        }

        binding.btnChatSettings.setOnClickListener {
            ChatDialogs.showSettings(requireContext()) { updateChips() }
        }

        binding.chipPersona.setOnClickListener {
            ChatDialogs.showPersonaPicker(requireContext()) { updateChips() }
        }

        binding.chipMode.setOnClickListener {
            ChatDialogs.showModePicker(requireContext()) { updateChips() }
        }

        binding.btnAttach.setOnClickListener { showAttachMenu() }
        binding.btnDictate.setOnClickListener { startDictation() }

        updateChips()
        refreshPending()
        observeState()
    }

    private fun toast(text: String) {
        if (_binding != null || context != null) {
            Toast.makeText(requireContext().applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateChips() {
        if (_binding == null) return
        binding.tvChipPersona.text = ChatDialogs.personaLabel(JarvisApp.instance.preferences.personality)
        binding.tvChipMode.text = ChatPrefs.modeLabel()
        binding.tvChatSubtitle.text = "Text chat  \u2022  " + ChatPrefs.modeLabel()
    }

    // ---- sending ----

    private fun onSendOrStop() {
        if (ChatController.isGenerating()) {
            ChatController.stop()
            return
        }
        val text = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() && pending.isEmpty()) return

        val attachments = ArrayList(pending)
        val editId = editingTurnId
        if (editId != null) {
            repo.removeFrom(editId)
            cancelEditing()
        }
        ChatSpeaker.stop()
        ChatController.send(requireContext(), text, attachments)
        binding.etMessageInput.text?.clear()
        pending.clear()
        refreshPending()
    }

    private fun startEditing(turn: ChatTurn) {
        if (ChatController.isGenerating()) {
            toast("Wait for the reply to finish, or tap stop.")
            return
        }
        editingTurnId = turn.id
        val text = if (turn.userTranscript == VOICE_USER_PLACEHOLDER) "" else turn.userTranscript
        binding.etMessageInput.setText(text)
        binding.etMessageInput.setSelection(text.length)
        binding.etMessageInput.hint = "Editing: sending replaces this message and everything after it"
        binding.etMessageInput.requestFocus()
        pending.clear()
        pending.addAll(turn.attachments.orEmpty())
        refreshPending()
    }

    private fun cancelEditing() {
        editingTurnId = null
        if (_binding != null) binding.etMessageInput.hint = "Message Jarvis"
    }

    private fun regenerate(turn: ChatTurn) {
        if (ChatController.isGenerating()) {
            toast("Wait for the reply to finish, or tap stop.")
            return
        }
        val isLast = repo.turnsSnapshot().lastOrNull()?.id == turn.id
        if (isLast) {
            ChatSpeaker.stop()
            ChatController.regenerate(requireContext(), turn.id)
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("Regenerate")
                .setMessage("This removes every message after this one. Continue?")
                .setPositiveButton("Regenerate") { _, _ ->
                    ChatSpeaker.stop()
                    ChatController.regenerate(requireContext(), turn.id)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun speakTurn(turn: ChatTurn) {
        if (ChatSpeaker.isSpeaking()) {
            ChatSpeaker.stop()
        } else {
            ChatSpeaker.speak(requireContext(), turn.jarvisResponse)
        }
    }

    // ---- attachments ----

    private fun showAttachMenu() {
        if (pending.size >= MAX_PENDING) {
            toast("You can attach up to $MAX_PENDING files per message.")
            return
        }
        val menu = PopupMenu(requireContext(), binding.btnAttach)
        menu.menu.add(0, 1, 0, "Photos")
        menu.menu.add(0, 2, 1, "Camera")
        menu.menu.add(0, 3, 2, "Files (PDF or text)")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> pickImages.launch("image/*")
                2 -> {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        requireContext(), Manifest.permission.CAMERA
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                }
                3 -> pickFiles.launch(arrayOf("application/pdf", "text/*", "application/json", "application/xml"))
            }
            true
        }
        menu.show()
    }

    private fun launchCamera() {
        try {
            val file = File(ChatMedia.dir(requireContext()), "camera_capture.jpg")
            cameraFile = file
            val uri = FileProvider.getUriForFile(requireContext(), requireContext().packageName + ".chatfiles", file)
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("Could not open the camera.")
        }
    }

    private fun onPictureTaken(ok: Boolean) {
        val file = cameraFile ?: return
        cameraFile = null
        if (!ok || !file.exists()) return
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                val saved = ChatMedia.importImage(appContext, Uri.fromFile(file))
                file.delete()
                saved
            }
            if (path != null) {
                pending.add(path)
                if (_binding != null) refreshPending()
            } else {
                toast("Could not read the photo.")
            }
        }
    }

    private fun importUris(uris: List<Uri>, filesMode: Boolean) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = 0
            for (uri in uris) {
                if (pending.size >= MAX_PENDING) break
                val path = withContext(Dispatchers.IO) {
                    if (filesMode) ChatMedia.importFile(appContext, uri) else ChatMedia.importImage(appContext, uri)
                }
                if (path != null) pending.add(path) else failed++
            }
            if (_binding != null) refreshPending()
            if (failed > 0) toast("Some files could not be added. Only photos, PDFs and text files under 15 MB work.")
        }
    }

    private fun refreshPending() {
        if (_binding == null) return
        val box = binding.layoutPending
        box.removeAllViews()
        binding.scrollPending.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val gap = (8 * density).toInt()
        for (path in pending.toList()) {
            val view: View
            if (ChatMedia.isImage(path)) {
                val iv = ImageView(requireContext())
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                val bg = GradientDrawable()
                bg.cornerRadius = 12 * density
                bg.setColor(0x22808080)
                iv.background = bg
                iv.clipToOutline = true
                iv.setImageBitmap(ChatMedia.thumbnail(path, 200))
                iv.layoutParams = LinearLayout.LayoutParams(size, size)
                view = iv
            } else {
                val chip = TextView(requireContext())
                chip.text = ChatMedia.displayName(path)
                chip.textSize = 12f
                chip.maxLines = 1
                chip.maxWidth = (150 * density).toInt()
                chip.gravity = android.view.Gravity.CENTER
                val pad = (10 * density).toInt()
                chip.setPadding(pad, 0, pad, 0)
                val bg = GradientDrawable()
                bg.cornerRadius = 12 * density
                bg.setColor(0x22808080)
                chip.background = bg
                chip.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size)
                view = chip
            }
            (view.layoutParams as LinearLayout.LayoutParams).marginEnd = gap
            view.contentDescription = "Attachment. Tap to remove."
            view.setOnClickListener {
                pending.remove(path)
                refreshPending()
                toast("Attachment removed")
            }
            box.addView(view)
        }
    }

    // ---- dictation ----

    private fun startDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
        try {
            dictate.launch(intent)
        } catch (e: Exception) {
            toast("Speech input is not available on this device.")
        }
    }

    // ---- observing ----

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.chatTurns.collect { turns ->
                        val lm = binding.rvChatMessages.layoutManager as LinearLayoutManager
                        val before = chatAdapter.itemCount
                        val nearBottom = before == 0 || lm.findLastVisibleItemPosition() >= before - 2
                        val grew = turns.size > before
                        chatAdapter.submitList(turns) {
                            if (turns.isNotEmpty() && (grew || nearBottom)) {
                                binding.rvChatMessages.scrollToPosition(turns.size - 1)
                            }
                        }
                    }
                }
                launch {
                    ChatController.generating.collect { busy ->
                        binding.btnSendMessage.setImageResource(if (busy) R.drawable.ic_stop_square else R.drawable.ic_send_up)
                        binding.btnSendMessage.contentDescription = if (busy) "Stop" else "Send"
                    }
                }
                launch {
                    repo.title.collect { title ->
                        binding.tvChatTitle.text = title
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
            updateChips()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
