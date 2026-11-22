package com.sahl.gallery

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class MediaItem(
    val id: Long,
    val uriString: String,
    val name: String,
    val bucket: String,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val isVideo: Boolean,
    val mimeType: String
) {
    val uri: Uri get() = Uri.parse(uriString)
}

data class Album(
    val name: String,
    val count: Int,
    val coverItem: MediaItem
)

sealed class TimelineEntry {
    data class Header(val title: String) : TimelineEntry()
    data class Item(val media: MediaItem) : TimelineEntry()
}

enum class MediaFilter {
    ALL, PHOTOS, VIDEOS, FAVORITES, LARGE
}

object MediaRepo {
    private val thumbPool = Executors.newFixedThreadPool(4)
    private val thumbCache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(8192, 65536)
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    fun clearCache() {
        thumbCache.evictAll()
    }

    fun loadAllMedia(context: Context, includeHidden: Boolean = false): List<MediaItem> {
        val list = ArrayList<MediaItem>(256)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                while (c.moveToNext()) {
                    val rel = if (iRel >= 0) c.getString(iRel) ?: "" else ""
                    if (!includeHidden && rel.split('/').any { it.startsWith(".") }) continue
                    val id = c.getLong(iId)
                    val type = c.getInt(iType)
                    val isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val uri = ContentUris.withAppendedId(baseUri, id)
                    list.add(
                        MediaItem(
                            id = id,
                            uriString = uri.toString(),
                            name = c.getString(iName) ?: "media",
                            bucket = c.getString(iBucket) ?: "Other",
                            dateAddedSec = c.getLong(iDate),
                            sizeBytes = c.getLong(iSize),
                            durationMs = if (isVideo) c.getLong(iDur) else 0L,
                            isVideo = isVideo,
                            mimeType = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*"
                        )
                    )
                }
            }
        } catch (_: Exception) { }
        return list
    }

    fun loadThumbnailAsync(context: Context, item: MediaItem, onLoaded: (Long, Bitmap?) -> Unit) {
        val cached = thumbCache.get(item.id)
        if (cached != null && !cached.isRecycled) {
            onLoaded(item.id, cached)
            return
        }
        thumbPool.execute {
            val bmp = try {
                context.contentResolver.loadThumbnail(item.uri, Size(256, 256), null)
            } catch (_: Exception) {
                null
            }
            if (bmp != null) {
                thumbCache.put(item.id, bmp)
            }
            onLoaded(item.id, bmp)
        }
    }

    fun buildAlbums(items: List<MediaItem>): List<Album> {
        val map = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            map.getOrPut(item.bucket) { ArrayList() }.add(item)
        }
        return map.entries.map { (name, list) ->
            Album(name = name, count = list.size, coverItem = list.first())
        }.sortedByDescending { it.count }
    }

    fun filterItems(
        items: List<MediaItem>,
        filter: MediaFilter,
        query: String,
        favorites: Set<Long>
    ): List<MediaItem> {
        val q = query.trim().lowercase(Locale.US)
        return items.filter { item ->
            val matchFilter = when (filter) {
                MediaFilter.ALL -> true
                MediaFilter.PHOTOS -> !item.isVideo
                MediaFilter.VIDEOS -> item.isVideo
                MediaFilter.FAVORITES -> favorites.contains(item.id)
                MediaFilter.LARGE -> item.sizeBytes >= 10L * 1024L * 1024L
            }
            val matchQuery = q.isEmpty() ||
                item.name.lowercase(Locale.US).contains(q) ||
                item.bucket.lowercase(Locale.US).contains(q)
            matchFilter && matchQuery
        }
    }

    fun groupByDate(
        items: List<MediaItem>,
        todayLabel: String,
        yesterdayLabel: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<TimelineEntry> {
        if (items.isEmpty()) return emptyList()
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val todayYear = cal.get(Calendar.YEAR)
        val todayDay = cal.get(Calendar.DAY_OF_YEAR)

        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yestYear = cal.get(Calendar.YEAR)
        val yestDay = cal.get(Calendar.DAY_OF_YEAR)

        val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val out = ArrayList<TimelineEntry>(items.size + 16)
        var lastHeader = ""

        for (item in items) {
            cal.timeInMillis = item.dateAddedSec * 1000L
            val y = cal.get(Calendar.YEAR)
            val d = cal.get(Calendar.DAY_OF_YEAR)
            val header = when {
                y == todayYear && d == todayDay -> todayLabel
                y == yestYear && d == yestDay -> yesterdayLabel
                else -> monthFmt.format(Date(item.dateAddedSec * 1000L))
            }
            if (header != lastHeader) {
                out.add(TimelineEntry.Header(header))
                lastHeader = header
            }
            out.add(TimelineEntry.Item(item))
        }
        return out
    }

    fun getFavorites(context: Context): MutableSet<Long> {
        val sp = context.getSharedPreferences("sahl_gallery_favs", Context.MODE_PRIVATE)
        val raw = sp.getStringSet("ids", emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toLongOrNull() }.toMutableSet()
    }

    fun toggleFavorite(context: Context, id: Long): Boolean {
        val favs = getFavorites(context)
        val added = if (favs.contains(id)) {
            favs.remove(id)
            false
        } else {
            favs.add(id)
            true
        }
        context.getSharedPreferences("sahl_gallery_favs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("ids", favs.map { it.toString() }.toSet())
            .apply()
        return added
    }

    fun copyToAlbum(context: Context, item: MediaItem, albumName: String): Uri? {
        val relPath = if (item.isVideo) "Movies/$albumName" else "Pictures/$albumName"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val baseUri = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cr = context.contentResolver
        val destUri = cr.insert(baseUri, values) ?: return null
        return try {
            cr.openInputStream(item.uri)?.use { input ->
                cr.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cr.update(destUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            destUri
        } catch (_: Exception) {
            try { cr.delete(destUri, null, null) } catch (_: Exception) { }
            null
        }
    }

    fun readExifSummary(context: Context, item: MediaItem): String {
        val sb = StringBuilder()
        sb.append(item.name).append('\n')
        sb.append("Album: ").append(item.bucket).append('\n')
        sb.append("Size: ").append(formatBytes(item.sizeBytes)).append('\n')
        sb.append("Date: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.dateAddedSec * 1000L))).append('\n')
        if (!item.isVideo) {
            try {
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val w = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                    val h = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)
                    if (w != null && h != null && w != "0") sb.append("Resolution: ${w}×${h}\n")
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    if (!model.isNullOrBlank()) sb.append("Camera: $model\n")
                    val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    val f = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    val exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    val details = listOfNotNull(
                        iso?.let { "ISO $it" },
                        f?.let { "f/$it" },
                        exp?.let { "${it}s" },
                        focal?.let { "${it}mm" }
                    )
                    if (details.isNotEmpty()) sb.append(details.joinToString(" · "))
                }
            } catch (_: Exception) { }
        }
        return sb.toString().trim()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    fun decodeSampledBitmap(context: Context, uri: Uri, maxPixels: Long = 16_000_000L): Bitmap? {
        return try {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val total = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            if (total <= 0L) return null
            var sample = 1
            while (total / (sample * sample) > maxPixels) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val rot = try {
                cr.openInputStream(uri)?.use { s ->
                    when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) { 0f }
            if (rot == 0f) return bmp
            val m = Matrix().apply { postRotate(rot) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also {
                if (it !== bmp) bmp.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    // --- Private Vault (app-private storage) ---
    fun vaultDir(context: Context): File = File(context.filesDir, "vault").apply { mkdirs() }

    fun listVaultFiles(context: Context): List<File> =
        vaultDir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun moveToVault(context: Context, item: MediaItem): Boolean {
        return try {
            val ext = item.name.substringAfterLast('.', if (item.isVideo) "mp4" else "jpg")
            val dest = File(vaultDir(context), "VAULT_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    fun restoreFromVault(context: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name.removePrefix("VAULT_"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SahlGallery")
            }
        }
        val cr = context.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            file.inputStream().use { input ->
                cr.openOutputStream(uri)?.use { output -> input.copyTo(output) }
            }
            file.delete()
            uri
        } catch (_: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) { }
            null
        }
    }

    fun shareUri(context: Context, uri: Uri, mimeType: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, null))
    }
}
