package com.hitif.videodownloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hitif.videodownloader.R
import com.hitif.videodownloader.databinding.ItemHistoryBinding
import com.hitif.videodownloader.db.DownloadRecord
import com.hitif.videodownloader.model.DownloadState
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private val onDelete: (DownloadRecord) -> Unit,
    private val onRetry:  (DownloadRecord) -> Unit,
    private val onShare:  (DownloadRecord) -> Unit
) : ListAdapter<HistoryItem, RecyclerView.ViewHolder>(HistoryDiff) {

    companion object {
        const val TYPE_HEADER  = 0
        const val TYPE_RECORD  = 1
        private val DATE_FMT   = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    }

    inner class HeaderVH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)
    inner class RecordVH(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) =
        if (getItem(position) is HistoryItem.Header) TYPE_HEADER else TYPE_RECORD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return if (viewType == TYPE_HEADER) HeaderVH(b) else RecordVH(b)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HistoryItem.Header -> bindHeader(holder as HeaderVH, item)
            is HistoryItem.Record -> bindRecord(holder as RecordVH, item.record)
        }
    }

    private fun bindHeader(vh: HeaderVH, h: HistoryItem.Header) = with(vh.binding) {
        tvFilename.text = h.label
        tvFilename.setTextColor(ContextCompat.getColor(root.context, R.color.accent_cyan))
        tvDate.text     = ""
        tvSize.text     = ""
        tvState.text    = ""
        tvSpeed.visibility = View.GONE
        tvSeries.text   = ""
        progressBar.visibility = View.GONE
        btnDelete.visibility = View.GONE
        btnRetry.visibility  = View.GONE
        btnShare.visibility  = View.GONE
        root.alpha = 1f
        root.setBackgroundResource(R.drawable.bg_history_header)
    }

    private fun bindRecord(vh: RecordVH, r: DownloadRecord) = with(vh.binding) {
        tvFilename.text = r.filename
        tvFilename.setTextColor(
            ContextCompat.getColor(root.context, R.color.text_primary)
        )
        tvDate.text  = DATE_FMT.format(Date(r.startedAt))

        // State & active check (used in multiple places below)
        val isActive = r.stateEnum == DownloadState.QUEUED || r.stateEnum == DownloadState.DOWNLOADING
        val (stateText, stateColor) = when (r.stateEnum) {
            DownloadState.COMPLETED  -> "\u2713 COMPL\u00c9T\u00c9"  to R.color.status_success
            DownloadState.FAILED     -> "\u2717 \u00c9CHEC"     to R.color.status_error
            DownloadState.DOWNLOADING -> "\u2b07 EN COURS" to R.color.accent_cyan
            DownloadState.QUEUED     -> "\u23f3 EN FILE"  to R.color.accent_violet
            else                     -> "\u2014 INACTIF"   to R.color.text_dim
        }
        tvState.text = stateText
        tvState.setTextColor(ContextCompat.getColor(root.context, stateColor))

        // Show progressBytes when downloading, sizeBytes when completed
        tvSize.text = if (isActive && r.progressBytes > 0) {
            formatFileSize(r.progressBytes)
        } else {
            r.sizeLabel
        }
        tvSeries.text = r.seriesLabel ?: ""
        tvSeries.visibility = if (r.seriesLabel != null) View.VISIBLE else View.GONE

        // Speed display (only when downloading)
        if (isActive && r.speedBps > 0) {
            tvSpeed.text = formatSpeed(r.speedBps)
            tvSpeed.visibility = View.VISIBLE
        } else if (isActive && r.progressBytes > 0) {
            // Show downloaded size even without speed reading
            tvSpeed.text = formatFileSize(r.progressBytes)
            tvSpeed.visibility = View.VISIBLE
        } else {
            tvSpeed.visibility = View.GONE
        }

        // Progress bar
        if (isActive && (r.totalBytes > 0 || r.progressBytes > 0)) {
            val percent = if (r.totalBytes > 0) {
                ((r.progressBytes * 100) / r.totalBytes).toInt().coerceIn(0, 100)
            } else {
                // HLS: use speed as indicator that download is active
                0
            }
            if (percent > 0) {
                progressBar.progress = percent
                progressBar.visibility = View.VISIBLE
                tvState.text = "$stateText $percent%"
            } else {
                progressBar.isIndeterminate = true
                progressBar.visibility = View.VISIBLE
                tvState.text = stateText
            }
        } else {
            progressBar.visibility = View.GONE
        }

        root.alpha = if (r.stateEnum == DownloadState.FAILED) 0.6f else 1f
        root.setBackgroundResource(R.drawable.bg_card_item)

        btnDelete.visibility = View.VISIBLE
        btnRetry.visibility  = if (r.stateEnum == DownloadState.FAILED) View.VISIBLE else View.GONE
        btnShare.visibility  = View.VISIBLE

        btnDelete.setOnClickListener { onDelete(r) }
        btnRetry.setOnClickListener  { onRetry(r) }
        btnShare.setOnClickListener  { onShare(r) }
    }

    private fun formatSpeed(bps: Long): String = when {
        bps < 1024 -> "$bps B/s"
        bps < 1024 * 1024 -> String.format("%.1f KB/s", bps / 1024.0)
        else -> String.format("%.1f MB/s", bps / (1024.0 * 1024.0))
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f Ko", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f Mo", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f Go", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

// Sealed wrapper so we can show date-group headers
sealed class HistoryItem {
    data class Header(val label: String) : HistoryItem()
    data class Record(val record: DownloadRecord) : HistoryItem()
}

object HistoryDiff : DiffUtil.ItemCallback<HistoryItem>() {
    override fun areItemsTheSame(a: HistoryItem, b: HistoryItem) = when {
        a is HistoryItem.Header && b is HistoryItem.Header -> a.label == b.label
        a is HistoryItem.Record && b is HistoryItem.Record -> a.record.id == b.record.id
        else -> false
    }
    override fun areContentsTheSame(a: HistoryItem, b: HistoryItem) = a == b
}

/** Groups a flat list of DownloadRecord into HistoryItems with date headers */
fun List<DownloadRecord>.toHistoryItems(): List<HistoryItem> {
    if (isEmpty()) return emptyList()
    val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val result = mutableListOf<HistoryItem>()
    var lastDate = ""
    for (r in this) {
        val date = fmt.format(Date(r.startedAt))
        if (date != lastDate) {
            result += HistoryItem.Header(date)
            lastDate = date
        }
        result += HistoryItem.Record(r)
    }
    return result
}
