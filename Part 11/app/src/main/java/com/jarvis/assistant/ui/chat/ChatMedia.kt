package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Stores chat attachments in app storage and turns them into Gemini request parts. */
object ChatMedia {

    private const val MAX_FILE_BYTES = 15L * 1024L * 1024L
    private const val MAX_IMAGE_SOURCE_BYTES = 40L * 1024L * 1024L
    private const val MAX_IMAGE_SIDE = 1600
    private val counter = AtomicInteger(0)
    private val cache = LruCache<String, Bitmap>(48)

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
    private val TEXT_EXT = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm", "kt", "java", "py", "js", "ts", "c", "cpp", "h",
        "log", "yml", "yaml", "sql", "sh", "kts", "gradle", "ini", "toml", "css"
    )

    fun dir(context: Context): File {
        val d = File(context.filesDir, "chat_media")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun ext(path: String): String = path.substringAfterLast('.', "").lowercase()

    fun isImage(path: String): Boolean = ext(path) in IMAGE_EXT

    fun isPdf(path: String): Boolean = ext(path) == "pdf"

    fun displayName(path: String): String {
        val n = File(path).name
        val u = n.indexOf('_')
        return if (u in 1..14) n.substring(u + 1) else n
    }

    private fun mimeFor(path: String): String = when (ext(path)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun newName(suffix: String): String =
        System.currentTimeMillis().toString() + "_" + suffix.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Copies, rotates and shrinks a picture. Blocking: call from a background thread. */
    fun importImage(context: Context, uri: Uri): String? {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.size > MAX_IMAGE_SOURCE_BYTES) return null
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
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_SIDE)
            }
            var bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            if (rotation != 0) {
                val m = Matrix()
                m.postRotate(rotation.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val out = File(dir(context), newName("photo" + counter.incrementAndGet() + ".jpg"))
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            return out.absolutePath
        } catch (e: Throwable) {
            return null
        }
    }

    /** Copies a PDF or text file. Returns null when the type is not supported. Blocking. */
    fun importFile(context: Context, uri: Uri): String? {
        try {
            val resolver = context.contentResolver
            var name = "file"
            var size = -1L
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: "file"
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            val mime = resolver.getType(uri).orEmpty()
            if (mime.startsWith("image/")) return importImage(context, uri)
            val e = ext(name)
            val supported = e == "pdf" || mime == "application/pdf" || mime.startsWith("text/") || e in TEXT_EXT
            if (!supported) return null
            if (size > MAX_FILE_BYTES) return null
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.size > MAX_FILE_BYTES) return null
            val finalName = if (e.isEmpty() && mime == "application/pdf") "$name.pdf" else name
            val out = File(dir(context), newName(finalName))
            out.writeBytes(bytes)
            return out.absolutePath
        } catch (e: Throwable) {
            return null
        }
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(width, height) / s > maxSide) s *= 2
        return s
    }

    fun thumbnail(path: String, maxSide: Int): Bitmap? {
        val key = path + "#" + maxSide
        cache.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            }
            val bmp = BitmapFactory.decodeFile(path, opts)
            if (bmp != null) cache.put(key, bmp)
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    private fun inline(mime: String, bytes: ByteArray): JSONObject =
        JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        )

    /** Request parts for one attachment. Blocking: reads the file. */
    fun toParts(path: String): List<JSONObject> {
        val f = File(path)
        if (!f.exists()) return emptyList()
        return try {
            when {
                isImage(path) -> listOf(inline(mimeFor(path), f.readBytes()))
                isPdf(path) -> listOf(inline("application/pdf", f.readBytes()))
                else -> {
                    val body = f.readText().take(60000)
                    listOf(JSONObject().put("text", "[Attached file: " + displayName(path) + "]\n" + body))
                }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
