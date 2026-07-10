package com.mkmemories.mkdownloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Synthèse **factuelle et neutre** d'une transcription (débat, interview,
 * discours) via l'API Mistral. La clé est saisie dans l'app et stockée
 * localement. Le prompt impose explicitement la neutralité : résumé, temps
 * forts, sujets — aucune opinion, aucun parti pris.
 */
object Mistral {

    private const val PREFS = "mkdl_mistral"
    private const val KEY = "api_key"
    private const val ENDPOINT = "https://api.mistral.ai/v1/chat/completions"
    private const val MODEL = "mistral-small-latest"

    private const val SYSTEM =
        "Tu es un assistant de dérushage pour un créateur vidéo. À partir de la " +
        "transcription d'une intervention (débat, interview, discours), produis une " +
        "SYNTHÈSE STRICTEMENT FACTUELLE ET NEUTRE, sans opinion ni parti pris, sans " +
        "prendre position. Structure : 1) Résumé en 4 à 6 puces ; 2) Temps forts (3 à 5) " +
        "avec un court verbatim entre guillemets ; 3) Sujets/thèmes abordés. Reste " +
        "neutre et fidèle au propos, n'invente rien."

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun key(c: Context): String = prefs(c).getString(KEY, "").orEmpty()
    fun hasKey(c: Context): Boolean = key(c).isNotBlank()
    fun setKey(c: Context, k: String) = prefs(c).edit().putString(KEY, k.trim()).apply()

    /** Renvoie la synthèse, ou lève une exception avec un message lisible. */
    suspend fun synthesize(context: Context, transcript: String): String =
        withContext(Dispatchers.IO) {
            val k = key(context)
            require(k.isNotBlank()) { "Clé Mistral manquante." }
            val body = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.3)
                put("max_tokens", 1100)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", SYSTEM) })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Transcription :\n\n" + transcript.take(24000))
                    })
                })
            }
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $k")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(raw).optJSONObject("message")?.optString("detail")
                        ?: JSONObject(raw).optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Erreur Mistral ($code)."
                error(msg)
            }
            JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim()
                ?: error("Réponse Mistral vide.")
        }
}
