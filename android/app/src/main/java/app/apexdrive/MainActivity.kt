package app.apexdrive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var serverField: EditText
    private lateinit var tokenField: EditText
    private val scope = CoroutineScope(Dispatchers.Main)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshSummary() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        serverField = findViewById(R.id.serverField)
        tokenField = findViewById(R.id.tokenField)

        val config = DriveConfig(this)
        serverField.setText(config.baseUrl)
        tokenField.setText(config.token)

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            config.baseUrl = serverField.text.toString().trim()
            config.token = tokenField.text.toString().trim()
            status.text = getString(R.string.saved)
            requestPermissionsIfNeeded()
            refreshSummary()
        }

        findViewById<Button>(R.id.uploadButton).setOnClickListener {
            enqueueUpload()
        }

        requestPermissionsIfNeeded()
        refreshSummary()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun refreshSummary() {
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                try {
                    val items = MediaScanner(this@MainActivity).scanImagesAndVideos(limit = 5000)
                    val total = items.sumOf { it.sizeBytes }
                    "${items.size} photos and videos, ${human(total)}"
                } catch (e: SecurityException) {
                    getString(R.string.need_permission)
                } catch (e: Exception) {
                    e.message ?: "scan failed"
                }
            }
            status.text = summary
        }
    }

    private fun enqueueUpload() {
        val config = DriveConfig(this)
        config.baseUrl = serverField.text.toString().trim()
        config.token = tokenField.text.toString().trim()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi only
                    .setRequiresCharging(true)                    // never eats battery on the move
                    .build()
            )
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork("apex-drive-sync", ExistingWorkPolicy.KEEP, request)

        status.text = getString(R.string.queued)
    }

    private fun human(bytes: Long): String {
        var value = bytes.toDouble()
        for (unit in listOf("B", "KB", "MB", "GB", "TB")) {
            if (value < 1024 || unit == "TB") return "%.1f %s".format(value, unit)
            value /= 1024
        }
        return "$bytes B"
    }
}
