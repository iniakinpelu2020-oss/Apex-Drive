package app.apexdrive

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class UploadResult(val ok: Boolean, val status: Int, val body: String)

/**
 * Talks to the Apex Drive routes on the Automation worker.
 *
 * Uploads stream from the content URI straight into the request, so a 300 MB video is
 * never held in memory. The hash is computed in a separate pass first: the worker
 * requires the expected sha256 up front so it can store it as the signed reference.
 */
class UploadClient(
    private val context: Context,
    private val baseUrl: String,
    private val tokenProvider: () -> String,
) {

    fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot open $uri")
        BufferedInputStream(stream).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun upload(item: MediaItem, sha256: String): UploadResult {
        val params = buildString {
            append("?name=").append(enc(item.name))
            append("&sha256=").append(sha256)
            append("&path=").append(enc("media/${item.id}"))
        }
        val connection = (URL(baseUrl.trimEnd('/') + "/api/drive/upload" + params).openConnection() as HttpURLConnection)
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${tokenProvider()}")
        connection.setRequestProperty("Content-Type", item.mimeType)
        connection.setFixedLengthStreamingMode(item.sizeBytes)
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000

        val source = context.contentResolver.openInputStream(item.uri)
            ?: throw IllegalStateException("cannot open ${item.uri}")
        BufferedInputStream(source).use { input ->
            connection.outputStream.use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        }

        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()
        return UploadResult(status in 200..299, status, text)
    }

    /** Asks the worker to re-read the stored object and confirm it matches. */
    fun verify(key: String): UploadResult {
        val connection = (URL(baseUrl.trimEnd('/') + "/api/drive/verify").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer ${tokenProvider()}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.outputStream.use { it.write("{\"key\":\"${key.replace("\"", "")}\"}".toByteArray()) }

        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()
        return UploadResult(status in 200..299, status, text)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
