package com.khushal.jarvis

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last crash so the app can show it on screen next time. */
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                getSharedPreferences("jarvis", 0).edit()
                    .putString("last_crash", sw.toString().take(3000)).commit()
            } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}
