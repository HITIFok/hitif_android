package com.hitif.videodownloader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.hitif.videodownloader.R
import com.hitif.videodownloader.databinding.FragmentHistoryBinding
import com.hitif.videodownloader.db.DownloadRecord
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.model.MediaType
import com.hitif.videodownloader.download.DownloadHelper
import com.hitif.videodownloader.download.DownloadNotificationManager

class HistoryFragment : BottomSheetDialogFragment() {

    private var _b: FragmentHistoryBinding? = null
    private val b get() = _b!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentHistoryBinding.inflate(inf, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.behavior?.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        setupList()
        setupButtons()
        observeHistory()
    }

    private fun setupList() {
        adapter = HistoryAdapter(
            onDelete = { record ->
                // Dismiss notification if download is still active
                try { DownloadNotificationManager.dismiss(record.url) } catch (_: Exception) {}
                vm.deleteHistoryRecord(record)
                Toast.makeText(requireContext(), "Supprimé de l'historique", Toast.LENGTH_SHORT).show()
            },
            onRetry  = { record ->
                // Re-enqueue as a MediaItem rebuilt from the record
                val item = record.toMediaItem()
                DownloadHelper.enqueue(requireContext(), item)
                Toast.makeText(requireContext(), "⬇ Relancé : ${record.filename.take(32)}", Toast.LENGTH_SHORT).show()
            },
            onShare  = { record ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, record.url)
                    putExtra(Intent.EXTRA_SUBJECT, record.filename)
                }
                startActivity(Intent.createChooser(intent, "Partager le lien"))
            }
        )
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter
        b.recyclerView.itemAnimator = null
    }

    private fun setupButtons() {
        b.btnClose.setOnClickListener { dismiss() }
        b.btnClearAll.setOnClickListener {
            try { DownloadNotificationManager.dismissAll() } catch (_: Exception) {}
            vm.clearHistory()
            Toast.makeText(requireContext(), "Historique effacé", Toast.LENGTH_SHORT).show()
        }
        b.btnClearFailed.setOnClickListener {
            vm.clearFailedHistory()
            Toast.makeText(requireContext(), "Échecs supprimés", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeHistory() {
        vm.downloadHistory.observe(viewLifecycleOwner) { records ->
            val items = records.toHistoryItems()
            adapter.submitList(items)
            val empty = records.isEmpty()
            b.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            b.recyclerView.visibility = if (empty) View.GONE  else View.VISIBLE
            b.tvCount.text = if (empty) "Aucun téléchargement"
                             else "${records.size} téléchargement(s)"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
    override fun getTheme() = R.style.Theme_BottomSheet_Holo
}

private fun DownloadRecord.toMediaItem() = MediaItem(
    url        = url,
    filename   = filename,
    mimeType   = mimeType,
    mediaType  = mediaTypeEnum,
    pageTitle  = pageTitle,
    pageUrl    = pageUrl,
    sizeBytes  = sizeBytes
)
