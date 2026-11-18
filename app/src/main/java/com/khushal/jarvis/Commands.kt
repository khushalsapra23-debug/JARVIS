package com.khushal.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Command router. Unknown sentences go to the AI brain once, never recursively. */
class Commands(private val s: JarvisService) {

    private val prefs = s.getSharedPreferences("jarvis", 0)
    private val recent = mutableListOf<Long>()
    private val hardStopRx = Regex("\\b(emergency stop|kill switch|shut down|shutdown|panic)\\b")
    private val softStops = setOf("stop", "stop it", "stop everything", "cancel", "enough", "wait", "freeze")
    private val pickRx = Regex("\\b(pick ?up|answer|accept)\\b")
    private val rejectRx = Regex("\\b(reject|decline|hang up|end the call|end call|cut the call|cut call|disconnect)\\b")
    private val msgRx = Regex("^(?:message|whatsapp|text)\\s+(.+?)\\s+(?:saying|that)\\s+(.+)$", RegexOption.IGNORE_CASE)

    fun handle(raw: String, viaAi: Boolean = false) {
        val t = raw.lowercase().replace(".", "").replace(",", "").trim()

        // Stop commands are checked first and always work.
        if (hardStopRx.containsMatchIn(t)) { s.hardStop(); return }
        if (t in softStops) { s.softStop(); s.say("Stopped"); return }

        // Runaway protection: more than 30 actions in a minute = stop everything.
        val now = SystemClock.elapsedRealtime()
        recent.removeAll { now - it > 60_000 }
        recent.add(now)
        if (recent.size > 30) {
            recent.clear(); s.softStop(); s.say("Too many actions in a minute. I stopped."); return
        }

        val pick = (pickRx.containsMatchIn(t) && t.contains("call")) ||
            t in setOf("pick up", "pickup", "answer", "answer it", "pick up on speaker", "answer on speaker")
        val msg = msgRx.find(raw.trim())

        when {
            pick -> answer(t.contains("speaker"))
            rejectRx.containsMatchIn(t) -> rejectCall()
            msg != null -> message(msg.groupValues[1], msg.groupValues[2])
            t.startsWith("open ") || t.startsWith("launch ") -> openApp(t.substringAfter(" "))
            t.startsWith("call ") -> call(t.removePrefix("call ").trim())
            t.startsWith("search ") -> {
                val q = t.removePrefix("search for ").removePrefix("search ").trim()
                Util.launch(s, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            }
            t.startsWith("type ") -> a11y { if (!it.typeText(raw.trim().drop(5))) s.say("No text box is selected") }
            t.contains("read screen") || t.contains("read the screen") -> a11y {
                val txt = it.visibleText()
                s.say(if (txt.isBlank()) "I cannot read anything here" else txt.take(400))
            }
            t.contains("screenshot") -> a11y(true) { sysAction(it, "shot") }
            t.contains("lock screen") || t.contains("lock the phone") || t.contains("lock phone") -> a11y(true) { sysAction(it, "lock") }
            t.contains("notifications") -> a11y(true) { it.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
            t.contains("alarm") -> alarm(t)
            t.contains("timer") -> timer(t)
            t.startsWith("remind me to ") -> remind(t)
            t.startsWith("add task ") -> addTask(t.removePrefix("add task ").trim())
            t.contains("clear tasks") -> { prefs.edit().remove("tasks").apply(); s.say("Tasks cleared") }
            t.contains("tasks") -> readTasks()
            t.contains("scroll down") || t.contains("next reel") || t.contains("next video") || t == "next" -> a11y { it.swipe(true) }
            t.contains("scroll up") || t.contains("previous reel") || t.contains("previous video") -> a11y { it.swipe(false) }
            t.contains("swipe left") -> a11y { it.swipeH(true) }
            t.contains("swipe right") -> a11y { it.swipeH(false) }
            t == "go back" || t == "back" -> a11y(true) { it.back() }
            t.contains("go home") || t.contains("home screen") -> a11y(true) { it.home() }
            t.contains("recent apps") -> a11y(true) { it.recents() }
            t.startsWith("click ") || t.startsWith("tap ") -> tap(t.substringAfter(" "))
            t.contains("volume up") -> volume(AudioManager.ADJUST_RAISE)
            t.contains("volume down") -> volume(AudioManager.ADJUST_LOWER)
            t.contains("flashlight") || t.contains("torch") -> torch(!t.contains("off"))
            t.contains("what time") -> s.say("It is " + SimpleDateFormat("h mm a", Locale.getDefault()).format(Date()))
            t.contains("stop listening") || t.contains("goodbye") -> { s.say("Goodbye"); s.main.postDelayed({ s.quit() }, 1500) }
            // Commands that came FROM the AI never go back to the AI (prevents infinite loops).
            else -> if (viaAi) s.say("I can't do that one") else s.think(t)
        }
    }

    /** safe = true for Back/Home/Recents etc., which must keep working even inside a payment app. */
    private fun a11y(safe: Boolean = false, block: (JarvisAccessibilityService) -> Unit) {
        val svc = JarvisAccessibilityService.instance
        when {
            svc == null -> s.say("Please turn on accessibility for Jarvis")
            !safe && !svc.allowed() -> s.say("I don't operate payment or banking apps")
            else -> block(svc)
        }
    }

    private fun sysAction(svc: JarvisAccessibilityService, what: String) {
        if (Build.VERSION.SDK_INT < 28) { s.say("That needs Android 9 or newer"); return }
        svc.global(if (what == "shot") AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                   else AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    // ---------- Calls ----------
    private fun callState() = s.getSystemService(TelephonyManager::class.java).callState

    private fun answer(speaker: Boolean) {
        if (callState() != TelephonyManager.CALL_STATE_RINGING) { s.say("No call is ringing"); return }
        try {
            s.getSystemService(TelecomManager::class.java).acceptRingingCall()
        } catch (e: SecurityException) { s.say("I need the answer calls permission"); return }
        if (speaker) s.main.postDelayed({
            try { s.getSystemService(AudioManager::class.java).isSpeakerphoneOn = true } catch (_: Exception) {}
        }, 1500)
    }

    @Suppress("DEPRECATION")
    private fun rejectCall() {
        if (callState() == TelephonyManager.CALL_STATE_IDLE) { s.say("There is no call"); return }
        if (Build.VERSION.SDK_INT < 28) { s.say("That needs Android 9 or newer"); return }
        try { s.getSystemService(TelecomManager::class.java).endCall() }
        catch (e: SecurityException) { s.say("I need the answer calls permission") }
    }

    private fun call(name: String) {
        val num = Util.numberFor(s, name)
        if (num == null) { s.say("I could not find $name in your contacts"); return }
        s.say("Calling $name")
        Util.launch(s, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))
    }

    /** Opens the WhatsApp chat with the text filled in, then presses Send. */
    private fun message(name: String, text: String) {
        val num = Util.numberFor(s, name.lowercase())
        if (num == null) { s.say("I could not find $name in your contacts"); return }
        var d = num.filter { it.isDigit() }
        if (d.length == 10) d = "91$d"   // assumes Indian numbers saved without country code
        s.say("Messaging $name")
        Util.launch(s, Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$d&text=" + Uri.encode(text))))
        val g = s.gen
        s.main.postDelayed({
            if (g == s.gen) a11y { if (!it.clickText("Send")) s.say("The message is ready but I could not press send") }
        }, 4000)
    }

    // ---------- Screen tapping: exact match first, then the AI picks the element ----------
    private fun tap(label: String) {
        a11y { svc ->
            if (svc.clickText(label)) return@a11y
            val c = svc.candidates()
            if (c.isEmpty()) { s.say("I can't see anything to tap"); return@a11y }
            val g = s.gen
            Brain.pick(s, label, c.map { it.first }) { idx ->
                s.main.post {
                    if (g != s.gen) return@post
                    if (idx == null) s.say("I could not find $label")
                    else if (!svc.allowed() || !svc.clickNode(c[idx].second)) s.say("That did not work")
                }
            }
        }
    }

    // ---------- Utilities ----------
    private fun volume(dir: Int) {
        s.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun torch(on: Boolean) {
        try {
            val cm = s.getSystemService(CameraManager::class.java)
            cm.setTorchMode(cm.cameraIdList[0], on)
        } catch (e: Exception) { s.say("Flashlight did not work") }
    }

    private fun openApp(name: String) {
        val i = Util.findApp(s, name)
        if (i == null) s.say("I could not find $name") else { s.say("Opening $name"); Util.launch(s, i) }
    }

    private fun alarm(t: String) {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(t.replace("a m", "am").replace("p m", "pm"))
        if (m == null) { s.say("Which time?"); return }
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3]
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        Util.launch(s, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        s.say("Alarm set for %d %02d".format(h, min))
    }

    private fun timer(t: String) {
        val m = Regex("(\\d+)\\s*(second|minute|hour)").find(t)
        if (m == null) { s.say("For how long?"); return }
        val n = m.groupValues[1].toInt()
        val secs = when (m.groupValues[2]) { "hour" -> n * 3600; "minute" -> n * 60; else -> n }
        Util.launch(s, Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        s.say("Timer started")
    }

    private fun remind(t: String) {
        val m = Regex("remind me to (.+?) in (\\d+) (minute|hour)").find(t)
        if (m == null) { addTask(t.removePrefix("remind me to ")); return }
        val what = m.groupValues[1]
        val ms = m.groupValues[2].toLong() * if (m.groupValues[3] == "hour") 3_600_000L else 60_000L
        s.main.postDelayed({ s.say("Reminder: $what") }, ms)
        s.say("Okay, I will remind you")
    }

    private fun addTask(task: String) {
        val list = (prefs.getString("tasks", "") ?: "").split("\n").filter { it.isNotBlank() } + task
        prefs.edit().putString("tasks", list.joinToString("\n")).apply()
        s.say("Added $task")
    }

    private fun readTasks() {
        val list = (prefs.getString("tasks", "") ?: "").split("\n").filter { it.isNotBlank() }
        if (list.isEmpty()) s.say("You have no tasks")
        else s.say("You have ${list.size} tasks. " + list.joinToString(". "))
    }
}
