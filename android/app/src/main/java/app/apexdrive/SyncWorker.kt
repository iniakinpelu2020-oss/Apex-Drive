package app.apexdrive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads new media, then verifies it, then stops.
 *
 * THERE IS NO DELETION IN THIS CLASS, BY DESIGN. Deletion is phase 3 and lives behind an
 * explicit user approval, because an upload that looks successful is not the same as a
 * copy that was re-read and confirmed.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = DriveConfig(applicationContext)
        val baseUrl = config.baseUrl
        val token = config.token
        if (baseUrl.isBlank() || token.isBlank()) {
            return@withContext Result.failure(workDataOf("error" to "server or token not configured"))
        }

        val scanner = MediaScanner(applicationContext)
        val client = UploadClient(applicationContext, baseUrl, { config.token })
        val items = try {
            scanner.scanImagesAndVideos()
        } catch (e: SecurityException) {
            return@withContext Result.failure(workDataOf("error" to "media permission not granted"))
        }

        var uploaded = 0
        var verified = 0
        var failed = 0
        var uploadedBytes = 0L

        for (item in items) {
            if (isStopped) break
            if (config.isUploaded(item.id, item.sizeBytes)) continue

            try {
                val digest = client.sha256(item.uri)
                val upload = client.upload(item, digest)
                if (!upload.ok) {
                    failed++
                    continue
                }
                val key = extractKey(upload.body)
                if (key != null && client.verify(key).ok) verified++
                config.markUploaded(item.id, item.sizeBytes)
                uploaded++
                uploadedBytes += item.sizeBytes
            } catch (e: Exception) {
                failed++
            }

            setProgress(workDataOf("uploaded" to uploaded, "verified" to verified, "failed" to failed))
        }

        Result.success(
            workDataOf(
                "uploaded" to uploaded,
                "verified" to verified,
                "failed" to failed,
                "bytes" to uploadedBytes,
            )
        )
    }

    /** Pulls "key" out of the small JSON the worker returns, without pulling in a JSON lib. */
    private fun extractKey(body: String): String? {
        val marker = "\"key\":\""
        val start = body.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = body.indexOf('"', from)
        if (end <= from) return null
        return body.substring(from, end)
    }
}

/** Small persisted settings + what has already been sent. */
class DriveConfig(context: Context) {
    private val prefs = context.getSharedPreferences("apex-drive", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", "") ?: ""
        set(value) = prefs.edit().putString("baseUrl", value).apply()

    /**
     * A Firebase ID token for the Automation worker.
     *
     * TODO(phase 2): replace this with a real sign-in (Firebase Auth + google-services.json)
     * and refresh the token per run. A hand-pasted token expires after an hour, which is fine
     * for building and testing but not for daily use.
     */
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()

    fun isUploaded(id: Long, size: Long): Boolean = prefs.getBoolean("u:$id:$size", false)

    fun markUploaded(id: Long, size: Long) {
        prefs.edit().putBoolean("u:$id:$size", true).apply()
    }
}
