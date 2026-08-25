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
)

object Models {

    val CATALOG = listOf(
        SdModel(
            id = "sd15_q4",
            label = "Standard — SD 1.5 (≈ 1,0 Go)",
            fileName = "sd15_q4_0.gguf",
            approxMb = 1030,
            urls = listOf(
                "https://huggingface.co/gpustack/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-Q4_0.gguf",
                "https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF/resolve/main/v1-5-pruned_Q4_0.gguf",
            ),
        ),
        SdModel(
            id = "sd15_q8",
            label = "Haute fidélité — SD 1.5 Q8 (≈ 1,8 Go)",
            fileName = "sd15_q8_0.gguf",
            approxMb = 1830,
            urls = listOf(
                "https://huggingface.co/gpustack/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-Q8_0.gguf",
                "https://huggingface.co/kostakoff/stable-diffusion-v1-5-GGUF/resolve/main/v1-5-pruned_Q8_0.gguf",
            ),
        ),
    )

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun fileOf(context: Context, model: SdModel): File = File(dir(context), model.fileName)

    fun isInstalled(context: Context, model: SdModel): Boolean {
        val f = fileOf(context, model)
        // Un GGUF valide fait plusieurs centaines de Mo : élimine les restes vides.
        return f.exists() && f.length() > 200L * 1024 * 1024
    }

    /** Le modèle prêt à l'emploi (préférence au plus léger installé). */
    fun installed(context: Context): SdModel? = CATALOG.firstOrNull { isInstalled(context, it) }
}
