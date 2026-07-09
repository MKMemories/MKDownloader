package com.mkmemories.mkdownloader

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Récepteur Cast par défaut (Chromecast, Google TV, TV & enceintes compatibles). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId("CC1AD845") // Default Media Receiver de Google
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
