package com.hitif.videodownloader.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownloader.R
import com.hitif.videodownloader.download.DownloadHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DownloadRecord(
    val id: Long,
    val title: String,
    val status: Int,
    val sizeBytes: Long,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val dateMs: Long,
    val uri: Uri,
    val mediaType: String
) {
    /** Display size: downloaded so far for running, total for others */
    val displayBytes: Long get() = when {
        status == DownloadManager.STATUS_RUNNING && bytesDownloaded > 0 -> bytesDownloaded
        status == DownloadManager.STATUS_PENDING && bytesDownloaded > 0 -> bytesDownloaded
        sizeBytes > 0 -> sizeBytes
        else -> bytesDownloaded
    }

    /** Progress percentage, -1 if unknown */
    val percent: Int get() = when {
        totalBytes > 0 -> ((bytesDownloaded * 100) / totalBytes).toInt()
        else -> -1
    }

    /** Human-readable display size */
    val displaySizeLabel: String
        get() {
            val bytes = displayBytes
            return when {
                bytes <= 0 -> "0 KB"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }

    /** Progress label with percentage for running downloads */
    val progressLabel: String
        get() = when (status) {
            DownloadManager.STATUS_RUNNING -> if (percent >= 0) "$percent%" else "En cours..."
            DownloadManager.STATUS_PENDING -> "En attente"
            DownloadManager.STATUS_PAUSED -> "En pause"
            DownloadManager.STATUS_FAILED -> "Echoue"
            DownloadManager.STATUS_SUCCESSFUL -> "Termine"
            else -> ""
        }
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
    private var downloadObserver: ContentObserver? = null

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
        registerDownloadObserver()
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

    override fun onDestroy() {
        super.onDestroy()
        stopProgressPolling()
        unregisterDownloadObserver()
    }

    // ── ContentObserver for real-time updates ───────────────────────────

    private fun registerDownloadObserver() {
        downloadObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                runOnUiThread { loadDownloads() }
            }
        }
        contentResolver.registerContentObserver(
            Uri.parse("content://downloads/my_downloads"),
            true,
            downloadObserver!!
        )
    }

    private fun unregisterDownloadObserver() {
        downloadObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
    }

    // ── Progress polling for running downloads ──────────────────────────

    private fun startProgressPolling() {
        stopProgressPolling()
        progressRunnable = object : Runnable {
            override fun run() {
                if (adapter.hasActiveDownloads()) {
                    loadDownloads()
                }
                handler.postDelayed(this, 1500) // refresh every 1.5s
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
        val records = queryDownloads()
        adapter.submitList(records)

        if (records.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        // Count and total size
        tvCount.text = "${records.size} fichier(s)"
        val totalMB = records.sumOf { it.displayBytes } / (1024.0 * 1024.0)
        tvTotalSize.text = if (totalMB >= 1024) String.format("%.1f GB", totalMB / 1024) else String.format("%.1f MB", totalMB)

        // Storage info
        updateStorageInfo()
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

        // Update storage bar
        storageBar.progress = usedPercent

        // Color coding: red if critically low, yellow if moderate, green if OK
        val color = when {
            availableMB < 100 -> 0xFFFF4444.toInt()   // < 100 MB: red
            availableMB < 500 -> 0xFFFFAA00.toInt()   // < 500 MB: yellow
            else -> 0xFF00E887.toInt()                 // OK: green
        }
        tvStorage.setTextColor(color)
        storageBar.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun queryDownloads(): List<DownloadRecord> {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val records = mutableListOf<DownloadRecord>()

        // Include ALL statuses so we can show failed downloads too
        val query = DownloadManager.Query()
            .setFilterByStatus(
                DownloadManager.STATUS_SUCCESSFUL or
                DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PENDING or
                DownloadManager.STATUS_PAUSED or
                DownloadManager.STATUS_FAILED
            )

        val cursor: Cursor? = dm.query(query)

        cursor?.use { c ->
            val colId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val colTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val colStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val colSize = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val colDownloaded = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val colDate = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            val colUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val colMedia = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)

            while (c.moveToNext()) {
                val id = c.getLong(colId)
                val title = c.getString(colTitle) ?: "Fichier inconnu"
                val status = c.getInt(colStatus)
                val size = c.getLong(colSize)
                val downloaded = c.getLong(colDownloaded)
                val date = c.getLong(colDate)
                val uriStr = c.getString(colUri)
                val mediaType = c.getString(colMedia) ?: ""

                if (uriStr != null) {
                    val uri = Uri.parse(uriStr)
                    records.add(DownloadRecord(
                        id, title, status, size, downloaded, totalBytes = size,
                        dateMs = date, uri, mediaType
                    ))
                }
            }
        }

        // Sort: running first, then pending, then paused, then success, then failed
        return records.sortedWith(compareByDescending<DownloadRecord> {
            when (it.status) {
                DownloadManager.STATUS_RUNNING -> 5
                DownloadManager.STATUS_PENDING -> 4
                DownloadManager.STATUS_PAUSED -> 3
                DownloadManager.STATUS_SUCCESSFUL -> 2
                DownloadManager.STATUS_FAILED -> 1
                else -> 0
            }
        }.thenByDescending { it.dateMs })
    }

    // ── Actions ────────────────────────────────────────────────────────

    private fun openFile(record: DownloadRecord) {
        if (record.status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, "Ce fichier n'est pas encore telecharge", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(record.uri, record.mediaType.ifBlank { "video/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Aucune application pour ouvrir ce fichier", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(record: DownloadRecord) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer")
            .setMessage("Supprimer '${record.title}' ?")
            .setPositiveButton("Supprimer") { _, _ ->
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(record.id)
                loadDownloads()
                Toast.makeText(this, "Supprime", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun retryDownload(record: DownloadRecord) {
        if (record.status != DownloadManager.STATUS_FAILED) return
        AlertDialog.Builder(this)
            .setTitle("Reessayer")
            .setMessage("Reessayer le telechargement de '${record.title}' ?")
            .setPositiveButton("Reessayer") { _, _ ->
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(record.id)
                Toast.makeText(this, "Supprime de l'historique. Re-telechargez depuis la page.", Toast.LENGTH_LONG).show()
                loadDownloads()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun clearAllDownloads() {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query()
        val cursor: Cursor? = dm.query(query)
        cursor?.use { c ->
            val colId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (c.moveToNext()) {
                dm.remove(c.getLong(colId))
            }
        }
        loadDownloads()
        Toast.makeText(this, "Historique vide", Toast.LENGTH_SHORT).show()
    }

    // ── Adapter ────────────────────────────────────────────────────────

    class DownloadsAdapter(
        private val onOpen: (DownloadRecord) -> Unit,
        private val onDelete: (DownloadRecord) -> Unit,
        private val onRetry: (DownloadRecord) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

        private val items = mutableListOf<DownloadRecord>()
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        fun submitList(list: List<DownloadRecord>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun hasActiveDownloads(): Boolean {
            return items.any {
                it.status == DownloadManager.STATUS_RUNNING ||
                it.status == DownloadManager.STATUS_PENDING
            }
        }

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

            fun bind(record: DownloadRecord) {
                tvFileName.text = record.title

                // File size — show downloaded so far for active downloads
                tvFileSize.text = when {
                    record.status == DownloadManager.STATUS_RUNNING && record.percent >= 0 ->
                        "${record.displaySizeLabel} / ${formatSize(record.totalBytes)}"
                    record.displayBytes <= 0 -> "---"
                    else -> record.displaySizeLabel
                }

                // Status icon and label
                when (record.status) {
                    DownloadManager.STATUS_RUNNING -> {
                        tvStatus.text = if (record.percent >= 0) "${record.percent}%" else "En cours..."
                        tvStatus.setTextColor(0xFF00E5FF.toInt())
                        progressBar.visibility = View.VISIBLE
                        ivStatus.setImageResource(R.drawable.ic_download)
                        ivStatus.alpha = 1f
                    }
                    DownloadManager.STATUS_PENDING -> {
                        tvStatus.text = "En attente"
                        tvStatus.setTextColor(0xFFFFAA00.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(R.drawable.ic_download)
                        ivStatus.alpha = 0.5f
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        tvStatus.text = "En pause"
                        tvStatus.setTextColor(0xFFFFAA00.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(R.drawable.ic_download)
                        ivStatus.alpha = 0.5f
                    }
                    DownloadManager.STATUS_FAILED -> {
                        tvStatus.text = "Echoue"
                        tvStatus.setTextColor(0xFFFF4444.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(android.R.drawable.ic_dialog_alert)
                        ivStatus.alpha = 1f
                    }
                    else -> {
                        // STATUS_SUCCESSFUL or unknown
                        tvStatus.text = "Termine"
                        tvStatus.setTextColor(0xFF00E887.toInt())
                        progressBar.visibility = View.GONE
                        ivStatus.setImageResource(android.R.drawable.stat_sys_download_done)
                        ivStatus.alpha = 1f
                    }
                }

                // Update progress bar for running downloads
                if (record.status == DownloadManager.STATUS_RUNNING && record.percent >= 0) {
                    progressBar.progress = record.percent
                    progressBar.isIndeterminate = false
                }

                // Date
                tvDate.text = dateFormat.format(Date(record.dateMs))

                // Open button: only enabled for completed downloads
                btnOpen.isEnabled = record.status == DownloadManager.STATUS_SUCCESSFUL
                btnOpen.alpha = if (btnOpen.isEnabled) 1f else 0.35f
                btnOpen.setOnClickListener { onOpen(record) }
                btnDelete.setOnClickListener { onDelete(record) }
            }
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes <= 0 -> "?"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }
    }
}
