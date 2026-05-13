package com.hitif.videodownloader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownloader.R
import com.hitif.videodownloader.db.AppDatabase
import com.hitif.videodownloader.download.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI-layer download record for the history list.
 * Maps from Room entity (com.hitif.videodownloader.db.DownloadRecord).
 */
data class UiDownloadRecord(
    val id: Long,
    val title: String,
    val state: String,          // "DOWNLOADING", "COMPLETED", "FAILED", "QUEUED", "PAUSED"
    val sizeBytes: Long,
    val progressBytes: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val dateMs: Long,
    val url: String,
    val mediaType: String
) {
    val displayBytes: Long get() = when {
        state == "DOWNLOADING" && progressBytes > 0 -> progressBytes
        sizeBytes > 0 -> sizeBytes
        progressBytes > 0 -> progressBytes
        else -> 0L
    }

    val percent: Int get() = when {
        totalBytes > 0 -> ((progressBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        sizeBytes > 0 && progressBytes > 0 -> ((progressBytes * 100) / sizeBytes).toInt().coerceIn(0, 100)
        else -> -1
    }

    val displaySizeLabel: String
        get() {
            val bytes = displayBytes
            return when {
                bytes <= 0 -> "0 KB"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }

    val isActive: Boolean get() = state == "DOWNLOADING" || state == "QUEUED"
    val isCompleted: Boolean get() = state == "COMPLETED"
    val isFailed: Boolean get() = state == "FAILED"
}

class DownloadHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DownloadsAdapter
    private lateinit var tvCount: TextView
    private lateinit var tvTotalSize: TextView
    private lateinit var tvStorage: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var emptyState: View

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_history)

        recyclerView = findViewById(R.id.recyclerDownloads)
        tvCount = findViewById(R.id.tvDownloadCount)
        tvTotalSize = findViewById(R.id.tvTotalSize)
        tvStorage = findViewById(R.id.tvStorageInfo)
        storageBar = findViewById(R.id.storageBar)
        emptyState = findViewById(R.id.emptyHistory)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = DownloadsAdapter(
            onOpen = { record -> openFile(record) },
            onDelete = { record -> confirmDelete(record) },
            onRetry = { record -> retryDownload(record) }
        )
        recyclerView.adapter = adapter

        findViewById<ImageView>(R.id.btnBackHistory).setOnClickListener { finish() }

        findViewById<ImageView>(R.id.btnClearHistory).setOnClickListener {
            if (adapter.itemCount > 0) {
                AlertDialog.Builder(this)
                    .setTitle("Supprimer tout")
                    .setMessage("Supprimer tous les telechargements de l'historique ?")
                    .setPositiveButton("Supprimer") { _, _ -> clearAllDownloads() }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }

        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        loadDownloads()
        startProgressPolling()
    }

    override fun onPause() {
        super.onPause()
        stopProgressPolling()
    }

    // ── Progress polling for active downloads ──────────────────────────

    private fun startProgressPolling() {
        stopProgressPolling()
        progressRunnable = object : Runnable {
            override fun run() {
                if (adapter.hasActiveDownloads()) {
                    loadDownloads()
                }
                handler.postDelayed(this, 1500)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    // ── Data loading ────────────────────────────────────────────────────

    private fun loadDownloads() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@DownloadHistoryActivity)
            val roomRecords = db.downloadDao().getRecent(200)

            val uiRecords = roomRecords.map { r ->
                UiDownloadRecord(
                    id = r.id,
                    title = r.filename.ifBlank { r.pageTitle.ifBlank { "Fichier inconnu" } },
                    state = r.state,
                    sizeBytes = r.sizeBytes,
                    progressBytes = r.progressBytes,
                    totalBytes = r.totalBytes,
                    speedBps = r.speedBps,
                    dateMs = r.completedAt ?: r.startedAt,
                    url = r.url,
                    mediaType = r.mediaType
                )
            }

            // Sort: active first (DOWNLOADING > QUEUED > COMPLETED > FAILED)
            val sorted = uiRecords.sortedWith(compareByDescending<UiDownloadRecord> {
                when (it.state) {
                    "DOWNLOADING" -> 5
                    "QUEUED" -> 4
                    "COMPLETED" -> 2
                    else -> 1
                }
            }.thenByDescending { it.dateMs })

            runOnUiThread {
                adapter.submitList(sorted)

                if (sorted.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyState.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }

                tvCount.text = "${sorted.size} fichier(s)"
                val totalMB = sorted.sumOf { it.displayBytes } / (1024.0 * 1024.0)
                tvTotalSize.text = if (totalMB >= 1024) String.format("%.1f GB", totalMB / 1024) else String.format("%.1f MB", totalMB)

                updateStorageInfo()
            }
        }
    }

    private fun updateStorageInfo() {
        val availableMB = DownloadHelper.getAvailableStorageMB(this)
        val totalMB = DownloadHelper.getTotalStorageMB(this)

        if (availableMB < 0 || totalMB < 0) {
            tvStorage.text = "Stockage: inconnu"
            tvStorage.setTextColor(0xFF506070.toInt())
            storageBar.progress = 0
            return
        }

        val availStr = if (availableMB >= 1024) String.format("%.1f GB", availableMB / 1024.0)
                       else "${availableMB} MB"
        val totalStr = if (totalMB >= 1024) String.format("%.1f GB", totalMB / 1024.0)
                       else "${totalMB} MB"

        val usedPercent = ((totalMB - availableMB) * 100 / totalMB).toInt().coerceIn(0, 100)
        tvStorage.text = "$availStr libres / $totalStr ($usedPercent% utilise)"

        storageBar.progress = usedPercent

        val color = when {
            availableMB < 100 -> 0xFFFF4444.toInt()   // < 100 MB: red
            availableMB < 500 -> 0xFFFFAA00.toInt()   // < 500 MB: yellow
            else -> 0xFF00E887.toInt()                 // OK: green
        }
        tvStorage.setTextColor(color)
        storageBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    // ── Actions ────────────────────────────────────────────────────────

    private fun openFile(record: UiDownloadRecord) {
        if (!record.isCompleted) {
            Toast.makeText(this, "Ce fichier n'est pas encore telecharge", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Try to find the file in the downloads directory
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val hitifDir = File(downloadsDir, "HITIF")
            val file = findFile(hitifDir, record.title)

            if (file != null && file.exists()) {
                val uri = Uri.fromFile(file)
                val mimeType = when {
                    record.mediaType.contains("audio") -> "audio/*"
                    record.mediaType.contains("video") || record.title.endsWith(".mp4") ||
                    record.title.endsWith(".mkv") || record.title.endsWith(".webm") -> "video/*"
                    else -> "*/*"
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Fichier introuvable sur l'appareil", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Aucune application pour ouvrir ce fichier", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findFile(dir: File, filename: String): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        // Direct match
        val direct = File(dir, filename)
        if (direct.exists()) return direct
        // Recursive search (limited depth)
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFile(child, filename)
                if (found != null) return found
            } else if (child.name.equals(filename, ignoreCase = true)) {
                return child
            }
        }
        return null
    }

    private fun confirmDelete(record: UiDownloadRecord) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer")
            .setMessage("Supprimer '${record.title}' de l'historique ?")
            .setPositiveButton("Supprimer") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getInstance(this@DownloadHistoryActivity)
                    db.downloadDao().deleteById(record.id)
                    loadDownloads()
                }
                Toast.makeText(this, "Supprime", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun retryDownload(record: UiDownloadRecord) {
        if (!record.isFailed) return
        AlertDialog.Builder(this)
            .setTitle("Reessayer")
            .setMessage("Reessayer le telechargement de '${record.title}' ?")
            .setPositiveButton("Reessayer") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getInstance(this@DownloadHistoryActivity)
                    db.downloadDao().deleteById(record.id)
                }
                Toast.makeText(this, "Supprime de l'historique. Re-telechargez depuis la page.", Toast.LENGTH_LONG).show()
                loadDownloads()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun clearAllDownloads() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@DownloadHistoryActivity)
            db.downloadDao().deleteAll()
            loadDownloads()
        }
        Toast.makeText(this, "Historique vide", Toast.LENGTH_SHORT).show()
    }

    // ── Adapter ────────────────────────────────────────────────────────

    class DownloadsAdapter(
        private val onOpen: (UiDownloadRecord) -> Unit,
        private val onDelete: (UiDownloadRecord) -> Unit,
        private val onRetry: (UiDownloadRecord) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

        private val items = mutableListOf<UiDownloadRecord>()
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        fun submitList(list: List<UiDownloadRecord>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun hasActiveDownloads(): Boolean = items.any { it.isActive }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download_history, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val ivStatus = view.findViewById<ImageView>(R.id.ivStatus)
            private val tvFileName = view.findViewById<TextView>(R.id.tvFileName)
            private val tvFileSize = view.findViewById<TextView>(R.id.tvFileSize)
            private val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
            private val tvDate = view.findViewById<TextView>(R.id.tvDate)
            private val btnOpen = view.findViewById<ImageView>(R.id.btnOpenFile)
            private val btnDelete = view.findViewById<ImageView>(R.id.btnDeleteFile)
            private val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)

            fun bind(record: UiDownloadRecord) {
                tvFileName.text = record.title

                // File size
                tvFileSize.text = when {
                    record.isActive && record.percent >= 0 ->
                        "${record.displaySizeLabel} / ${formatSize(record.totalBytes.coerceAtLeast(record.sizeBytes))}"
                    record.displayBytes <= 0 -> "---"
                    else -> record.displaySizeLabel
                }

                // Status icon and label
                when (record.state) {
                    "DOWNLOADING" -> {
                        tvStatus.text = if (record.percent >= 0) "${record.percent}%" else "En cours..."
                        tvStatus.setTextColor(0xFF00E5FF.toInt())
                        progressBar.visibility = View.VISIBLE
                        ivStatus.setImageResource(R.drawable.ic_download)
                        ivStatus.alpha = 1f
                    }
                    "QUEUED" -> {
                        tvStatus.text = "En attente"
                        tvStatus.setTextColor(0xFFFFAA00.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(R.drawable.ic_download)
                        ivStatus.alpha = 0.5f
                    }
                    "PAUSED" -> {
                        tvStatus.text = "En pause"
                        tvStatus.setTextColor(0xFFFFAA00.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(R.drawable.ic_download)
                        ivStatus.alpha = 0.5f
                    }
                    "FAILED" -> {
                        tvStatus.text = "Echoue"
                        tvStatus.setTextColor(0xFFFF4444.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(android.R.drawable.ic_dialog_alert)
                        ivStatus.alpha = 1f
                    }
                    "COMPLETED" -> {
                        tvStatus.text = "Termine"
                        tvStatus.setTextColor(0xFF00E887.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(android.R.drawable.stat_sys_download_done)
                        ivStatus.alpha = 1f
                    }
                    else -> {
                        tvStatus.text = record.state
                        tvStatus.setTextColor(0xFF506070.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(R.drawable.ic_download)
                        ivStatus.alpha = 0.3f
                    }
                }

                // Progress bar for active downloads
                if (record.isActive && record.percent >= 0) {
                    progressBar.progress = record.percent
                    progressBar.isIndeterminate = false
                } else {
                    progressBar.visibility = View.GONE
                }

                tvDate.text = dateFormat.format(Date(record.dateMs))

                btnOpen.isEnabled = record.isCompleted
                btnOpen.alpha = if (btnOpen.isEnabled) 1f else 0.35f
                btnOpen.setOnClickListener { onOpen(record) }
                btnDelete.setOnClickListener { onDelete(record) }
            }
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes <= 0 -> "?"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}
