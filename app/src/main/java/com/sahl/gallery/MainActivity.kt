package com.sahl.gallery

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.MediaStore
import java.util.concurrent.Executors
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : ComponentActivity() {
    private var dark = false
    private var allItems: List<MediaItem> = emptyList()
    private var albums: List<Album> = emptyList()
    private var timelineEntries: List<TimelineEntry> = emptyList()
    private var visibleMedia: List<MediaItem> = emptyList()
    private var favorites: Set<Long> = emptySet()
    private var pinnedAlbums: MutableSet<String> = mutableSetOf()

    private var activeTab = 0 // 0 = Photos, 1 = Albums, 2 = Search
    private var activeAlbum: String? = null
    private var activeFilter = MediaFilter.ALL
    private var photoSort = PhotoSort.NEWEST
    private var albumSort = AlbumSort.COUNT
    private var searchQuery = ""
    private var gridCols = 3
    private var albumsScrollState: Parcelable? = null

    private val selectedIds = LinkedHashSet<Long>()
    private var isPickMode = false

    private lateinit var permissionOverlay: LinearLayout
    private lateinit var topTitle: TextView
    private lateinit var btnBackAlbum: TextView
    private lateinit var btnSort: ImageView
    private lateinit var selectionBar: LinearLayout

    private val mediaExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable {
        if (hasMediaPermission()) reloadMedia()
    }
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            mainHandler.removeCallbacks(reloadRunnable)
            mainHandler.postDelayed(reloadRunnable, 120L)
        }
    }
    private lateinit var selectionCountText: TextView
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var filterRow: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView

    private lateinit var tabPhotos: TextView
    private lateinit var tabAlbums: TextView
    private lateinit var tabSearch: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            permissionOverlay.visibility = View.GONE
            reloadMedia()
        } else {
            permissionOverlay.visibility = View.VISIBLE
        }
    }

    private val viewerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) reloadMedia()
    }

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            selectedIds.clear()
            updateSelectionBar()
            reloadMedia()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        Updater.scheduleDaily(this)

        val act = intent?.action
        isPickMode = act == Intent.ACTION_PICK || act == Intent.ACTION_GET_CONTENT
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val sp = getSharedPreferences("sahl_gallery_prefs", MODE_PRIVATE)
        gridCols = sp.getInt("grid_cols", 3).coerceIn(2, 5)
        photoSort = PhotoSort.fromId(sp.getInt("photo_sort", PhotoSort.NEWEST.id))
        albumSort = AlbumSort.fromId(sp.getInt("album_sort", AlbumSort.COUNT.id))
        pinnedAlbums = MediaRepo.getPinnedAlbums(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selectedIds.isNotEmpty() -> {
                        selectedIds.clear()
                        updateSelectionBar()
                        recycler.adapter?.notifyDataSetChanged()
                    }
                    activeAlbum != null -> exitAlbum()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        buildUi()

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )

        if (hasMediaPermission()) {
            permissionOverlay.visibility = View.GONE
            reloadMedia()
        } else {
            permissionOverlay.visibility = View.VISIBLE
            requestMediaPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        val newCols = getSharedPreferences("sahl_gallery_prefs", MODE_PRIVATE).getInt("grid_cols", gridCols).coerceIn(2, 5)
        if (newCols != gridCols) {
            gridCols = newCols
            applyLayoutManager()
        }
        if (hasMediaPermission()) {
            reloadMedia()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(reloadRunnable)
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Exception) {}
        mediaExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasMediaPermission(): Boolean =
        requiredPermissions().any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestMediaPermissions() {
        permLauncher.launch(requiredPermissions())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val bg = if (dark) Color.parseColor("#111315") else Color.parseColor("#F5F6F5")
        val cardBg = if (dark) Color.parseColor("#1C2023") else Color.WHITE
        val textPrimary = if (dark) Color.WHITE else Color.parseColor("#14171A")
        val accent = Color.parseColor("#0E6B4E")

        val root = FrameLayout(this).apply {
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        btnBackAlbum = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(textPrimary)
            visibility = View.GONE
            setPadding(0, (4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { exitAlbum() }
        }
        topTitle = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val iconSz = (36 * dp).toInt()
        val iconPad = (7 * dp).toInt()
        btnSort = ImageView(this).apply {
            setImageResource(R.drawable.ic_sahl_sort)
            setColorFilter(textPrimary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(iconPad, iconPad, iconPad, iconPad)
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz)
            setOnClickListener { showSortDialog() }
        }
        val btnCleanup = ImageView(this).apply {
            setImageResource(R.drawable.ic_sahl_cleaner)
            setColorFilter(textPrimary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(iconPad, iconPad, iconPad, iconPad)
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz)
            setOnClickListener {
                viewerLauncher.launch(Intent(this@MainActivity, CleanupActivity::class.java))
            }
        }
        val btnCamera = ImageView(this).apply {
            setImageResource(R.drawable.ic_sahl_camera)
            setColorFilter(textPrimary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(iconPad, iconPad, iconPad, iconPad)
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz)
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage("com.sahl.camera")
                    ?: Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                try { startActivity(launch) } catch (_: Exception) { }
            }
        }
        val btnSettings = ImageView(this).apply {
            setImageResource(R.drawable.ic_sahl_settings)
            setColorFilter(textPrimary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(iconPad, iconPad, iconPad, iconPad)
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        topBar.addView(btnBackAlbum)
        topBar.addView(topTitle)
        topBar.addView(btnSort)
        topBar.addView(btnCleanup)
        topBar.addView(btnCamera)
        topBar.addView(btnSettings)
        col.addView(topBar)

        // Multi-select Bar
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(accent)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            visibility = View.GONE
        }
        selectionCountText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnSelShare = TextView(this).apply {
            text = "↗ ${getString(R.string.action_share)}"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { shareSelected() }
        }
        val btnSelDelete = TextView(this).apply {
            text = "${getString(R.string.action_delete)}"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { trashSelected() }
        }
        val btnSelClear = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener {
                selectedIds.clear()
                updateSelectionBar()
                recycler.adapter?.notifyDataSetChanged()
            }
        }
        selectionBar.addView(selectionCountText)
        selectionBar.addView(btnSelShare)
        selectionBar.addView(btnSelDelete)
        selectionBar.addView(btnSelClear)
        col.addView(selectionBar)

        // Search + Filter Container
        searchBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            visibility = View.GONE
        }
        searchInput = EditText(this).apply {
            hint = getString(R.string.search_hint)
            textSize = 14.5f
            setTextColor(textPrimary)
            setHintTextColor(Color.GRAY)
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = 12 * dp
            }
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString() ?: ""
                    refreshDisplay()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        val filters = listOf(
            MediaFilter.ALL to R.string.filter_all,
            MediaFilter.PHOTOS to R.string.filter_photos,
            MediaFilter.VIDEOS to R.string.filter_videos,
            MediaFilter.FAVORITES to R.string.filter_favorites,
            MediaFilter.LARGE to R.string.filter_large
        )
        for ((f, strRes) in filters) {
            val chip = TextView(this).apply {
                tag = f
                text = getString(strRes)
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() }
                setOnClickListener {
                    activeFilter = f
                    updateFilterChips()
                    refreshDisplay()
                }
            }
            filterRow.addView(chip)
        }
        searchBarContainer.addView(searchInput)
        searchBarContainer.addView(filterRow)
        col.addView(searchBarContainer)

        // Empty State
        emptyText = TextView(this).apply {
            text = getString(R.string.empty_media)
            textSize = 15f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        col.addView(emptyText)

        // Pinch-to-resize grid columns on RecyclerView
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (activeTab == 1 && activeAlbum == null) return
                val next = if (detector.scaleFactor > 1.1f) {
                    (gridCols - 1).coerceAtLeast(2)
                } else if (detector.scaleFactor < 0.9f) {
                    (gridCols + 1).coerceAtMost(5)
                } else gridCols
                if (next != gridCols) {
                    gridCols = next
                    getSharedPreferences("sahl_gallery_prefs", MODE_PRIVATE).edit().putInt("grid_cols", gridCols).apply()
                    applyLayoutManager()
                    recycler.adapter?.notifyDataSetChanged()
                }
            }
        })

        recycler = RecyclerView(this).apply {
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            setOnTouchListener { _, event ->
                scaleDetector.onTouchEvent(event)
                false
            }
        }
        col.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom Navigation Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cardBg)
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }
        fun navTab(label: String, tabIdx: Int) = TextView(this).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnClickListener {
                activeTab = tabIdx
                activeAlbum = null
                updateNavTabs()
                refreshDisplay()
            }
        }
        tabPhotos = navTab(getString(R.string.tab_photos), 0)
        tabAlbums = navTab(getString(R.string.tab_albums), 1)
        tabSearch = navTab(getString(R.string.tab_search), 2)
        bottomBar.addView(tabPhotos)
        bottomBar.addView(tabAlbums)
        bottomBar.addView(tabSearch)
        col.addView(bottomBar)

        // Permission Overlay
        permissionOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
            visibility = View.GONE
        }
        permissionOverlay.addView(TextView(this).apply {
            text = getString(R.string.permission_needed)
            textSize = 16f
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * dp).toInt())
        })
        permissionOverlay.addView(Button(this).apply {
            text = getString(R.string.grant_permission)
            setBackgroundColor(accent)
            setTextColor(Color.WHITE)
            setOnClickListener { requestMediaPermissions() }
        })
        root.addView(permissionOverlay, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        updateNavTabs()
        updateFilterChips()
        applyLayoutManager()
    }

    private fun updateNavTabs() {
        val accent = Color.parseColor("#0E6B4E")
        val idle = Color.GRAY
        tabPhotos.setTextColor(if (activeTab == 0) accent else idle)
        tabAlbums.setTextColor(if (activeTab == 1) accent else idle)
        tabSearch.setTextColor(if (activeTab == 2) accent else idle)
        updateSearchBarVisibility()
    }

    private fun updateSearchBarVisibility() {
        val inAlbumsList = activeTab == 1 && activeAlbum == null
        val inSearchTab = activeTab == 2
        searchBarContainer.visibility = if (inAlbumsList || inSearchTab) View.VISIBLE else View.GONE
        filterRow.visibility = if (inSearchTab) View.VISIBLE else View.GONE
    }

    private fun updateFilterChips() {
        val accent = Color.parseColor("#0E6B4E")
        val idleBg = if (dark) Color.parseColor("#24282C") else Color.parseColor("#E3E6E8")
        val dp = resources.displayMetrics.density
        for (i in 0 until filterRow.childCount) {
            val v = filterRow.getChildAt(i) as? TextView ?: continue
            val selected = v.tag == activeFilter
            v.setTextColor(if (selected) Color.WHITE else if (dark) Color.LTGRAY else Color.DKGRAY)
            v.background = GradientDrawable().apply {
                setColor(if (selected) accent else idleBg)
                cornerRadius = 14 * dp
            }
        }
    }

    private fun applyLayoutManager() {
        val inAlbumsGrid = activeTab == 1 && activeAlbum == null
        val cols = if (inAlbumsGrid) 2 else gridCols
        val existing = recycler.layoutManager as? GridLayoutManager
        if (existing != null && existing.spanCount == cols) {
            val hasSpanLookup = existing.spanSizeLookup !is GridLayoutManager.DefaultSpanSizeLookup
            if (inAlbumsGrid && !hasSpanLookup) return
            if (!inAlbumsGrid && hasSpanLookup) return
        }
        recycler.itemAnimator = null
        val lm = GridLayoutManager(this, cols)
        if (!inAlbumsGrid) {
            lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (timelineEntries.getOrNull(position) is TimelineEntry.Header) cols else 1
            }
        }
        recycler.layoutManager = lm
    }

    private fun reloadMedia() {
        val showHidden = getSharedPreferences("sahl_gallery_prefs", MODE_PRIVATE).getBoolean("show_hidden", false)
        if (mediaExecutor.isShutdown) return
        mediaExecutor.execute {
            val loaded = MediaRepo.loadAllMedia(this, includeHidden = showHidden)
            val favs = MediaRepo.getFavorites(this)
            val pinned = MediaRepo.getPinnedAlbums(this)
            val builtAlbums = MediaRepo.sortAlbums(MediaRepo.buildAlbums(loaded), albumSort, pinned)
            runOnUiThread {
                if (loaded == allItems && favs == favorites && pinned == pinnedAlbums && allItems.isNotEmpty()) {
                    return@runOnUiThread
                }
                allItems = loaded
                favorites = favs
                pinnedAlbums = pinned
                albums = builtAlbums
                refreshDisplay()
            }
        }
    }

    private fun openAlbum(name: String) {
        albumsScrollState = recycler.layoutManager?.onSaveInstanceState()
        activeAlbum = name
        refreshDisplay()
    }

    private fun exitAlbum() {
        activeAlbum = null
        refreshDisplay()
        albumsScrollState?.let { state ->
            recycler.post { recycler.layoutManager?.onRestoreInstanceState(state) }
        }
    }

    private fun showSortDialog() {
        val sp = getSharedPreferences("sahl_gallery_prefs", MODE_PRIVATE)
        if (activeTab == 1 && activeAlbum == null) {
            val labels = arrayOf(
                getString(R.string.sort_most_items),
                getString(R.string.sort_name),
                getString(R.string.sort_newest)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.sort_by)
                .setSingleChoiceItems(labels, albumSort.id) { dlg, which ->
                    albumSort = AlbumSort.fromId(which)
                    sp.edit().putInt("album_sort", albumSort.id).apply()
                    albums = MediaRepo.sortAlbums(albums, albumSort, pinnedAlbums)
                    refreshDisplay()
                    dlg.dismiss()
                }.show()
        } else {
            val labels = arrayOf(
                getString(R.string.sort_newest),
                getString(R.string.sort_oldest),
                getString(R.string.sort_largest),
                getString(R.string.sort_name)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.sort_by)
                .setSingleChoiceItems(labels, photoSort.id) { dlg, which ->
                    photoSort = PhotoSort.fromId(which)
                    sp.edit().putInt("photo_sort", photoSort.id).apply()
                    refreshDisplay()
                    dlg.dismiss()
                }.show()
        }
    }

    private fun refreshDisplay() {
        btnBackAlbum.visibility = if (activeAlbum != null) View.VISIBLE else View.GONE
        btnSort.visibility = if (activeTab == 0 || activeTab == 1) View.VISIBLE else View.GONE
        updateSearchBarVisibility()
        topTitle.text = activeAlbum ?: getString(R.string.app_name)
        applyLayoutManager()

        if (activeTab == 1 && activeAlbum == null) {
            emptyText.visibility = View.GONE
            albums = MediaRepo.sortAlbums(albums, albumSort, pinnedAlbums)
            if (recycler.adapter is AlbumAdapter) {
                recycler.adapter?.notifyDataSetChanged()
            } else {
                recycler.adapter = AlbumAdapter()
            }
            return
        }

        val rawSource = when {
            activeAlbum == getString(R.string.all_images_album) -> allItems.filter { !it.isVideo }
            activeAlbum == getString(R.string.favorites_album) -> allItems.filter { favorites.contains(it.id) }
            activeAlbum != null -> allItems.filter { it.bucket == activeAlbum }
            activeTab == 2 -> MediaRepo.filterItems(allItems, activeFilter, searchQuery, favorites)
            else -> allItems
        }

        val sorted = MediaRepo.sortMedia(rawSource, photoSort)
        visibleMedia = sorted
        timelineEntries = if (photoSort == PhotoSort.NEWEST || photoSort == PhotoSort.OLDEST) {
            MediaRepo.groupByDate(sorted, getString(R.string.today), getString(R.string.yesterday))
        } else {
            sorted.map { TimelineEntry.Item(it) }
        }
        emptyText.visibility = if (timelineEntries.isEmpty()) View.VISIBLE else View.GONE
        if (recycler.adapter is TimelineAdapter) {
            recycler.adapter?.notifyDataSetChanged()
        } else {
            recycler.adapter = TimelineAdapter()
        }
    }

    private fun updateSelectionBar() {
        if (selectedIds.isEmpty()) {
            selectionBar.visibility = View.GONE
        } else {
            selectionBar.visibility = View.VISIBLE
            selectionCountText.text = getString(R.string.selected_count, selectedIds.size)
        }
    }

    private fun shareSelected() {
        val items = visibleMedia.filter { selectedIds.contains(it.id) }
        MediaRepo.shareMultiple(this, items)
    }

    private fun trashSelected() {
        val uris = visibleMedia.filter { selectedIds.contains(it.id) }.map { it.uri }
        if (uris.isEmpty()) return
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, uris, true)
            trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (_: Exception) { }
    }

    inner class TimelineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = when (val e = timelineEntries[position]) {
            is TimelineEntry.Header -> -e.title.hashCode().toLong() - 1L
            is TimelineEntry.Item -> e.media.id
        }

        inner class HeaderVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        inner class ItemVH(val box: FrameLayout, val img: ImageView, val badge: TextView, val check: TextView) : RecyclerView.ViewHolder(box)

        override fun getItemViewType(position: Int): Int =
            if (timelineEntries[position] is TimelineEntry.Header) TYPE_HEADER else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val dp = resources.displayMetrics.density
            if (viewType == TYPE_HEADER) {
                val tv = TextView(parent.context).apply {
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (dark) Color.LTGRAY else Color.DKGRAY)
                    setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                }
                return HeaderVH(tv)
            }
            val size = resources.displayMetrics.widthPixels / gridCols
            val box = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(size, size)
                setPadding(2, 2, 2, 2)
            }
            val img = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(if (dark) Color.parseColor("#1C2023") else Color.parseColor("#E0E3E5"))
            }
            val badge = TextView(parent.context).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(170, 0, 0, 0))
                setPadding((5 * dp).toInt(), (2 * dp).toInt(), (5 * dp).toInt(), (2 * dp).toInt())
                visibility = View.GONE
            }
            val check = TextView(parent.context).apply {
                text = "✓"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#0E6B4E"))
                }
                visibility = View.GONE
            }
            box.addView(img, FrameLayout.LayoutParams(-1, -1))
            box.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = (4 * dp).toInt(); marginEnd = (4 * dp).toInt()
            })
            box.addView(check, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
                topMargin = (6 * dp).toInt(); marginEnd = (6 * dp).toInt()
            })
            return ItemVH(box, img, badge, check)
        }

        override fun getItemCount(): Int = timelineEntries.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = timelineEntries[position]
            if (holder is HeaderVH && entry is TimelineEntry.Header) {
                holder.tv.text = entry.title
                return
            }
            if (holder is ItemVH && entry is TimelineEntry.Item) {
                val item = entry.media
                holder.img.tag = item.id
                val cached = MediaRepo.getCachedThumbnail(item.id)
                if (cached != null) {
                    holder.img.setImageBitmap(cached)
                } else {
                    holder.img.setImageBitmap(null)
                    MediaRepo.loadThumbnailAsync(this@MainActivity, item) { id, bmp ->
                        if (holder.img.tag == id && bmp != null) {
                            holder.img.post {
                                if (holder.img.tag == id) holder.img.setImageBitmap(bmp)
                            }
                        }
                    }
                }

                if (item.isVideo) {
                    holder.badge.visibility = View.VISIBLE
                    holder.badge.text = "▶ ${MediaRepo.formatDuration(item.durationMs)}"
                } else {
                    holder.badge.visibility = View.GONE
                }

                val isSel = selectedIds.contains(item.id)
                holder.check.visibility = if (isSel) View.VISIBLE else View.GONE
                holder.img.alpha = if (isSel) 0.7f else 1f

                holder.box.setOnClickListener {
                    if (selectedIds.isNotEmpty()) {
                        if (isSel) selectedIds.remove(item.id) else selectedIds.add(item.id)
                        updateSelectionBar()
                        notifyItemChanged(position)
                        return@setOnClickListener
                    }
                    if (isPickMode) {
                        setResult(RESULT_OK, Intent().setData(item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        finish()
                        return@setOnClickListener
                    }
                    val idx = visibleMedia.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                    ViewerActivity.pendingItems = visibleMedia
                    viewerLauncher.launch(Intent(this@MainActivity, ViewerActivity::class.java).putExtra("index", idx))
                }

                holder.box.setOnLongClickListener {
                    if (isPickMode) return@setOnLongClickListener false
                    if (isSel) selectedIds.remove(item.id) else selectedIds.add(item.id)
                    updateSelectionBar()
                    notifyItemChanged(position)
                    true
                }
            }
        }
    }

    inner class AlbumAdapter : RecyclerView.Adapter<AlbumAdapter.VH>() {
        // +2 special cards at top: Favorites and Private Vault
        inner class VH(val card: LinearLayout, val img: ImageView, val title: TextView, val count: TextView) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val dp = resources.displayMetrics.density
            val cardBg = if (dark) Color.parseColor("#1C2023") else Color.WHITE
            val textPrimary = if (dark) Color.WHITE else Color.parseColor("#14171A")
            val size = resources.displayMetrics.widthPixels / 2 - (24 * dp).toInt()

            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(cardBg)
                    cornerRadius = 14 * dp
                }
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
                layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply {
                    val m = (6 * dp).toInt()
                    setMargins(m, m, m, m)
                }
            }
            val img = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(if (dark) Color.parseColor("#252A2E") else Color.parseColor("#E3E6E8"))
                layoutParams = LinearLayout.LayoutParams(-1, size)
            }
            val title = TextView(parent.context).apply {
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary)
                maxLines = 1
                setPadding((4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), 0)
            }
            val count = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            }
            card.addView(img)
            card.addView(title)
            card.addView(count)
            return VH(card, img, title, count)
        }

        private fun displayedAlbums(): List<Album> {
            val q = searchQuery.trim()
            return if (q.isEmpty()) albums else albums.filter { it.name.contains(q, ignoreCase = true) }
        }

        override fun getItemCount(): Int {
            val list = displayedAlbums()
            return if (searchQuery.trim().isEmpty()) list.size + 3 else list.size
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val qEmpty = searchQuery.trim().isEmpty()
            if (qEmpty && position == 0) {
                val images = allItems.filter { !it.isVideo }
                holder.title.text = "🖼 ${getString(R.string.all_images_album)}"
                holder.count.text = images.size.toString()
                val cover = images.firstOrNull()
                if (cover != null) {
                    holder.img.tag = cover.id
                    val cached = MediaRepo.getCachedThumbnail(cover.id)
                    if (cached != null) {
                        holder.img.setImageBitmap(cached)
                    } else {
                        holder.img.setImageBitmap(null)
                        MediaRepo.loadThumbnailAsync(this@MainActivity, cover) { id, bmp ->
                            if (holder.img.tag == id && bmp != null) {
                                holder.img.post { if (holder.img.tag == id) holder.img.setImageBitmap(bmp) }
                            }
                        }
                    }
                } else {
                    holder.img.setImageBitmap(null)
                }
                holder.card.setOnClickListener {
                    openAlbum(getString(R.string.all_images_album))
                }
                return
            }
            if (qEmpty && position == 1) {
                holder.img.setImageBitmap(null)
                holder.title.text = "♥ ${getString(R.string.favorites_album)}"
                holder.count.text = favorites.size.toString()
                holder.card.setOnClickListener {
                    openAlbum(getString(R.string.favorites_album))
                }
                return
            }
            if (qEmpty && position == 2) {
                holder.img.setImageBitmap(null)
                holder.title.text = "${getString(R.string.vault_album)}"
                holder.count.text = MediaRepo.listVaultFiles(this@MainActivity).size.toString()
                holder.card.setOnClickListener {
                    startActivity(Intent(this@MainActivity, VaultActivity::class.java))
                }
                return
            }
            val list = displayedAlbums()
            val album = list[if (qEmpty) position - 3 else position]
            val isPinned = pinnedAlbums.contains(album.name)
            holder.title.text = if (isPinned) "📌 ${album.name}" else album.name
            holder.count.text = album.count.toString()
            holder.img.tag = album.coverItem.id
            val cached = MediaRepo.getCachedThumbnail(album.coverItem.id)
            if (cached != null) {
                holder.img.setImageBitmap(cached)
            } else {
                holder.img.setImageBitmap(null)
                MediaRepo.loadThumbnailAsync(this@MainActivity, album.coverItem) { id, bmp ->
                    if (holder.img.tag == id && bmp != null) {
                        holder.img.post {
                            if (holder.img.tag == id) holder.img.setImageBitmap(bmp)
                        }
                    }
                }
            }
            holder.card.setOnClickListener {
                openAlbum(album.name)
            }
            holder.card.setOnLongClickListener {
                val nowPinned = MediaRepo.togglePinnedAlbum(this@MainActivity, album.name)
                if (nowPinned) pinnedAlbums.add(album.name) else pinnedAlbums.remove(album.name)
                val msg = if (nowPinned) getString(R.string.album_pinned, album.name) else getString(R.string.album_unpinned, album.name)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                refreshDisplay()
                true
            }
        }
    }
}
