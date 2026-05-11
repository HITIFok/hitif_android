package com.hitif.videodownloader.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.hitif.videodownloader.R
import com.hitif.videodownloader.databinding.ActivityBrowserBinding
import com.hitif.videodownloader.network.JsBridge
import com.hitif.videodownloader.network.JsInterface
import com.hitif.videodownloader.util.SmartNamer

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private val vm: BrowserViewModel by viewModels()
    private var mediaPanel: MediaPanelFragment? = null

    companion object {
        const val HOME_URL = "https://www.google.com"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityBrowserBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupWebView()
            setupAddressBar()
            setupToolbar()
            setupObservers()
            setupBackPress()

            // Handle external URL intent
            val url = intent?.data?.toString() ?: savedInstanceState?.getString("url") ?: HOME_URL
            binding.webView.loadUrl(url)
        } catch (e: Exception) {
            Log.e("HITIF", "onCreate crash", e)
            Toast.makeText(this, "Erreur init: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── WebView ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        // JS bridge (catches blob:, MSE, etc.)
        wv.addJavascriptInterface(
            JsInterface(vm.detector, vm.pageUrl, vm.pageTitle),
            JsBridge.INTERFACE_NAME
        )

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                vm.detector.analyse(request,
                    vm.pageUrl.value ?: "",
                    vm.pageTitle.value ?: "")
                return null   // let the browser handle it normally
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                vm.isLoading.postValue(true)
                vm.clearMedia()
                binding.addressBar.setText(url)
                binding.progressBar.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                vm.isLoading.postValue(false)
                vm.pageUrl.postValue(url)
                vm.canGoBack.postValue(view.canGoBack())
                vm.canGoForward.postValue(view.canGoForward())
                binding.progressBar.isVisible = false

                // Inject JS bridge script
                view.evaluateJavascript(JsBridge.INJECT_SCRIPT, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest,
                                         error: WebResourceError) {
                if (request.isForMainFrame) {
                    vm.isLoading.postValue(false)
                    binding.progressBar.isVisible = false
                }
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrlLoading(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView,
                                                  request: WebResourceRequest): Boolean {
                return handleUrlLoading(request.url.toString())
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                vm.pageTitle.postValue(title)
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
            }
        }
    }

    private fun handleUrlLoading(url: String): Boolean {
        // Let intent: / market: / mailto: etc. go to system
        return when {
            url.startsWith("http") || url.startsWith("https") -> false
            else -> {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
                catch (e: Exception) { true }
            }
        }
    }

    // ── Address bar ──────────────────────────────────────────────────────────

    private fun setupAddressBar() {
        binding.addressBar.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigate(v.text.toString())
                true
            } else false
        }

        binding.btnGo.setOnClickListener {
            navigate(binding.addressBar.text.toString())
        }

        // Clear & focus
        binding.addressBar.setOnFocusChangeListener { v, focused ->
            if (focused) binding.addressBar.selectAll()
        }
    }

    private fun navigate(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains('.') && !input.contains(' ') -> "https://$input"
            else -> "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        binding.webView.loadUrl(url)
        binding.addressBar.clearFocus()
    }

    // ── Bottom toolbar ───────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnBack.setOnLongClickListener {
            binding.webView.copyBackForwardList().let { list ->
                // Could show history sheet — for now just go home
                binding.webView.loadUrl(HOME_URL); true
            }
        }

        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }

        binding.btnRefresh.setOnClickListener {
            if (vm.isLoading.value == true) binding.webView.stopLoading()
            else binding.webView.reload()
        }

        binding.btnHome.setOnClickListener {
            binding.webView.loadUrl(HOME_URL)
        }

        binding.btnMedia.setOnClickListener {
            toggleMediaPanel()
        }

        // Long press on home button opens download history
        binding.btnHome.setOnLongClickListener {
            startActivity(Intent(this, DownloadHistoryActivity::class.java))
            true
        }
    }

    fun showSeasonDownloadDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_season_download, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Telecharger une Saison")
            .setView(view)
            .create()

        val etEpisodeUrl = view.findViewById<android.widget.EditText>(R.id.etEpisodeUrl)
        val etEpStart = view.findViewById<android.widget.EditText>(R.id.etEpStart)
        val etEpEnd = view.findViewById<android.widget.EditText>(R.id.etEpEnd)
        val tvTotal = view.findViewById<android.widget.TextView>(R.id.tvTotalEpisodes)
        val tvPattern = view.findViewById<android.widget.TextView>(R.id.tvDetectedPattern)
        val tvPreview = view.findViewById<android.widget.TextView>(R.id.tvNamePreview)
        val spinDelay = view.findViewById<android.widget.Spinner>(R.id.spinDelay)

        // Delay spinner options
        val delayOptions = arrayOf("5s", "8s", "12s", "20s", "30s")
        val delayValues = intArrayOf(5, 8, 12, 20, 30)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, delayOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinDelay.adapter = adapter
        spinDelay.setSelection(1) // 8s default

        // Auto-fill current URL
        val currentUrl = binding.webView.url
        if (currentUrl != null) {
            etEpisodeUrl.setText(currentUrl)
        }

        fun updatePreview() {
            val rawUrl = etEpisodeUrl.text.toString().trim()
            if (rawUrl.isBlank() || !rawUrl.startsWith("http")) {
                tvPattern.visibility = android.view.View.GONE
                tvPreview.visibility = android.view.View.GONE
                return
            }
            val pattern = SmartNamer.detectPattern(rawUrl)
            if (pattern.contains("{N}")) {
                tvPattern.text = "Pattern: $pattern"
                tvPattern.visibility = android.view.View.VISIBLE
                val startEp = etEpStart.text.toString().toIntOrNull() ?: 1
                val naming = SmartNamer.smartName(pattern, startEp)
                if (naming.animeName.isNotBlank()) {
                    tvPreview.text = "\uD83D\uDCC1 ${naming.basename}"
                    tvPreview.visibility = android.view.View.VISIBLE
                } else {
                    tvPreview.visibility = android.view.View.GONE
                }
            } else {
                tvPattern.text = "Pattern non detecte - utilisez une URL avec un numero d'episode"
                tvPattern.setTextColor(0xFFFF4444.toInt())
                tvPattern.visibility = android.view.View.VISIBLE
                tvPreview.visibility = android.view.View.GONE
            }
        }

        fun updateTotal() {
            val e1 = etEpStart.text.toString().toIntOrNull() ?: 1
            val e2 = etEpEnd.text.toString().toIntOrNull() ?: 24
            val total = if (e2 >= e1) (e2 - e1 + 1) else 0
            tvTotal.text = "$total episode(s) au total"
            updatePreview()
        }

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateTotal() }
        }
        etEpisodeUrl.addTextChangedListener(watcher)
        etEpStart.addTextChangedListener(watcher)
        etEpEnd.addTextChangedListener(watcher)
        updateTotal()

        view.findViewById<android.widget.Button>(R.id.btnCancelSeason).setOnClickListener { dialog.dismiss() }

        // Test button: open episode in browser
        view.findViewById<android.widget.Button>(R.id.btnTestUrl).setOnClickListener {
            val rawUrl = etEpisodeUrl.text.toString().trim()
            val pattern = SmartNamer.detectPattern(rawUrl)
            if (pattern.contains("{N}")) {
                val startEp = etEpStart.text.toString().toIntOrNull() ?: 1
                val testUrl = pattern.replace("{N}", startEp.toString())
                binding.webView.loadUrl(testUrl)
                dialog.dismiss()
                Toast.makeText(this, "Episode $startEp ouvert", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Impossible de detecter le pattern", Toast.LENGTH_SHORT).show()
            }
        }

        // Start button: queue all downloads with smart naming
        view.findViewById<android.widget.Button>(R.id.btnStartSeason).setOnClickListener {
            val rawUrl = etEpisodeUrl.text.toString().trim()
            val pattern = SmartNamer.detectPattern(rawUrl)
            if (!pattern.contains("{N}")) {
                Toast.makeText(this, "Impossible de detecter le pattern. Collez une URL d'episode.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val e1 = etEpStart.text.toString().toIntOrNull() ?: 1
            val e2 = etEpEnd.text.toString().toIntOrNull() ?: 24
            if (e2 < e1) {
                Toast.makeText(this, "L'episode de fin doit etre >= au debut", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (e2 - e1 > 200) {
                Toast.makeText(this, "Maximum 200 episodes a la fois", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val delayMs = delayValues[spinDelay.selectedItemPosition].toLong() * 1000

            var count = 0
            for (ep in e1..e2) {
                val url = pattern.replace("{N}", ep.toString())
                val naming = SmartNamer.smartName(pattern, ep)
                val filename = if (naming.animeName.isNotBlank()) {
                    "${naming.basename}.mp4"
                } else {
                    "Episode_${ep.toString().padStart(2, '0')}.mp4"
                }
                val item = com.hitif.videodownloader.model.MediaItem(
                    url = url,
                    filename = filename,
                    mediaType = com.hitif.videodownloader.model.MediaType.VIDEO,
                    pageUrl = rawUrl
                )
                try {
                    com.hitif.videodownloader.download.DownloadHelper.enqueue(this, item)
                    count++
                } catch (_: Exception) {}
            }
            dialog.dismiss()
            Toast.makeText(this, "$count telechargement(s) lance(s)", Toast.LENGTH_LONG).show()
        }

        dialog.show()
    }

    fun showCustomFilenameDialog(item: com.hitif.videodownloader.model.MediaItem) {
        val view = layoutInflater.inflate(R.layout.dialog_custom_filename, null)
        val etFileName = view.findViewById<android.widget.EditText>(R.id.etFileName)
        val tvExt = view.findViewById<android.widget.TextView>(R.id.tvExtension)

        etFileName.setText(item.filename.substringBeforeLast('.', item.filename))
        tvExt.text = "Extension: .${item.filename.substringAfterLast('.', "mp4")}"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Renommer le fichier")
            .setView(view)
            .create()

        view.findViewById<android.widget.Button>(R.id.btnCancelName).setOnClickListener { dialog.dismiss() }
        view.findViewById<android.widget.Button>(R.id.btnConfirmName).setOnClickListener {
            val customName = etFileName.text.toString().trim()
            if (customName.isBlank()) {
                Toast.makeText(this, "Nom invalide", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ext = item.filename.substringAfterLast('.', "mp4")
            val renamedItem = item.copy(
                filename = "$customName.$ext"
            )
            try {
                com.hitif.videodownloader.download.DownloadHelper.enqueue(this, renamedItem)
                Toast.makeText(this, "Telechargement lance : $customName.$ext", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun toggleMediaPanel() {
        val existing = supportFragmentManager.findFragmentByTag("media_panel")
        if (existing != null) {
            (existing as? MediaPanelFragment)?.dismiss()
        } else {
            MediaPanelFragment().also {
                mediaPanel = it
                it.show(supportFragmentManager, "media_panel")
            }
        }
    }

    // ── LiveData observers ───────────────────────────────────────────────────

    private fun setupObservers() {
        vm.isLoading.observe(this) { loading ->
            binding.btnRefresh.setImageResource(
                if (loading) R.drawable.ic_stop else R.drawable.ic_refresh
            )
        }
        vm.canGoBack.observe(this) { binding.btnBack.alpha = if (it) 1f else 0.35f }
        vm.canGoForward.observe(this) { binding.btnForward.alpha = if (it) 1f else 0.35f }
        vm.pageTitle.observe(this) { title ->
            supportActionBar?.title = title
        }
        vm.mediaItems.observe(this) { items ->
            val count = items.size
            binding.btnMediaBadge.text = if (count > 0) count.toString() else ""
            binding.btnMediaBadge.isVisible = count > 0
            // Pulse the media button when new media found
            if (count > 0) pulseMediaButton()
        }
    }

    private fun pulseMediaButton() {
        binding.btnMedia.animate()
            .scaleX(1.25f).scaleY(1.25f).setDuration(120)
            .withEndAction {
                binding.btnMedia.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
    }

    // ── Back press ───────────────────────────────────────────────────────────

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    supportFragmentManager.findFragmentByTag("media_panel") != null -> {
                        (supportFragmentManager.findFragmentByTag("media_panel") as? MediaPanelFragment)?.dismiss()
                    }
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("url", binding.webView.url)
    }
}
