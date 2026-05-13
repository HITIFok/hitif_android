package com.hitif.videodownloader

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Catch uncaught exceptions and show them as Toast
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("HITIF_CRASH", "Uncaught exception in thread ${t.name}", e)
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            Log.e("HITIF_CRASH", sw.toString())
            // Also try to show a Toast on the main thread
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(this, "Erreur: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {}
            // Let the default handler kill the app
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            defaultHandler?.uncaughtException(t, e)
        }
    }
}
