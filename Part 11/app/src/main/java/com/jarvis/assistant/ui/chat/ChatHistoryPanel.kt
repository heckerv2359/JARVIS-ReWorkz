package com.jarvis.assistant.ui.chat

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.ConversationInfo
import java.util.Calendar

/** A slide in history panel, grouped by date like Claude: Pinned, Today, Yesterday, Previous 7 days, Older. */
class ChatHistoryPanel(
    private val activity: Activity,
    private val onChanged: () -> Unit,
    private val onClosed: () -> Unit
) {

    private sealed class Row {
        class Header(val title: String) : Row()
        class Item(val info: ConversationInfo) : Row()
    }

    private val repo get() = JarvisApp.instance.chatRepository
    private var overlay: FrameLayout? = null
    private var panelView: View? = null
    private var scrimView: View? = null
    private var searchBox: EditText? = null
    private var rows: List<Row> = emptyList()
    private var panelAdapter: PanelAdapter? = null

    fun isOpen(): Boolean = overlay != null

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        val ok = activity.theme.resolveAttribute(attr, tv, true)
        return if (ok && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
    }

    fun open() {
        if (overlay != null) return
        val host = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val bg = themeColor(R.attr.themeBg, 0xFF121212.toInt())
        val textPrimary = themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt())
        val textMuted = themeColor(R.attr.themeTextMuted, 0xFF9AA0A6.toInt())

        val root = FrameLayout(activity)
        root.isClickable = true

        val scrim = View(activity)
        scrim.setBackgroundColor(0x99000000.toInt())
        scrim.alpha = 0f
        scrim.setOnClickListener { close() }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val width = minOf(dp(330), (activity.resources.displayMetrics.widthPixels * 0.86f).toInt())
        val panel = LinearLayout(activity)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(bg)
        panel.setPadding(dp(16), dp(40), dp(16), dp(12))
        panel.isClickable = true
        root.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))

        // Header: title and New chat
        val header = LinearLayout(activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(activity)
        title.text = "Chats"
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(textPrimary)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val newBtn = TextView(activity)
        newBtn.text = "+ New chat"
        newBtn.textSize = 13f
        newBtn.setTypeface(null, Typeface.BOLD)
        newBtn.setTextColor(textPrimary)
        newBtn.setPadding(dp(14), dp(8), dp(14), dp(8))
        val pill = GradientDrawable()
        pill.cornerRadius = dp(20).toFloat()
        pill.setStroke(dp(1), textMuted)
        newBtn.background = pill
        newBtn.setOnClickListener {
            repo.newConversation()
            onChanged()
            close()
        }
        header.addView(newBtn)
        panel.addView(header)

        // Search box
        val search = EditText(activity)
        search.hint = "Search chats"
        search.setHintTextColor(textMuted)
        search.setTextColor(textPrimary)
        search.textSize = 14f
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.maxLines = 1
        search.setPadding(dp(14), dp(10), dp(14), dp(10))
        val searchBg = GradientDrawable()
        searchBg.cornerRadius = dp(14).toFloat()
        searchBg.setColor(0x22808080)
        search.background = searchBg
        val searchLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchLp.topMargin = dp(14)
        searchLp.bottomMargin = dp(8)
        panel.addView(search, searchLp)
        searchBox = search

        // List
        val recycler = RecyclerView(activity)
        recycler.layoutManager = LinearLayoutManager(activity)
        val adapter = PanelAdapter(textPrimary, textMuted)
        recycler.adapter = adapter
        panelAdapter = adapter
        panel.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })

        overlay = root
        panelView = panel
        scrimView = scrim
        refresh()

        host.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.translationX = -width.toFloat()
        panel.animate().translationX(0f).setDuration(220).start()
        scrim.animate().alpha(1f).setDuration(220).start()
    }

    fun close() {
        val root = overlay ?: return
        val panel = panelView
        val scrim = scrimView
        overlay = null
        panelView = null
        scrimView = null
        searchBox = null
        panelAdapter = null
        onClosed()
        if (panel != null) {
            scrim?.animate()?.alpha(0f)?.setDuration(160)?.start()
            panel.animate().translationX(-panel.width.toFloat()).setDuration(180).withEndAction {
                (root.parent as? ViewGroup)?.removeView(root)
            }.start()
        } else {
            (root.parent as? ViewGroup)?.removeView(root)
        }
    }

    /** Removes the panel immediately, without animation. */
    fun dismissNow() {
        val root = overlay ?: return
        overlay = null
        panelView = null
        scrimView = null
        searchBox = null
        panelAdapter = null
        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun refresh() {
        val list = repo.listConversations(searchBox?.text?.toString().orEmpty())
        val out = ArrayList<Row>()

        val pinned = list.filter { it.pinned }
        if (pinned.isNotEmpty()) {
            out.add(Row.Header("Pinned"))
            pinned.forEach { out.add(Row.Item(it)) }
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val today = cal.timeInMillis
        val day = 24L * 60L * 60L * 1000L
        val groups = listOf(
            "Today" to today,
            "Yesterday" to today - day,
            "Previous 7 days" to today - 7 * day,
            "Older" to Long.MIN_VALUE
        )
        val rest = list.filter { !it.pinned }
        var upper = Long.MAX_VALUE
        for ((name, lower) in groups) {
            val inGroup = rest.filter { it.updatedAt >= lower && it.updatedAt < upper }
            if (inGroup.isNotEmpty()) {
                out.add(Row.Header(name))
                inGroup.forEach { out.add(Row.Item(it)) }
            }
            upper = lower
        }
        if (out.isEmpty()) out.add(Row.Header("No chats found"))

        rows = out
        panelAdapter?.notifyDataSetChanged()
    }

    private fun showMenu(info: ConversationInfo) {
        val items = arrayOf("Rename", if (info.pinned) "Unpin" else "Pin to top", "Export as text", "Delete")
        AlertDialog.Builder(activity)
            .setTitle(info.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ChatDialogs.promptRename(activity, info) { refresh(); onChanged() }
                    1 -> { repo.togglePin(info.id); refresh() }
                    2 -> ChatDialogs.exportChat(activity, info.id)
                    3 -> AlertDialog.Builder(activity)
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
    }

    private inner class PanelAdapter(
        private val textPrimary: Int,
        private val textMuted: Int
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val density = activity.resources.displayMetrics.density
        private fun dp(v: Int) = (v * density).toInt()

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val t = TextView(activity)
                t.textSize = 12f
                t.setTypeface(null, Typeface.BOLD)
                t.setTextColor(textMuted)
                t.setPadding(dp(4), dp(16), dp(4), dp(6))
                t.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                object : RecyclerView.ViewHolder(t) {}
            } else {
                val box = LinearLayout(activity)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(dp(12), dp(10), dp(12), dp(10))
                box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val name = TextView(activity)
                name.maxLines = 1
                name.ellipsize = android.text.TextUtils.TruncateAt.END
                name.textSize = 15f
                name.setTextColor(textPrimary)
                val preview = TextView(activity)
                preview.maxLines = 1
                preview.ellipsize = android.text.TextUtils.TruncateAt.END
                preview.textSize = 12f
                preview.setTextColor(textMuted)
                box.addView(name)
                box.addView(preview)
                ItemHolder(box, name, preview)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row is Row.Header) {
                (holder.itemView as TextView).text = row.title
                return
            }
            val item = row as Row.Item
            val h = holder as ItemHolder
            val info = item.info
            val active = info.id == repo.activeConversationId()
            h.name.text = info.title
            h.name.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            h.preview.text = info.preview
            h.preview.visibility = if (info.preview.isBlank()) View.GONE else View.VISIBLE
            val bg = GradientDrawable()
            bg.cornerRadius = dp(12).toFloat()
            bg.setColor(if (active) 0x33808080 else 0x00000000)
            h.itemView.background = bg
            h.itemView.setOnClickListener {
                repo.switchTo(info.id)
                onChanged()
                close()
            }
            h.itemView.setOnLongClickListener {
                showMenu(info)
                true
            }
        }
    }

    private class ItemHolder(view: View, val name: TextView, val preview: TextView) : RecyclerView.ViewHolder(view)
}
