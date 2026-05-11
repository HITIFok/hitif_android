package com.hitif.videodownloader.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DownloadRecord(
    val id: Long,
    val title: String,
    val status: Int,
    val sizeBytes: Long,
    val dateMs: Long,
    val uri: Uri,
    val mediaType: String
)

class DownloadHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DownloadsAdapter
    private lateinit var tvCount: TextView
    private lateinit var tvTotalSize: TextView
    private lateinit var emptyState: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_history)

        recyclerView = findViewById(R.id.recyclerDownloads)
        tvCount = findViewById(R.id.tvDownloadCount)
        tvTotalSize = findViewById(R.id.tvTotalSize)
        emptyState = findViewById(R.id.emptyHistory)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = DownloadsAdapter(
            onOpen = { record -> openFile(record) },
            onDelete = { record -> confirmDelete(record) }
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
    }

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

        tvCount.text = "${records.size} fichier(s)"
        val totalMB = records.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
        tvTotalSize.text = if (totalMB >= 1024) String.format("%.1f GB", totalMB / 1024) else String.format("%.1f MB", totalMB)
    }

    private fun queryDownloads(): List<DownloadRecord> {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val records = mutableListOf<DownloadRecord>()

        val query = DownloadManager.Query()
            .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_PAUSED)

        val cursor: Cursor? = dm.query(query)

        cursor?.use { c ->
            val colId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val colTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
            val colStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val colSize = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val colDate = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            val colUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val colMedia = c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)

            while (c.moveToNext()) {
                val id = c.getLong(colId)
                val title = c.getString(colTitle) ?: "Fichier inconnu"
                val status = c.getInt(colStatus)
                val size = c.getLong(colSize)
                val date = c.getLong(colDate)
                val uriStr = c.getString(colUri)
                val mediaType = c.getString(colMedia) ?: ""

                if (uriStr != null) {
                    val uri = Uri.parse(uriStr)
                    records.add(DownloadRecord(id, title, status, size, date, uri, mediaType))
                }
            }
        }

        return records
    }

    private fun openFile(record: DownloadRecord) {
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

    // ── Adapter ───────────────────────────────────────────────────────────────

    class DownloadsAdapter(
        private val onOpen: (DownloadRecord) -> Unit,
        private val onDelete: (DownloadRecord) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

        private val items = mutableListOf<DownloadRecord>()
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        fun submitList(list: List<DownloadRecord>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
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

                // File size
                tvFileSize.text = when {
                    record.sizeBytes <= 0 -> "---"
                    record.sizeBytes < 1024 * 1024 -> "${record.sizeBytes / 1024} KB"
                    else -> String.format("%.1f MB", record.sizeBytes / (1024.0 * 1024.0))
                }

                // Status
                when (record.status) {
                    DownloadManager.STATUS_RUNNING -> {
                        tvStatus.text = "En cours..."
                        tvStatus.setTextColor(0xFF00E5FF.toInt())
                        progressBar.visibility = View.VISIBLE
                    }
                    DownloadManager.STATUS_PENDING -> {
                        tvStatus.text = "En attente"
                        tvStatus.setTextColor(0xFFFFAA00.toInt())
                        progressBar.visibility = View.GONE
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        tvStatus.text = "En pause"
                        tvStatus.setTextColor(0xFFFFAA00.toInt())
                        progressBar.visibility = View.GONE
                    }
                    else -> {
                        tvStatus.text = "Termine"
                        tvStatus.setTextColor(0xFF00E887.toInt())
                        progressBar.visibility = View.GONE
                    }
                }

                // Date
                tvDate.text = dateFormat.format(Date(record.dateMs))

                btnOpen.setOnClickListener { onOpen(record) }
                btnDelete.setOnClickListener { onDelete(record) }
            }
        }
    }
}
