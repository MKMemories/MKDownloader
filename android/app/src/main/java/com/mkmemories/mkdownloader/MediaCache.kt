package com.mkmemories.mkdownloader

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Cache disque partagé pour l'audio — le vrai « module audio local ».
 *
 * ExoPlayer y écrit les octets de chaque titre AU FUR ET À MESURE de la lecture
 * (streaming + mise en cache simultanée). À la ré-écoute, la lecture se fait
 * directement depuis le disque : instantanée, sans réseau, sans 403, découplée
 * du flux YouTube fragile.
 *
 * Instance UNIQUE par processus : SimpleCache verrouille son dossier, en créer
 * deux lèverait une exception.
 */
@UnstableApi
object MediaCache {
    private const val MAX_BYTES = 512L * 1024 * 1024   // 512 Mo (LRU)

    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.cacheDir, "media-audio"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
}
