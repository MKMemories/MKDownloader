package com.mkmemories.mkstudio

/**
 * Pont vers le moteur natif stable-diffusion.cpp (libmkstudio.so).
 * Toutes les fonctions bloquent : à appeler depuis le thread de génération.
 */
object NativeSD {

    init {
        System.loadLibrary("mkstudio")
    }

    /** Progression de l'échantillonnage (step/steps), appelée par le natif. */
    @Volatile
    var progressListener: ((step: Int, steps: Int) -> Unit)? = null

    @JvmStatic
    fun onProgress(step: Int, steps: Int) {
        progressListener?.invoke(step, steps)
    }

    /**
     * Charge (ou réutilise) le modèle GGUF. Long : 10-40 s la première fois.
     * @param taesdPath mini-décodeur TAESD optionnel (décodage final ~10× plus vite).
     */
    external fun loadModel(path: String, threads: Int, taesdPath: String?): Boolean

    external fun isLoaded(): Boolean

    /** Récupère et vide le journal du moteur natif (pour le Journal technique). */
    external fun getLogs(): String

    /** Capacités CPU détectées (NEON, DOTPROD, FP16…). */
    external fun systemInfo(): String

    external fun unload()

    /** Demande l'arrêt de la génération en cours (dès que possible). */
    external fun cancel()

    /**
     * Génère une image (bloquant, plusieurs minutes possibles).
     * @param init     null = texte→image ; sinon pixels RGB (width*height*3) de la
     *                 photo de départ, déjà redimensionnée — retouche (img2img).
     * @param loraPath LoRA optionnel (ex : accélérateur LCM) ; null = aucun.
     * @param useLcm   true = échantillonneur LCM (4-6 étapes, avec le LoRA LCM).
     * @return pixels RGB (width*height*3) ou null (échec/annulation).
     */
    external fun generate(
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        init: ByteArray?,
        strength: Float,
        loraPath: String?,
        loraMultiplier: Float,
        useLcm: Boolean,
    ): ByteArray?
}
