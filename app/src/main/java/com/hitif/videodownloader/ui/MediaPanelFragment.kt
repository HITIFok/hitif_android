package com.hitif.videodownloader.ui

import android.content.Intent
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
import com.hitif.videodownloader.databinding.FragmentMediaPanelBinding
import com.hitif.videodownloader.download.DownloadHelper
import com.hitif.videodownloader.model.MediaItem

class MediaPanelFragment : BottomSheetDialogFragment() {

    private var _b: FragmentMediaPanelBinding? = null
    private val b get() = _b!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: MediaAdapter

    override fun onCreateView(inf: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _b = FragmentMediaPanelBinding.inflate(inf, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.behavior?.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        setupRecyclerView()
        setupButtons()
        observeMedia()
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            onDownload = ::download,
            onDelete   = { vm.removeItem(it) },
            onShare    = ::share
        )
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerView.adapter = adapter
        b.recyclerView.itemAnimator = null
    }

    private fun setupButtons() {
        b.btnClose.setOnClickListener { dismiss() }

        b.btnClearAll.setOnClickListener {
            vm.clearMedia()
            Toast.makeText(requireContext(), "Liste vidée", Toast.LENGTH_SHORT).show()
        }

        b.btnDownloadAll.setOnClickListener {
            val items = vm.mediaItems.value.orEmpty()
            if (items.isEmpty()) {
                Toast.makeText(requireContext(), "Aucun média détecté", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            items.forEach { download(it) }
            Toast.makeText(requireContext(), "${items.size} téléchargement(s) lancé(s)", Toast.LENGTH_SHORT).show()
        }

        // Season button — opens season dialog for first detected group
        b.btnSeason.setOnClickListener {
            val groups = vm.seasonGroups.value ?: return@setOnClickListener
            val firstKey = groups.keys.firstOrNull() ?: return@setOnClickListener
            SeasonDialogFragment.newInstance(firstKey)
                .show(parentFragmentManager, "season_dialog")
        }
    }

    private fun observeMedia() {
        vm.mediaItems.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items.toList())
            val empty = items.isEmpty()
            b.emptyState.visibility   = if (empty) View.VISIBLE else View.GONE
            b.recyclerView.visibility = if (empty) View.GONE    else View.VISIBLE
            b.tvMediaCount.text = if (empty) "Aucun média détecté"
                                  else "${items.size} média(s) détecté(s)"
        }

        vm.seasonGroups.observe(viewLifecycleOwner) { groups ->
            b.btnSeason.visibility = if (groups.isNotEmpty()) View.VISIBLE else View.GONE
            if (groups.isNotEmpty()) {
                val count = groups.values.sumOf { it.size }
                b.btnSeason.text = "📺 ${groups.size} SAISON(S) · $count ÉP."
            }
        }
    }

    private fun download(item: MediaItem) {
        try {
            DownloadHelper.enqueue(requireContext(), item)
            Toast.makeText(requireContext(),
                "⬇ ${item.filename.take(32)}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun share(item: MediaItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, item.url)
            putExtra(Intent.EXTRA_SUBJECT, item.filename)
        }
        startActivity(Intent.createChooser(intent, "Partager le lien"))
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
    override fun getTheme() = R.style.Theme_BottomSheet_Holo
}
