package com.mkmemories.mkstudio

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.mkmemories.mkstudio.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding

    // Styles : libellé → suffixe ajouté au prompt (SD 1.5 comprend l'anglais).
    private val styles = listOf(
        "Aucun" to "",
        "Photo réaliste" to ", photorealistic, highly detailed, sharp focus, 8k photo",
        "Portrait" to ", portrait photography, 85mm lens, soft light, bokeh",
        "Dessin animé" to ", cartoon illustration, vibrant colors, clean lines",
        "Peinture" to ", oil painting, fine art, textured brush strokes",
        "Cyberpunk" to ", cyberpunk style, neon lights, futuristic city",
        "Fantasy" to ", epic fantasy art, dramatic lighting, intricate details",
    )
    private val formats = listOf(
        Triple("Carré", 512, 512),
        Triple("Portrait", 512, 768),
        Triple("Paysage", 768, 512),
        Triple("Éclair (rapide)", 384, 384),
    )
    // Étapes par qualité : [turbo LCM, précis]
    private val qualities = listOf(
        Triple("Rapide", 4, 12),
        Triple("Standard", 6, 20),
        Triple("Fin", 10, 28),
    )
    private val strengths = listOf("Légère" to 0.35f, "Moyenne" to 0.55f, "Forte" to 0.75f)

    private val negativeDefault =
        "ugly, deformed, disfigured, extra limbs, blurry, low quality, watermark, text, jpeg artifacts"

    private var editBitmap: Bitmap? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) loadEditBitmap(uri)
        }

    private val askNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        buildChips(ui.styleChips, styles.map { it.first })
        buildChips(ui.formatChips, formats.map { it.first })
        buildChips(ui.modeChips, listOf(getString(R.string.mode_turbo), getString(R.string.mode_precise)))
        buildChips(ui.qualityChips, qualities.map { it.first }, checkedIndex = 1)
        buildChips(ui.strengthChips, strengths.map { it.first }, checkedIndex = 1)

        ui.bottomNav.setOnItemSelectedListener { item ->
            ui.paneCreate.isVisible = item.itemId == R.id.nav_create
            ui.paneEdit.isVisible = item.itemId == R.id.nav_edit
            ui.paneGallery.isVisible = item.itemId == R.id.nav_gallery
            if (item.itemId == R.id.nav_gallery) refreshGallery()
            true
        }

        ui.generateBtn.setOnClickListener { generateFromCreate() }
        ui.pickBtn.setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        ui.editGenerateBtn.setOnClickListener { generateFromEdit() }
        ui.cancelBtn.setOnClickListener { Studio.cancelCurrent() }
        ui.shareBtn.setOnClickListener { shareResult() }
        ui.logsBtn.setOnClickListener { showLogs() }

        ui.galleryList.layoutManager = GridLayoutManager(this, 2)
        ui.galleryList.adapter = galleryAdapter

        Studio.onChange = { render() }
        render()
    }

    override fun onDestroy() {
        Studio.onChange = null
        super.onDestroy()
    }

    // ---------- Génération ----------

    private fun generateFromCreate() {
        if (Studio.busy) { toast(getString(R.string.busy)); return }
        if (Models.installed(this) == null) { toast(getString(R.string.need_model)); return }
        val prompt = ui.promptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) { toast(getString(R.string.need_prompt)); return }
        val style = styles[checkedIndex(ui.styleChips)].second
        val (_, w, h) = formats[checkedIndex(ui.formatChips)]
        val turbo = checkedIndex(ui.modeChips) == 0
        if (turbo && !Models.turboReady(this)) { toast(getString(R.string.need_turbo)); return }
        val q = qualities[checkedIndex(ui.qualityChips)]
        val steps = if (turbo) q.second else q.third
        Studio.generate(this, prompt + style, negativeDefault, w, h, steps, turbo = turbo)
        render()
    }

    private fun generateFromEdit() {
        if (Studio.busy) { toast(getString(R.string.busy)); return }
        if (Models.installed(this) == null) { toast(getString(R.string.need_model)); return }
        val src = editBitmap ?: run { toast(getString(R.string.edit_need_photo)); return }
        val prompt = ui.editPrompt.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) { toast(getString(R.string.need_prompt)); return }
        val strength = strengths[checkedIndex(ui.strengthChips)].second
        // La retouche garde un format carré 512 (bon compromis vitesse/qualité).
        val turbo = Models.turboReady(this)
        Studio.generate(
            this, prompt, negativeDefault, 512, 512, if (turbo) 6 else 20,
            init = src, strength = strength, turbo = turbo,
        )
        render()
    }

    private fun loadEditBitmap(uri: Uri) {
        runCatching {
            val source = ImageDecoder.createSource(contentResolver, uri)
            editBitmap = ImageDecoder.decodeBitmap(source) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = true
            }
            ui.editPreview.isVisible = true
            ui.editPreview.setImageBitmap(editBitmap)
        }.onFailure { toast(it.message ?: "Erreur de lecture de la photo") }
    }

    private fun shareResult() {
        val uri = Studio.lastImageUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    // ---------- Journal technique ----------

    private fun deviceHeader(): String = buildString {
        append("Appareil : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        append(" · Android ${android.os.Build.VERSION.RELEASE}")
        append(" · ${Runtime.getRuntime().availableProcessors()} cœurs\n")
        append("Moteur : ")
        append(runCatching { NativeSD.systemInfo() }.getOrDefault("(non chargé)"))
        append("\n————————————\n")
    }

    private fun showLogs() {
        Logs.drainNative()
        val text = deviceHeader() + Logs.dump()
        val view = android.widget.TextView(this).apply {
            setText(text)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.text))
            setPadding(40, 20, 40, 20)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_title)
            .setView(scroll)
            .setPositiveButton(R.string.logs_copy) { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("MK Studio", text))
                toast(getString(R.string.logs_copied))
            }
            .setNeutralButton(R.string.logs_share) { _, _ ->
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        },
                        getString(R.string.logs_share),
                    ),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Rendu d'état ----------

    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (Studio.busy) {
                render()
                ui.root.postDelayed(this, 1000)
            }
        }
    }

    private fun render() {
        // Carte modèles
        renderModels()
        // Barre d'état
        val busy = Studio.busy
        if (busy) {
            ui.root.removeCallbacks(elapsedTicker)
            ui.root.postDelayed(elapsedTicker, 1000)
        }
        ui.statusCard.isVisible = busy || Studio.lastError != null
        if (busy) {
            val elapsed = getString(
                R.string.st_elapsed,
                Studio.fmtDuration(System.currentTimeMillis() - Studio.opStartedAt),
            )
            ui.statusText.text = Studio.statusText + elapsed
            ui.statusProgress.isVisible = true
            ui.statusProgress.isIndeterminate = Studio.percent < 0
            if (Studio.percent >= 0) ui.statusProgress.setProgressCompat(Studio.percent, true)
        } else if (Studio.lastError != null) {
            ui.statusText.text = Studio.lastError
            ui.statusProgress.isVisible = false
        }
        ui.cancelBtn.isVisible = busy
        // Résultat
        val img = Studio.lastImage
        ui.resultCard.isVisible = !busy && img != null
        if (img != null) ui.resultThumb.setImageBitmap(img)
        ui.generateBtn.isEnabled = !busy
        ui.editGenerateBtn.isEnabled = !busy
    }

    private fun renderModels() {
        val box = ui.modelsBox
        box.removeAllViews()
        var anyInstalled = false
        // Modèles de base + accélérateur ⚡ Turbo (proposé dès qu'une base est là).
        val baseInstalled = Models.CATALOG.any { Models.isInstalled(this, it) }
        val rows = if (baseInstalled) Models.CATALOG + Models.LCM_LORA + Models.TAESD else Models.CATALOG
        rows.forEach { model ->
            val installed = Models.isInstalled(this, model)
            if (installed && model.id != Models.LCM_LORA.id && model.id != Models.TAESD.id) anyInstalled = true
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 0)
            }
            row.addView(
                TextView(this).apply {
                    text = if (installed) "${model.label}  ${getString(R.string.model_installed)}" else model.label
                    setTextColor(getColor(if (installed) R.color.ok else R.color.text))
                    textSize = 13f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (!installed) {
                row.addView(
                    Button(this, null, 0, com.google.android.material.R.style.Widget_Material3_Button_TonalButton).apply {
                        text = getString(R.string.model_download)
                        isEnabled = !Studio.busy
                        setOnClickListener { Studio.downloadModel(this@MainActivity, model) }
                    },
                )
            }
            box.addView(row)
        }
        // La carte reste visible tant qu'il manque un modèle de base, l'accélérateur
        // ou le décodeur rapide.
        ui.modelCard.isVisible = !anyInstalled ||
            Models.CATALOG.any { !Models.isInstalled(this, it) } ||
            !Models.turboReady(this) || !Models.isInstalled(this, Models.TAESD)
    }

    // ---------- Galerie ----------

    private var galleryUris: List<Uri> = emptyList()

    private val galleryAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_image, parent, false)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun getItemCount() = galleryUris.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val img = holder.itemView.findViewById<ImageView>(R.id.cellImage)
            val uri = galleryUris[position]
            img.load(uri) { crossfade(true) }
            img.setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
        }
    }

    private fun refreshGallery() {
        galleryUris = Studio.galleryImages(this)
        ui.galleryEmpty.isVisible = galleryUris.isEmpty()
        @Suppress("NotifyDataSetChanged")
        galleryAdapter.notifyDataSetChanged()
    }

    // ---------- Aides ----------

    private fun buildChips(group: ChipGroup, labels: List<String>, checkedIndex: Int = 0) {
        labels.forEachIndexed { i, label ->
            group.addView(
                Chip(this).apply {
                    text = label
                    isCheckable = true
                    id = androidx.core.view.ViewCompat.generateViewId()
                    isChecked = i == checkedIndex
                },
            )
        }
    }

    private fun checkedIndex(group: ChipGroup): Int {
        for (i in 0 until group.childCount) {
            if ((group.getChildAt(i) as Chip).isChecked) return i
        }
        return 0
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
