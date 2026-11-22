package com.sahl.gallery

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore

/**
 * Sahl Union door of Sahl Gallery (only apps signed with the Sahl key can open it):
 *   content://com.sahl.gallery.union/recent?limit=20 -> id, name, mime, date, uri
 */
class UnionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val c = context ?: return null
        if (uri.pathSegments.firstOrNull() != "recent") return null
        val limit = (uri.getQueryParameter("limit")?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val out = MatrixCursor(arrayOf("id", "name", "mime", "date", "uri"))
        try {
            val filesUri = MediaStore.Files.getContentUri("external")
            val cols = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
            val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
            val args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
            c.contentResolver.query(filesUri, cols, sel, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cur ->
                var count = 0
                while (cur.moveToNext() && count < limit) {
                    val id = cur.getLong(0)
                    val name = cur.getString(1) ?: ""
                    val mime = cur.getString(2) ?: ""
                    val date = cur.getLong(3) * 1000L
                    val mediaType = cur.getInt(4)
                    val base = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val itemUri = ContentUris.withAppendedId(base, id).toString()
                    out.addRow(arrayOf<Any>(id, name, mime, date, itemUri))
                    count++
                }
            }
        } catch (_: Exception) { }
        return out
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
