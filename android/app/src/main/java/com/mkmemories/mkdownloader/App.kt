package com.mkmemories.mkdownloader

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class App : Application()

/** Initialisation paresseuse (et unique) du moteur yt-dlp + ffmpeg embarqué. */
object Engine {
    @Volatile private var ready = false
    private val mutex = Mutex()

    suspend fun ensureReady(context: Context) = withContext(Dispatchers.IO) {
        if (ready) return@withContext
        mutex.withLock {
            if (ready) return@withLock
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            ready = true
        }
    }

    suspend fun updateYtDlp(context: Context): String = withContext(Dispatchers.IO) {
        ensureReady(context)
        val status = YoutubeDL.getInstance()
            .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp déjà à jour"
        else "yt-dlp mis à jour ✔"
    }
}
