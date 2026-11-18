package com.khushal.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val autostartScreens = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
    )

    private fun startJarvis() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Tap button 1 and allow the microphone first", Toast.LENGTH_LONG).show()
            return
        }
        JarvisService.setStopped(this, false)
        try { startForegroundService(Intent(this, JarvisService::class.java)) }
        catch (e: Exception) { Toast.makeText(this, "Could not start: " + e.message, Toast.LENGTH_LONG).show() }
    }

    private fun openAutostart() {
        for ((pkg, cls) in autostartScreens) {
            try { startActivity(Intent().setClassName(pkg, cls)); return } catch (_: Exception) {}
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        fun text(s: String, size: Float) = TextView(this).apply { this.text = s; textSize = size; setPadding(0, 0, 0, 24) }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        col.addView(text("Jarvis", 28f))
        val crash = getSharedPreferences("jarvis", 0).getString("last_crash", "")
        if (!crash.isNullOrBlank()) {
            col.addView(text("Last crash (send this to get help):\n\n$crash", 11f).apply { setTextIsSelectable(true); setTextColor(Color.RED) })
            col.addView(btn("Clear crash message") {
                getSharedPreferences("jarvis", 0).edit().remove("last_crash").apply()
            })
        }
        col.addView(btn("EMERGENCY STOP") {
            JarvisService.setStopped(this, true)
            stopService(Intent(this, JarvisService::class.java))
            JarvisAccessibilityService.instance?.disableSelf()
        }.apply { setBackgroundColor(Color.RED); setTextColor(Color.WHITE) })
        col.addView(text("Setup (once): do 1 to 5, then Start. Say \"Jarvis open YouTube\", \"Jarvis pick up\", \"Jarvis stop\".", 15f).apply { setPadding(0, 24, 0, 24) })

        col.addView(btn("1. Grant permissions") {
            val p = mutableListOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.ANSWER_PHONE_CALLS
            )
            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(p.toTypedArray(), 1)
        })
        col.addView(btn("2. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(btn("3. Turn on Jarvis accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("4. Keep Jarvis alive (battery)") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        col.addView(btn("5. Auto-start settings (Xiaomi/Oppo/Vivo/Realme)") { openAutostart() })

        val key = EditText(this).apply { hint = "Anthropic API key (optional, for AI brain)"; setSingleLine() }
        col.addView(key)
        col.addView(btn("Save API key") {
            getSharedPreferences("jarvis", 0).edit().putString("api_key", key.text.toString().trim()).apply()
            key.setText("")
        })
        col.addView(btn("Start Jarvis") { startJarvis() })
        col.addView(btn("Stop Jarvis") {
            JarvisService.setStopped(this, true)
            stopService(Intent(this, JarvisService::class.java))
        })

        col.addView(text("Offline use: download the offline speech pack in Settings > Google > Settings for Google apps > Search, Assistant & Voice > Voice > Offline speech recognition. Phone commands then work without internet; the AI brain and WhatsApp still need it.", 13f).apply { setPadding(0, 32, 0, 16) })
        col.addView(text("Ways to stop Jarvis: say \"stop\" (pauses), say \"Jarvis emergency stop\" (kills it), press volume up + down together, tap STOP in the notification, or the red button. Last resort: Settings > Accessibility > Jarvis > off.", 13f))
        setContentView(ScrollView(this).apply { addView(col) })

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && !JarvisService.isStopped(this)) {
            startJarvis()
        }
    }
}
