package com.hitif.videodownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.hitif.videodownloader.model.MediaItem
import com.hitif.videodownloader.network.MediaDetector

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    val mediaItems   = MutableLiveData<List<MediaItem>>(emptyList())
    val pageTitle    = MutableLiveData<String>("")
    val pageUrl      = MutableLiveData<String>("")
    val isLoading    = MutableLiveData<Boolean>(false)
    val canGoBack    = MutableLiveData<Boolean>(false)
    val canGoForward = MutableLiveData<Boolean>(false)
    val panelVisible = MutableLiveData<Boolean>(false)

    private val _seenUrls = mutableSetOf<String>()

    val detector = MediaDetector { item ->
        val key = item.url.substringBefore('?')
        synchronized(_seenUrls) {
            if (_seenUrls.add(key)) {
                val current = mediaItems.value.orEmpty().toMutableList()
                current.add(0, item)          // newest first
                mediaItems.postValue(current)
            }
        }
    }

    fun clearMedia() {
        _seenUrls.clear()
        detector.reset()
        mediaItems.value = emptyList()
    }

    fun removeItem(item: MediaItem) {
        val list = mediaItems.value.orEmpty().toMutableList()
        list.remove(item)
        mediaItems.value = list
    }

    fun mediaCount(): Int = mediaItems.value?.size ?: 0
}
