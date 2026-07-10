package com.mkmemories.mkdownloader

import android.content.Context

/**
 * Bibliothèque hors-ligne : les fichiers réellement téléchargés par l'app
 * (journal des téléchargements = source de vérité). Sert l'onglet Bibliothèque
 * et le nœud « Téléchargements » d'Android Auto — lecture 100 % hors réseau.
 */
object OfflineLibrary {

    fun entries(context: Context): List<HistoryEntry> = History.all(context)

    fun audioEntries(context: Context): List<HistoryEntry> = entries(context).filter { it.audio }

    /** VideoItem jouable directement (uri content://) pour une entrée hors-ligne. */
    fun toVideoItem(e: HistoryEntry): VideoItem = VideoItem(
        url = e.uri,
        title = e.title,
        uploader = "Hors-ligne",
        durationSec = 0,
        thumbnail = null,
    )

    /** Tous les morceaux audio hors-ligne (Android Auto, « tout écouter »). */
    fun audioTracks(context: Context): List<VideoItem> = audioEntries(context).map(::toVideoItem)
}
