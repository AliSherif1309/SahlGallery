package com.sahl.gallery

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryLogicTest {
    private fun item(
        id: Long,
        name: String,
        bucket: String,
        dateSec: Long,
        size: Long = 100_000L,
        isVideo: Boolean = false
    ) = MediaItem(
        id = id,
        uriString = "content://media/external/images/media/$id",
        name = name,
        bucket = bucket,
        dateAddedSec = dateSec,
        sizeBytes = size,
        durationMs = if (isVideo) 65_000L else 0L,
        isVideo = isVideo,
        mimeType = if (isVideo) "video/mp4" else "image/jpeg"
    )

    @Test
    fun albumAggregationSortsByCount() {
        val list = listOf(
            item(1, "a.jpg", "Camera", 100),
            item(2, "b.jpg", "SahlCamera", 200),
            item(3, "c.jpg", "SahlCamera", 300)
        )
        val albums = MediaRepo.buildAlbums(list)
        assertEquals(2, albums.size)
        assertEquals("SahlCamera", albums[0].name)
        assertEquals(2, albums[0].count)
    }

    @Test
    fun filterItemsSupportsTypeFavoritesAndQuery() {
        val list = listOf(
            item(1, "sunset.jpg", "Camera", 100, size = 20_000_000L),
            item(2, "clip.mp4", "SahlCamera", 200, isVideo = true),
            item(3, "doc.png", "Screenshots", 300)
        )
        val favs = setOf(3L)
        assertEquals(1, MediaRepo.filterItems(list, MediaFilter.VIDEOS, "", favs).size)
        assertEquals(1, MediaRepo.filterItems(list, MediaFilter.FAVORITES, "", favs).size)
        assertEquals(1, MediaRepo.filterItems(list, MediaFilter.LARGE, "", favs).size)
        assertEquals(1, MediaRepo.filterItems(list, MediaFilter.ALL, "sahl", favs).size)
    }

    @Test
    fun dateGroupingCreatesTodayAndYesterdayHeaders() {
        val nowMs = 1_700_000_000_000L
        val nowSec = nowMs / 1000L
        val list = listOf(
            item(1, "today.jpg", "Camera", nowSec - 60),
            item(2, "yest.jpg", "Camera", nowSec - 86_400L)
        )
        val grouped = MediaRepo.groupByDate(list, "Today", "Yesterday", nowMs)
        assertTrue(grouped[0] is TimelineEntry.Header)
        assertEquals("Today", (grouped[0] as TimelineEntry.Header).title)
        assertTrue(grouped.any { it is TimelineEntry.Header && it.title == "Yesterday" })
    }

    @Test
    fun cropAndDurationFormattingWork() {
        val rect = PhotoEditorMath.computeCenteredCropRect(4000, 3000, 1f)
        assertEquals(3000, rect.width)
        assertEquals(3000, rect.height)
        assertEquals(500, rect.left)
        assertEquals("1:05", MediaRepo.formatDuration(65_000L))
    }
}
