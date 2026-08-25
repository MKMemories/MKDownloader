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
    // Chaîne de modèles : le premier disponible gagne (le « -preview » garde
    // parfois un quota gratuit distinct).
    private val GEMINI_MODELS = listOf("gemini-2.5-flash-image", "gemini-2.5-flash-image-preview")

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
        val apiKey = key(context)
        if (apiKey == null) {
            // Sans clé : service communautaire gratuit (FLUX) — texte→image seulement.
            if (init != null) {
                throw IllegalStateException("La retouche ☁ nécessite une clé Google (bouton « ☁ Clé qualité max »).")
            }
            return pollinations(prompt, aspect)
        }
        var lastError: Exception? = null
        for (model in GEMINI_MODELS) {
            try {
                // 1er essai avec le format demandé ; si l'API refuse ce paramètre,
                // on retente sans (l'image sortira en carré par défaut).
                return try {
                    call(apiKey, model, prompt, init, aspect)
                } catch (e: ApiFieldException) {
                    call(apiKey, model, prompt, init, null)
                }
            } catch (e: Exception) {
                lastError = e
                Logs.add("cloud $model: ${e.message?.take(160)}")
            }
        }
        // Gemini indisponible (quota/région) : repli gratuit FLUX pour le texte→image.
        if (init == null) {
            Logs.add("repli sur le service gratuit sans clé (FLUX)")
            return pollinations(prompt, aspect)
        }
        throw lastError ?: IllegalStateException("Service cloud indisponible.")
    }

    /** Service communautaire gratuit, sans clé (image.pollinations.ai, FLUX). */
    private fun pollinations(prompt: String, aspect: String): Bitmap {
        val (w, h) = when (aspect) {
            "3:4" -> 832 to 1152
            "4:3" -> 1152 to 832
            else -> 1024 to 1024
        }
        val enc = java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val url = "https://image.pollinations.ai/prompt/$enc?width=$w&height=$h&model=flux&nologo=true"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 180000
        conn.setRequestProperty("User-Agent", "MKStudio/1.7")
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("Service gratuit indisponible (HTTP $code) — réessaie, ou configure une clé Google.")
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Image du service gratuit illisible — réessaie.")
    }

    private class ApiFieldException(message: String) : Exception(message)

    private fun call(apiKey: String, model: String, prompt: String, init: Bitmap?, aspect: String?): Bitmap {
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

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
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
            Logs.add("cloud http $code ($model): ${text.take(400)}")
            if (code == 400 && (msg.contains("imageConfig") || msg.contains("aspect", true) ||
                    msg.contains("responseModalities"))
            ) {
                throw ApiFieldException(msg)
            }
            throw IllegalStateException(
                when (code) {
                    400, 401, 403 -> "Clé API refusée — vérifie-la (aistudio.google.com). ${msg.take(120)}"
                    429 ->
                        if (msg.contains("limit: 0") || msg.contains("limit:0")) {
                            "Ce modèle n'est pas inclus dans l'offre gratuite pour ton compte/ta région. ${msg.take(160)}"
                        } else {
                            "Quota gratuit épuisé — réessaie plus tard. ${msg.take(160)}"
                        }
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
