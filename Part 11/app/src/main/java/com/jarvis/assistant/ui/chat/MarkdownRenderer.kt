package com.jarvis.assistant.ui.chat

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.view.View

/** A small Markdown renderer for chat bubbles: code blocks, inline code, bold, italic, headings, lists, links. */
object MarkdownRenderer {

    private const val FLAG = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    fun render(text: String, onCopyCode: (String) -> Unit): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        var firstLine = true
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                i++
                if (!firstLine) sb.append('\n')
                firstLine = false
                appendCodeBlock(sb, code.toString(), onCopyCode)
                continue
            }

            if (!firstLine) sb.append('\n')
            firstLine = false

            val hashes = trimmed.takeWhile { it == '#' }.length
            if (hashes in 1..6 && trimmed.length > hashes && trimmed[hashes] == ' ') {
                val start = sb.length
                appendInline(sb, trimmed.substring(hashes + 1).trim())
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, FLAG)
                sb.setSpan(RelativeSizeSpan(if (hashes <= 2) 1.2f else 1.1f), start, sb.length, FLAG)
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                val indent = line.length - line.trimStart().length
                sb.append(" ".repeat(minOf(indent, 8)))
                sb.append("\u2022 ")
                appendInline(sb, trimmed.substring(2))
            } else {
                appendInline(sb, line)
            }
            i++
        }
        return sb
    }

    private fun appendCodeBlock(sb: SpannableStringBuilder, code: String, onCopyCode: (String) -> Unit) {
        val headStart = sb.length
        sb.append("Copy code")
        sb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                onCopyCode(code)
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.isUnderlineText = false
                ds.color = 0xFF9AA0A6.toInt()
            }
        }, headStart, sb.length, FLAG)
        sb.setSpan(RelativeSizeSpan(0.8f), headStart, sb.length, FLAG)
        sb.append('\n')
        val start = sb.length
        sb.append(if (code.isEmpty()) " " else code)
        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, FLAG)
        sb.setSpan(BackgroundColorSpan(0x33808080), start, sb.length, FLAG)
        sb.setSpan(RelativeSizeSpan(0.9f), start, sb.length, FLAG)
    }

    private fun appendInline(sb: SpannableStringBuilder, s: String) {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]

            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    val start = sb.length
                    sb.append(s.substring(i + 1, end))
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, FLAG)
                    sb.setSpan(BackgroundColorSpan(0x33808080), start, sb.length, FLAG)
                    i = end + 1
                    continue
                }
            }

            if (c == '*' && i + 1 < n && s[i + 1] == '*') {
                val end = s.indexOf("**", i + 2)
                if (end > i + 2) {
                    val start = sb.length
                    appendInline(sb, s.substring(i + 2, end))
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, FLAG)
                    i = end + 2
                    continue
                }
            }

            if (c == '*' && i + 1 < n && s[i + 1] != ' ' && s[i + 1] != '*') {
                val end = s.indexOf('*', i + 1)
                if (end > i + 1 && s[end - 1] != ' ') {
                    val start = sb.length
                    appendInline(sb, s.substring(i + 1, end))
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, FLAG)
                    i = end + 1
                    continue
                }
            }

            if (c == '[') {
                val close = s.indexOf("](", i + 1)
                if (close > i) {
                    val endParen = s.indexOf(')', close + 2)
                    if (endParen > close) {
                        val label = s.substring(i + 1, close)
                        val url = s.substring(close + 2, endParen).trim()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val start = sb.length
                            sb.append(label.ifBlank { url })
                            sb.setSpan(URLSpan(url), start, sb.length, FLAG)
                            i = endParen + 1
                            continue
                        }
                    }
                }
            }

            sb.append(c)
            i++
        }
    }

    /** Plain text for text to speech: no code, no links, no Markdown symbols. */
    fun stripForSpeech(text: String): String {
        var t = text
        val sources = t.indexOf("\n\n**Sources**")
        if (sources >= 0) t = t.substring(0, sources)
        t = Regex("```[\\s\\S]*?```").replace(t, " code block omitted. ")
        t = Regex("\\[([^\\]]*)\\]\\([^)]*\\)").replace(t, "\$1")
        t = t.replace(Regex("[*`#>]+"), "")
        t = t.replace(Regex("\\s+"), " ")
        return t.trim()
    }
}
