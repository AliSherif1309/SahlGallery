package com.sahl.gallery

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

class CleanupActivity : ComponentActivity() {
    private var dark = false
    private val selectedItems = LinkedHashMap<Long, MediaItem>()
    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var btnTrash: Button

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            selectedItems.clear()
            setResult(RESULT_OK)
            scanStorage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bg = if (dark) Color.parseColor("#111315") else Color.parseColor("#F5F6F5")
        val textPrimary = if (dark) Color.WHITE else Color.parseColor("#14171A")
        val accent = Color.parseColor("#0E6B4E")
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(textPrimary)
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.cleanup_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        root.addView(header)

        statusText = TextView(this).apply {
            text = getString(R.string.cleanup_scanning)
            textSize = 14f
            setTextColor(accent)
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        root.addView(statusText)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        root.addView(ScrollView(this).apply { addView(listContainer) }, LinearLayout.LayoutParams(-1, 0, 1f))

        btnTrash = Button(this).apply {
            text = getString(R.string.action_delete)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { trashSelected() }
        }
        root.addView(btnTrash, LinearLayout.LayoutParams(-1, -2).apply {
            val m = (16 * dp).toInt()
            setMargins(m, (8 * dp).toInt(), m, m)
        })

        setContentView(root)
        scanStorage()
    }

    private fun scanStorage() {
        statusText.text = getString(R.string.cleanup_scanning)
        listContainer.removeAllViews()
        btnTrash.visibility = View.GONE

        Thread {
            val all = MediaRepo.loadAllMedia(this)
            val dupGroups = DuplicateFinder.findDuplicateGroups(this, all)
            val large = all.filter { it.sizeBytes >= 20L * 1024L * 1024L }.sortedByDescending { it.sizeBytes }
            val screenshots = all.filter { it.bucket.equals("Screenshots", ignoreCase = true) }

            runOnUiThread {
                selectedItems.clear()
                // Pre-select extra copies in duplicate groups (keep the first/newest)
                for (g in dupGroups) {
                    for (idx in 1 until g.size) {
                        selectedItems[g[idx].id] = g[idx]
                    }
                }
                renderResults(dupGroups, large, screenshots)
            }
        }.start()
    }

    private fun renderResults(
        dupGroups: List<List<MediaItem>>,
        large: List<MediaItem>,
        screenshots: List<MediaItem>
    ) {
        val dp = resources.displayMetrics.density
        val cardBg = if (dark) Color.parseColor("#1C2023") else Color.WHITE
        val textPrimary = if (dark) Color.WHITE else Color.parseColor("#14171A")

        fun sectionHeader(title: String) {
            listContainer.addView(TextView(this).apply {
                text = title
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
                setPadding(0, (12 * dp).toInt(), 0, (6 * dp).toInt())
            })
        }

        fun itemRow(item: MediaItem, tagText: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(cardBg)
                    cornerRadius = 12 * dp
                }
                setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * dp).toInt() }
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
                tag = item.id
            }
            MediaRepo.loadThumbnailAsync(this, item) { id, bmp ->
                if (thumb.tag == id && bmp != null) thumb.post { thumb.setImageBitmap(bmp) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (10 * dp).toInt() }
            }
            info.addView(TextView(this).apply {
                text = item.name
                textSize = 13.5f
                setTextColor(textPrimary)
                maxLines = 1
            })
            info.addView(TextView(this).apply {
                text = "${MediaRepo.formatBytes(item.sizeBytes)} · $tagText"
                textSize = 12f
                setTextColor(Color.GRAY)
            })
            val cb = CheckBox(this).apply {
                isChecked = selectedItems.containsKey(item.id)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedItems[item.id] = item else selectedItems.remove(item.id)
                    updateTrashButton()
                }
            }
            row.addView(thumb)
            row.addView(info)
            row.addView(cb)
            listContainer.addView(row)
        }

        if (dupGroups.isNotEmpty()) {
            sectionHeader("${getString(R.string.cleanup_duplicates)} (${dupGroups.sumOf { it.size - 1 }})")
            dupGroups.forEachIndexed { gIdx, group ->
                group.forEachIndexed { idx, item ->
                    val label = if (idx == 0) "Keep #${gIdx + 1}" else "Duplicate #${gIdx + 1}"
                    itemRow(item, label)
                }
            }
        }
        if (large.isNotEmpty()) {
            sectionHeader("${getString(R.string.cleanup_large)} (${large.size})")
            large.take(20).forEach { itemRow(it, it.bucket) }
        }
        if (screenshots.isNotEmpty()) {
            sectionHeader("${getString(R.string.cleanup_screenshots)} (${screenshots.size})")
            screenshots.take(20).forEach { itemRow(it, "Screenshots") }
        }

        if (dupGroups.isEmpty() && large.isEmpty() && screenshots.isEmpty()) {
            statusText.text = getString(R.string.cleanup_clean)
        } else {
            updateTrashButton()
        }
    }

    private fun updateTrashButton() {
        val totalBytes = selectedItems.values.sumOf { it.sizeBytes }
        if (selectedItems.isEmpty()) {
            btnTrash.visibility = View.GONE
            statusText.text = getString(R.string.cleanup_ready)
        } else {
            btnTrash.visibility = View.VISIBLE
            val summary = "${selectedItems.size} · ${MediaRepo.formatBytes(totalBytes)}"
            btnTrash.text = "${getString(R.string.action_delete)} ($summary)"
            statusText.text = getString(R.string.cleanup_reclaimable, MediaRepo.formatBytes(totalBytes))
        }
    }

    private fun trashSelected() {
        val uris = selectedItems.values.map { it.uri }
        if (uris.isEmpty()) return
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, uris, true)
            trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (_: Exception) { }
    }
}
