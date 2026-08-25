package com.mkmemories.mkstudio

import android.content.Context
import java.io.File

/**
 * Catalogue des modèles utilisables et emplacement sur l'appareil.
 * Chaque modèle a plusieurs miroirs de téléchargement (HuggingFace).
 */
data class SdModel(
    val id: String,
    val label: String,
    val fileName: String,
    val approxMb: Int,
    val urls: List<String>,
    val minBytes: Long = 200L * 1024 * 1024,   // taille minimale d'un fichier valide
    /** LCM distillé DANS le modèle : Turbo natif sans LoRA (rapide et stable). */
    val lcmBuiltIn: Boolean = false,
    /** Dépôts HuggingFace où résoudre le fichier à l'exécution (API /tree). */
    val repos: List<String> = emptyList(),
    /** Motif (regex) du fichier à choisir dans le dépôt. */
    val filePattern: String = "(?i)q4_0[^/]*\\.gguf$",
)

object Models {

    val CATALOG = listOf(
        // LCM distillé dans le modèle : 4 étapes NATIVEMENT, sans LoRA à
        // appliquer (le gros frein mesuré), et esthétique Dreamshaper.
        SdModel(
            id = "ds8_lcm",
            label = "⚡ Dreamshaper 8 + Turbo intégré (≈ 1,3 Go) · LE plus rapide",
            // v2 : la v1.4 avait téléchargé une quantification 2 bits (résultats
            // moches) sous l'ancien nom — nouveau nom ⇒ re-téléchargement propre.
            fileName = "ds8_lcm_v2.gguf",
            approxMb = 1300,
            urls = emptyList(),   // fichier résolu à l'exécution via l'API HF
            lcmBuiltIn = true,
            repos = listOf(
                "stduhpf/dreamshaper-8LCM-im-GGUF-sdcpp",
                "Steward/lcm-dreamshaper-v7-gguf",
            ),
        ),
        SdModel(
            id = "sd15_q4",
            label = "Rapide — SD 1.5 Q4 (≈ 1,0 Go) · recommandé",
            fileName = "sd15_q4_0.gguf",
            approxMb = 1030,
            urls = listOf(
                "https://huggingface.co/gpustack/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-Q4_0.gguf",
                "https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF/resolve/main/v1-5-pruned_Q4_0.gguf",
            ),
        ),
        SdModel(
            id = "sd15_q8",
            label = "Haute fidélité — SD 1.5 Q8 (≈ 1,8 Go) · plus lent",
            fileName = "sd15_q8_0.gguf",
            approxMb = 1830,
            urls = listOf(
                "https://huggingface.co/gpustack/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-Q8_0.gguf",
                "https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF/resolve/main/v1-5-pruned_Q8_0.gguf",
            ),
        ),
    )

    /** Accélérateur ⚡ LCM : génère en 4-6 étapes au lieu de 20 (~4× plus vite). */
    val LCM_LORA = SdModel(
        id = "lcm_lora",
        label = "⚡ Accélérateur Turbo LCM (≈ 70 Mo)",
        fileName = "lcm_lora_sd15.safetensors",
        approxMb = 70,
        urls = listOf(
            "https://huggingface.co/latent-consistency/lcm-lora-sdv1-5/resolve/main/pytorch_lora_weights.safetensors",
        ),
        minBytes = 30L * 1024 * 1024,
    )

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun fileOf(context: Context, model: SdModel): File = File(dir(context), model.fileName)

    fun isInstalled(context: Context, model: SdModel): Boolean {
        val f = fileOf(context, model)
        // Élimine les fichiers vides/incomplets restés d'un téléchargement raté.
        return f.exists() && f.length() > model.minBytes
    }

    /** Le modèle prêt à l'emploi (préférence au plus rapide installé). */
    fun installed(context: Context): SdModel? = CATALOG.firstOrNull { isInstalled(context, it) }

    fun turboReady(context: Context): Boolean = isInstalled(context, LCM_LORA)

    /** Mini-décodeur TAESD : image finale décodée ~10× plus vite (~10 Mo). */
    val TAESD = SdModel(
        id = "taesd",
        label = "🚀 Décodeur rapide TAESD (≈ 10 Mo)",
        fileName = "taesd_sd15.safetensors",
        approxMb = 10,
        urls = listOf(
            "https://huggingface.co/madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors",
        ),
        minBytes = 2L * 1024 * 1024,
    )

    fun taesdPath(context: Context): String? =
        if (isInstalled(context, TAESD)) fileOf(context, TAESD).absolutePath else null

    /** Purge les fichiers de versions défectueuses (libère l'espace). */
    fun cleanupObsolete(context: Context) {
        listOf("ds8_lcm_q4_0.gguf").forEach { name ->
            val f = File(dir(context), name)
            if (f.exists() && f.delete()) Logs.add("fichier obsolète supprimé: $name")
        }
    }
}
