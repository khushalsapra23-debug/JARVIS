package com.khushal.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object Util {

    /** Launch from the accessibility service when possible: Android blocks background activity starts. */
    fun launch(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = JarvisAccessibilityService.instance ?: ctx
        try { c.startActivity(intent) } catch (e: Exception) { Log.e("Jarvis", "launch failed", e) }
    }

    private val aliases = mapOf(
        "insta" to "instagram", "yt" to "youtube", "you tube" to "youtube",
        "whats app" to "whatsapp", "face book" to "facebook", "chrome browser" to "chrome"
    )

    fun findApp(ctx: Context, spoken: String): Intent? {
        val pm = ctx.packageManager
        val want = aliases[spoken.trim()] ?: spoken.trim()
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
        fun label(i: android.content.pm.ResolveInfo) = i.loadLabel(pm).toString().lowercase()
        val hit = apps.firstOrNull { label(it) == want } ?: apps.firstOrNull { label(it).contains(want) }
        return hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
    }

    fun numberFor(ctx: Context, name: String): String? = try {
        val p = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        ctx.contentResolver.query(
            p, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: SecurityException) { null }

    fun nameFor(ctx: Context, number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: SecurityException) { null }
}

/** Jarvis never operates payment / banking apps. */
object Payments {
    private val known = setOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
        "in.org.npci.upiapp", "com.paypal.android.p2pmobile", "com.squareup.cash",
        "com.venmo", "com.google.android.apps.walletnfcrel"
    )
    private val words = listOf("bank", "upi", "paytm", "phonepe", "paisa", "bhim", "razorpay", "wallet", "mobikwik", "freecharge")
    fun blocked(pkg: String) = pkg in known || words.any { pkg.lowercase().contains(it) }
}
