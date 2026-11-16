package com.jarvis.assistant.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import com.jarvis.assistant.pro.VisionAssistant
import com.jarvis.assistant.util.MathSolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { text ->
            binding.etMessageInput.setText(text)
            binding.etMessageInput.setSelection(text.length)
            binding.etMessageInput.requestFocus()
        }
    )

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) askAboutImage(uri)
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

        // Setup Chat List
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        // Send Message
        binding.btnSendMessage.setOnClickListener {
            sendMessage()
        }

        // New Chat / Clear
        binding.btnChatNew.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("New Conversation")
                .setMessage("Start a new conversation and clear current history?")
                .setPositiveButton("New Chat") { _, _ ->
                    JarvisApp.instance.chatRepository.clearHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Back / Menu
        binding.btnChatMenu.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }

        // Mode Chips
        binding.chipPersona.setOnClickListener {
            Toast.makeText(requireContext(), "Persona: Jarvis Active", Toast.LENGTH_SHORT).show()
        }

        binding.chipMode.setOnClickListener {
            Toast.makeText(requireContext(), "Mode: Code & Assistant", Toast.LENGTH_SHORT).show()
        }

        binding.btnAttach.setOnClickListener {
            pickImage.launch("image/*")
        }

        observeChatTurns()
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim() ?: ""
        if (text.isBlank()) return

        if (!JarvisConversationService.isServiceRunning()) {
            (activity as? MainActivity)?.handleMicOrOrbClick()
        }

        JarvisConversationService.sendTextMessage(text)
        binding.etMessageInput.text?.clear()
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(width, height) / s > maxSide) s *= 2
        return s
    }

    private fun readJpeg(uri: Uri): ByteArray? {
        return try {
            val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            var rotation = 0
            try {
                val exif = ExifInterface(ByteArrayInputStream(bytes))
                rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (e: Exception) {
                rotation = 0
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2000) }
            var bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            MathSolver.encodeJpeg(bmp)
        } catch (e: Exception) {
            null
        }
    }

    private fun askAboutImage(uri: Uri) {
        val question = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        binding.etMessageInput.text?.clear()
        val appContext = requireContext().applicationContext
        Toast.makeText(appContext, "Analyzing image...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val jpeg = withContext(Dispatchers.IO) { readJpeg(uri) }
            if (jpeg == null) {
                Toast.makeText(appContext, "Could not open that image.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val answer = withContext(Dispatchers.IO) { VisionAssistant.answer(question, listOf(jpeg)) }
            val userLabel = if (question.isBlank()) "[Image]" else "[Image] $question"
            val reply = if (answer.error == null) answer.text else answer.error
            JarvisApp.instance.chatRepository.addTurn(userLabel, reply)
        }
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty()) {
                            binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
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
