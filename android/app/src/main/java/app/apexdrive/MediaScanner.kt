package app.apexdrive

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** One photo or video as the system knows it. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val dateAddedSeconds: Long,
)

/**
 * Reads the gallery through MediaStore.
 *
 * MediaStore is what Android allows an app to see. It does not expose a filesystem path,
 * which is fine: uploads stream from the content URI, and removal (phase 3) must go back
 * through MediaStore with user confirmation.
 */
class MediaScanner(private val context: Context) {

    fun scanImagesAndVideos(limit: Int = 3000): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val collection = MediaStore.Files.getContentUri("external")
        val items = mutableListOf<MediaItem>()

        context.contentResolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)

            while (cursor.moveToNext() && items.size < limit) {
                val id = cursor.getLong(idCol)
                val size = cursor.getLong(sizeCol)
                if (size <= 0L) continue
                items += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = cursor.getString(nameCol) ?: "file-$id",
                    sizeBytes = size,
                    mimeType = cursor.getString(mimeCol) ?: "application/octet-stream",
                    dateAddedSeconds = cursor.getLong(dateCol),
                )
            }
        }
        return items
    }
}
