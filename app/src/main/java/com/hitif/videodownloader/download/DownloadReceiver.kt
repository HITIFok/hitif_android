package com.hitif.videodownloader.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q  = DownloadManager.Query().setFilterById(id)
        val c  = dm.query(q)
        if (c != null && c.moveToFirst()) {
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val title  = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "Fichier"
            c.close()
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(context, "✓ Téléchargé : $title", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "✗ Échec : $title", Toast.LENGTH_SHORT).show()
            }
        } else {
            c?.close()
        }
    }
}
