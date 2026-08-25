package com.mkmemories.mkstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mode « ☁ Qualité max » : génération/retouche via l'API Google Gemini
 * (modèle image gemini-2.5-flash-image), avec la clé GRATUITE que l'utilisateur
 * crée sur aistudio.google.com. La clé reste stockée localement sur l'appareil.
 * Bloquant : à appeler depuis le thread de travail.
 */
object CloudEngine {

    private const val PREFS = "mkstudio_cloud"
    private const val KEY = "gemini_key"
    private const val MODEL = "gemini-2.5-flash-image"

    fun key(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun hasKey(context: Context): Boolean = key(context) != null

    fun setKey(context: Context, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value?.trim().orEmpty()).apply()
    }

    /**
     * Génère (ou retouche si [init] non nul) une image. Renvoie le Bitmap final.
     * @param aspect "1:1", "3:4", "4:3", "16:9"…
     */
    fun generate(context: Context, prompt: String, aspect: String, init: Bitmap?): Bitmap {
        val apiKey = key(context) ?: throw IllegalStateException("Clé API manquante.")
        // 1er essai avec le format demandé ; si l'API refuse ce paramètre, on
        // retente sans (l'image sortira en carré par défaut).
        return try {
            call(apiKey, prompt, init, aspect)
        } catch (e: ApiFieldException) {
            call(apiKey, prompt, init, null)
        }
    }

    private class ApiFieldException(message: String) : Exception(message)

    private fun call(apiKey: String, prompt: String, init: Bitmap?, aspect: String?): Bitmap {
        val parts = JSONArray()
        if (init != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", toJpegB64(init)),
                ),
            )
        }
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        if (aspect != null) {
            body.put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                    .put("imageConfig", JSONObject().put("aspectRatio", aspect)),
            )
        }

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().decodeToString() }.orEmpty()

        if (code !in 200..299) {
            val msg = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("message")
            }.getOrNull().orEmpty()
            if (code == 400 && (msg.contains("imageConfig") || msg.contains("aspect", true) ||
                    msg.contains("responseModalities"))
            ) {
                throw ApiFieldException(msg)
            }
            throw IllegalStateException(
                when (code) {
                    400, 401, 403 -> "Clé API refusée — vérifie-la (aistudio.google.com). ${msg.take(120)}"
                    429 -> "Quota gratuit épuisé pour aujourd'hui — réessaie plus tard ou passe en mode ⚡ local."
                    else -> "Erreur cloud HTTP $code. ${msg.take(120)}"
                },
            )
        }

        val root = JSONObject(text)
        root.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw IllegalStateException("Demande bloquée par la sécurité Google ($it). Reformule le prompt.")
        }
        val partsOut = root.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Réponse cloud vide. Reformule le prompt.")
        for (i in 0 until partsOut.length()) {
            val data = partsOut.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
            if (!data.isNullOrEmpty()) {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Image cloud illisible.")
            }
        }
        // Pas d'image : le modèle a répondu en texte (souvent un refus poli).
        val said = (0 until partsOut.length())
            .mapNotNull { partsOut.optJSONObject(it)?.optString("text") }
            .firstOrNull { it.isNotBlank() }
        throw IllegalStateException("Pas d'image générée. ${said?.take(160) ?: "Reformule le prompt."}")
    }

    private fun toJpegB64(src: Bitmap): String {
        val maxSide = 1024
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
