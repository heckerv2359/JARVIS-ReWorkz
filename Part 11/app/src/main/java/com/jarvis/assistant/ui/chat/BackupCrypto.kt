package com.jarvis.assistant.ui.chat

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Passphrase protection for backup files: PBKDF2 key, AES-GCM encryption. */
object BackupCrypto {

    private const val ITERATIONS = 120000

    private fun deriveKey(pass: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun encrypt(plain: String, pass: CharArray): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        val iv = ByteArray(12)
        random.nextBytes(salt)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pass, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("app", "jarvis-backup")
            .put("v", 1)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("data", b64(encrypted))
            .toString()
    }

    /** Returns null when the passphrase is wrong or the file is not a JARVIS backup. */
    fun decrypt(fileText: String, pass: CharArray): String? {
        return try {
            val o = JSONObject(fileText)
            if (o.optString("app") != "jarvis-backup") return null
            val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(o.getString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(pass, salt), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
