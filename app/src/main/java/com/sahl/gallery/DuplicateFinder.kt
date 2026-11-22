package com.sahl.gallery

import android.content.Context
import android.graphics.Bitmap
import android.util.Size

/**
 * Fast local perceptual difference-hash (dHash) duplicate detector.
 * Operates on 9x8 grayscale thumbnails -> 64-bit hash -> Hamming distance <= 6.
 */
object DuplicateFinder {
    fun computeDHash(pixels9x8: IntArray): Long {
        if (pixels9x8.size < 72) return 0L
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            val rowStart = y * 9
            for (x in 0 until 8) {
                val c1 = pixels9x8[rowStart + x]
                val c2 = pixels9x8[rowStart + x + 1]
                val l1 = 77 * ((c1 ushr 16) and 0xFF) + 150 * ((c1 ushr 8) and 0xFF) + 29 * (c1 and 0xFF)
                val l2 = 77 * ((c2 ushr 16) and 0xFF) + 150 * ((c2 ushr 8) and 0xFF) + 29 * (c2 and 0xFF)
                if (l1 > l2) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun findDuplicateGroups(context: Context, photos: List<MediaItem>, maxScan: Int = 300): List<List<MediaItem>> {
        val candidates = photos.filter { !it.isVideo }.take(maxScan)
        val hashed = ArrayList<Pair<MediaItem, Long>>(candidates.size)
        val buf = IntArray(72)

        for (item in candidates) {
            val bmp = try {
                context.contentResolver.loadThumbnail(item.uri, Size(64, 64), null)
            } catch (_: Exception) {
                null
            } ?: continue
            val scaled = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            scaled.getPixels(buf, 0, 9, 0, 0, 9, 8)
            scaled.recycle()
            if (bmp !== scaled) bmp.recycle()
            hashed.add(item to computeDHash(buf))
        }

        val used = BooleanArray(hashed.size)
        val groups = ArrayList<List<MediaItem>>()
        for (i in hashed.indices) {
            if (used[i]) continue
            val (itemA, hashA) = hashed[i]
            val group = mutableListOf(itemA)
            for (j in i + 1 until hashed.size) {
                if (used[j]) continue
                if (hammingDistance(hashA, hashed[j].second) <= 6) {
                    used[j] = true
                    group.add(hashed[j].first)
                }
            }
            if (group.size > 1) {
                used[i] = true
                groups.add(group)
            }
        }
        return groups
    }
}
