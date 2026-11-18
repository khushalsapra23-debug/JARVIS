package com.khushal.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import java.util.Locale

class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile var alive = false
        fun setStopped(c: Context, v: Boolean) =
            c.getSharedPreferences("jarvis", 0).edit().putBoolean("user_stopped", v).apply()
        fun isStopped(c: Context) = c.getSharedPreferences("jarvis", 0).getBoolean("user_stopped", false)
    }

    val main = Handler(Looper.getMainLooper())
    @Volatile var gen = 0          // bumped on every stop; queued AI actions from older generations are dropped
    @Volatile private var ringing = false
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private lateinit var commands: Commands
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = true
    private var speaking = false
    private var awaitUntil = 0L
    private var lastRing = 0L

    private val wakeWords = listOf("jarvis", "jarves", "hey jar")
    private val restart = Runnable { listen() }
    private val callWords = Regex("\\b(pick ?up|answer|accept|reject|decline|hang up)\\b")

    private val recogIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // uses the offline speech pack if you downloaded it
    }

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            ringing = i.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_RINGING
            if (!ringing) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRing < 4000) return   // Android sends this broadcast twice
            lastRing = now
            @Suppress("DEPRECATION")
            val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val who = num?.let { Util.nameFor(this@JarvisService, it) ?: it.toCharArray().joinToString(" ") }
                ?: "an unknown number"
            say("Incoming call from $who. Say pick up, or reject.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening")
            .setContentText("Say \"Jarvis\" then a command. Tap STOP to kill it.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_delete), "STOP",
                PendingIntent.getService(this, 0,
                    Intent(this, JarvisService::class.java).setAction("STOP_ALL"), PendingIntent.FLAG_IMMUTABLE)
            ).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Exception) {
            getSharedPreferences("jarvis", 0).edit().putString("last_crash", "Foreground start refused: $e").apply()
            alive = false
            stopSelf()
            return
        }

        // Keeps the CPU awake while the screen is off so listening continues (uses more battery).
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen").apply { acquire() }

        commands = Commands(this)
        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }

        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(phoneReceiver, f)
    }

    override fun onInit(status: Int) {
        tts.language = Locale("en", "IN")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { resume() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { resume() }
        })
        say("Jarvis online")
    }

    private fun resume() {
        main.post { speaking = false; main.postDelayed(restart, 300) }
    }

    fun say(text: String) {
        main.post {
            speaking = true
            recognizer?.cancel()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
        }
    }

    /** Soft stop: cancel speech and any queued AI actions, keep Jarvis running. */
    fun softStop() {
        gen++
        tts.stop()
        speaking = false
        main.removeCallbacks(restart)
        main.postDelayed(restart, 300)
    }

    /** Hard stop: kill Jarvis completely and switch its accessibility service off. */
    fun hardStop() {
        gen++
        setStopped(this, true)
        try { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
        JarvisAccessibilityService.instance?.disableSelf()
        stopSelf()
    }

    /** Normal quit ("goodbye"): stops Jarvis and tells the watchdog not to bring it back. */
    fun quit() { setStopped(this, true); stopSelf() }

    /** Hands unknown sentences to the LLM, then runs the commands it returns one by one. */
    fun think(text: String) {
        val g = gen
        Brain.ask(this, text) { reply, cmds ->
            main.post {
                if (g != gen) return@post
                if (reply.isNotBlank()) say(reply)
                var d = if (reply.isBlank()) 0L else 2500L
                cmds.take(15).forEach { c ->
                    main.postDelayed({ if (g == gen) commands.handle(c, true) }, d); d += 1800
                }
            }
        }
    }

    private fun listen() {
        if (!running || speaking) return
        main.removeCallbacks(restart)
        recognizer?.startListening(recogIntent)
    }

    private fun onHeard(text: String) {
        val t = text.lowercase()
        val now = SystemClock.elapsedRealtime()
        val idx = wakeWords.map { t.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (idx != null) {
            val w = wakeWords.first { t.indexOf(it) == idx }
            val cmd = t.substring(idx + w.length).trim()
            if (cmd.isEmpty()) { awaitUntil = now + 8000; say("Yes?") } else commands.handle(cmd)
        } else if (t.trim() in setOf("stop", "stop it", "cancel", "enough")) {
            commands.handle(t)
        } else if (ringing && callWords.containsMatchIn(t)) {
            commands.handle(t)        // while the phone rings, no wake word needed
        } else if (now < awaitUntil) {
            awaitUntil = 0
            commands.handle(t)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(r: Bundle?) {
            r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onHeard(it) }
            if (!speaking) main.postDelayed(restart, 300)
        }
        override fun onError(error: Int) {
            if (!speaking) main.postDelayed(restart, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 500)
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_ALL") { hardStop(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        alive = false
        gen++
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        if (::tts.isInitialized) tts.shutdown()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
