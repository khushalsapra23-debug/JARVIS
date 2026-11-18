package com.khushal.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
    }

    private var volUp = false
    private var volDown = false
    private val handler = Handler(Looper.getMainLooper())

    // Watchdog: if Jarvis was killed by the phone (not by you), start it again.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!JarvisService.alive && !JarvisService.isStopped(this@JarvisAccessibilityService) &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try { startForegroundService(Intent(this@JarvisAccessibilityService, JarvisService::class.java)) }
                catch (_: Exception) {}
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onServiceConnected() { instance = this; handler.post(watchdog) }
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null; handler.removeCallbacks(watchdog); return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- KILL SWITCH: press volume up + volume down together ----------
    override fun onKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP) volUp = down
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) volDown = down
        if (volUp && volDown) { volUp = false; volDown = false; panic() }
        return false
    }

    fun panic() {
        try {
            getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
        JarvisService.setStopped(this, true)
        stopService(Intent(this, JarvisService::class.java))
        disableSelf()
    }

    // ---------- Payment guard ----------
    /** False while a payment/banking app is on screen: Jarvis will not tap, type, swipe or read there. */
    fun allowed(): Boolean {
        val p = rootInActiveWindow?.packageName?.toString() ?: return true
        return !Payments.blocked(p)
    }

    // ---------- Gestures ----------
    fun swipe(up: Boolean) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        gesture(x, dm.heightPixels * if (up) 0.75f else 0.25f, x, dm.heightPixels * if (up) 0.25f else 0.75f)
    }

    fun swipeH(left: Boolean) {
        val dm = resources.displayMetrics
        val y = dm.heightPixels / 2f
        gesture(dm.widthPixels * if (left) 0.8f else 0.2f, y, dm.widthPixels * if (left) 0.2f else 0.8f, y)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build(), null, null)
    }

    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    fun global(action: Int) = performGlobalAction(action)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ---------- Smarter tapping ----------
    private fun labelOf(n: AccessibilityNodeInfo, depth: Int = 0): String {
        val own = listOfNotNull(n.text, n.contentDescription, n.hintText).joinToString(" ").trim()
        if (own.isNotEmpty() || depth >= 3) return own
        val sb = StringBuilder()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (c.isPassword) continue
            sb.append(labelOf(c, depth + 1)).append(' ')
        }
        return sb.toString().trim()
    }

    /** Clickable on-screen elements with a readable label (passwords skipped). */
    fun candidates(): List<Pair<String, AccessibilityNodeInfo>> {
        val out = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.size >= 80) return
            if (!n.isPassword && (n.isClickable || n.isCheckable)) {
                val l = labelOf(n)
                if (l.isNotBlank()) out.add(l to n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out
    }

    fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        tapAt(r.exactCenterX(), r.exactCenterY())
        return true
    }

    fun clickText(label: String): Boolean {
        val want = label.lowercase().trim()
        val c = candidates()
        val hit = c.firstOrNull { it.first.lowercase() == want } ?: c.firstOrNull { it.first.lowercase().contains(want) }
        if (hit != null) return clickNode(hit.second)
        val n = rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { !it.isPassword } ?: return false
        return clickNode(n)
    }

    fun typeText(text: String): Boolean {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (n.isPassword) return false
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun visibleText(): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 600 || n.isPassword) return
            n.text?.let { if (it.isNotBlank()) sb.append(it).append(". ") }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return sb.toString()
    }
}
