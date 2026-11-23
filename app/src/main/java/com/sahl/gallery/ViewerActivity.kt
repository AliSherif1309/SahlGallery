package com.sahl.gallery

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class ViewerActivity : ComponentActivity() {
    companion object {
        var pendingItems: List<MediaItem> = emptyList()
    }

    private var items: MutableList<MediaItem> = mutableListOf()
    private var favorites: MutableSet<Long> = mutableSetOf()
    private lateinit var pager: ViewPager2
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var btnFav: TextView
    private lateinit var btnEdit: TextView
    private var chromeVisible = true

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val pos = pager.currentItem
            if (pos in items.indices) {
                items.removeAt(pos)
                if (items.isEmpty()) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    pager.adapter?.notifyItemRemoved(pos)
                    updateHeader()
                    setResult(RESULT_OK)
                }
            }
        }
    }

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        favorites = MediaRepo.getFavorites(this)

        val externalUri: Uri? = intent.data
        val startIndex = intent.getIntExtra("index", 0)
        items = if (pendingItems.isNotEmpty() && externalUri == null) {
            pendingItems.toMutableList()
        } else if (externalUri != null) {
            val mime = intent.type ?: contentResolver.getType(externalUri) ?: "image/*"
            mutableListOf(
                MediaItem(
                    id = System.currentTimeMillis(),
                    uriString = externalUri.toString(),
                    name = externalUri.lastPathSegment ?: "media",
                    bucket = "",
                    dateAddedSec = System.currentTimeMillis() / 1000L,
                    sizeBytes = 0L,
                    durationMs = 0L,
                    isVideo = mime.startsWith("video"),
                    mimeType = mime
                )
            )
        } else {
            finish()
            return
        }

        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        pager = ViewPager2(this).apply {
            adapter = ViewerAdapter()
            setCurrentItem(startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), false)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateHeader()
                }
            })
        }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))

        // Top Bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding((12 * dp).toInt(), (36 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        topBar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { finish() }
        })
        titleText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        topBar.addView(titleText)
        topBar.addView(TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { showMoreMenu() }
        })
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Bottom Bar
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt())
        }
        fun actionBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { onClick() }
        }
        btnFav = actionBtn("♡ ${getString(R.string.action_favorite)}") { toggleFav() }
        btnEdit = actionBtn("${getString(R.string.action_edit)}") { openEditor() }
        val btnShare = actionBtn("↗ ${getString(R.string.action_share)}") {
            currentItem()?.let { MediaRepo.shareUri(this, it.uri, it.mimeType) }
        }
        val btnInfo = actionBtn("ⓘ ${getString(R.string.action_info)}") { showInfo() }
        val btnDelete = actionBtn("${getString(R.string.action_delete)}") { trashCurrent() }

        bottomBar.addView(btnFav)
        bottomBar.addView(btnEdit)
        bottomBar.addView(btnShare)
        bottomBar.addView(btnInfo)
        bottomBar.addView(btnDelete)
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
        updateHeader()
    }

    private fun currentItem(): MediaItem? = items.getOrNull(pager.currentItem)

    private fun updateHeader() {
        val item = currentItem() ?: return
        titleText.text = item.name
        val isFav = favorites.contains(item.id)
        btnFav.text = if (isFav) "♥ ${getString(R.string.action_favorite)}" else "♡ ${getString(R.string.action_favorite)}"
        btnEdit.visibility = if (item.isVideo) View.GONE else View.VISIBLE
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        val v = if (chromeVisible) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = v
    }

    private fun toggleFav() {
        val item = currentItem() ?: return
        val added = MediaRepo.toggleFavorite(this, item.id)
        if (added) favorites.add(item.id) else favorites.remove(item.id)
        updateHeader()
        setResult(RESULT_OK)
    }

    private fun openEditor() {
        val item = currentItem() ?: return
        if (item.isVideo) return
        editLauncher.launch(Intent(this, EditorActivity::class.java).setData(item.uri))
    }

    private fun showInfo() {
        val item = currentItem() ?: return
        val msg = MediaRepo.readExifSummary(this, item)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_info)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun trashCurrent() {
        val item = currentItem() ?: return
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, listOf(item.uri), true)
            trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot delete this item", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMoreMenu() {
        val item = currentItem() ?: return
        val opts = mutableListOf(getString(R.string.action_copy_to), getString(R.string.action_lock))
        if (!item.isVideo) opts.add(getString(R.string.action_wallpaper))
        AlertDialog.Builder(this)
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which]) {
                    getString(R.string.action_copy_to) -> {
                        val albums = arrayOf("SahlGallery", "Favorites", "Camera")
                        AlertDialog.Builder(this)
                            .setTitle(R.string.action_copy_to)
                            .setItems(albums) { _, aIdx ->
                                val target = albums[aIdx]
                                Thread {
                                    val dest = MediaRepo.copyToAlbum(this, item, target)
                                    runOnUiThread {
                                        if (dest != null) {
                                            Toast.makeText(this, getString(R.string.copied_to_album, target), Toast.LENGTH_SHORT).show()
                                            setResult(RESULT_OK)
                                        }
                                    }
                                }.start()
                            }.show()
                    }
                    getString(R.string.action_lock) -> {
                        Thread {
                            val ok = MediaRepo.moveToVault(this, item)
                            runOnUiThread {
                                if (ok) {
                                    Toast.makeText(this, R.string.vault_added, Toast.LENGTH_SHORT).show()
                                    trashCurrent()
                                }
                            }
                        }.start()
                    }
                    getString(R.string.action_wallpaper) -> {
                        Thread {
                            val bmp = MediaRepo.decodeSampledBitmap(this, item.uri, 8_000_000L)
                            if (bmp != null) {
                                WallpaperManager.getInstance(this).setBitmap(bmp)
                                bmp.recycle()
                                runOnUiThread { Toast.makeText(this, R.string.wallpaper_set, Toast.LENGTH_SHORT).show() }
                            }
                        }.start()
                    }
                }
            }.show()
    }

    inner class ViewerAdapter : RecyclerView.Adapter<ViewerAdapter.VH>() {
        inner class VH(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(-1, -1)
                setBackgroundColor(Color.BLACK)
            }
            return VH(box)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.container.removeAllViews()
            if (item.isVideo) {
                val vv = VideoView(holder.container.context).apply {
                    setVideoURI(item.uri)
                    val mc = MediaController(holder.container.context)
                    mc.setAnchorView(this)
                    setMediaController(mc)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        start()
                    }
                }
                holder.container.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            } else {
                val zv = ZoomImageView(holder.container.context).apply {
                    onSingleTap = { toggleChrome() }
                }
                holder.container.addView(zv, FrameLayout.LayoutParams(-1, -1))
                Thread {
                    val bmp = MediaRepo.decodeSampledBitmap(this@ViewerActivity, item.uri)
                    runOnUiThread {
                        if (bmp != null) zv.setImageBitmap(bmp)
                    }
                }.start()
            }
        }
    }
}
