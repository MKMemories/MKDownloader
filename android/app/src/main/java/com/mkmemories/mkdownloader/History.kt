package com.mkmemories.mkdownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: String,
    val title: String,
    val platform: String,
    val fileName: String,
    val uri: String,
    val timestamp: Long,
    val audio: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("platform", platform)
        put("fileName", fileName); put("uri", uri); put("timestamp", timestamp); put("audio", audio)
    }

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            id = o.optString("id"),
            title = o.optString("title"),
            platform = o.optString("platform", "Autre"),
            fileName = o.optString("fileName"),
            uri = o.optString("uri"),
            timestamp = o.optLong("timestamp"),
            audio = o.optBoolean("audio"),
        )
    }
}

/** Historique des téléchargements, persisté localement (SharedPreferences JSON). */
object History {
    private const val PREFS = "mkdl_history"
    private const val KEY = "entries"

    fun all(context: Context): List<HistoryEntry> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(HistoryEntry::fromJson)
        }.sortedByDescending { it.timestamp }
    }

    fun add(context: Context, entry: HistoryEntry) {
        val list = all(context).toMutableList()
        list.add(0, entry)
        save(context, list)
    }

    /** Supprime une entrée ET le fichier associé (libère l'espace). */
    fun remove(context: Context, id: String) {
        val list = all(context).toMutableList()
        val entry = list.firstOrNull { it.id == id } ?: return
        deleteFile(context, entry)
        list.removeAll { it.id == id }
        save(context, list)
    }

    /** Renomme un élément : titre affiché + (au mieux) nom du fichier via MediaStore. */
    fun rename(context: Context, id: String, newTitle: String): Boolean {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return false
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val entry = list[idx]
        val newFileName = renamePreservingExt(entry.fileName, clean)
        runCatching {
            if (entry.uri.isNotEmpty()) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
                }
                context.contentResolver.update(Uri.parse(entry.uri), values, null, null)
            }
        }
        list[idx] = entry.copy(title = clean, fileName = newFileName)
        save(context, list)
        return true
    }

    private fun renamePreservingExt(oldName: String, newTitle: String): String {
        val dot = oldName.lastIndexOf('.')
        val ext = if (dot > 0) oldName.substring(dot) else ""
        val safe = newTitle.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120).ifEmpty { "sans-titre" }
        return safe + ext
    }

    fun clear(context: Context, alsoFiles: Boolean) {
        if (alsoFiles) all(context).forEach { deleteFile(context, it) }
        save(context, emptyList())
    }

    private fun deleteFile(context: Context, entry: HistoryEntry) {
        runCatching {
            if (entry.uri.isEmpty()) return
            val uri = android.net.Uri.parse(entry.uri)
            if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
            else context.contentResolver.delete(uri, null, null)
        }
    }

    private fun save(context: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
