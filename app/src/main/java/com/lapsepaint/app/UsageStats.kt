package com.lapsepaint.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Anonymous usage statistics through Google Analytics for Firebase.
 *
 * Nothing is sent unless both are true: the app was built with google-services.json
 * (so Firebase is set up), and the user tapped Allow in the consent prompt. Only
 * counts and timings are sent - never drawings, photos or personal details.
 * Firebase also measures time in the app and screen views on its own once enabled.
 */
object UsageStats {
    private const val PREFS = "usage"
    private var analytics: FirebaseAnalytics? = null
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        analytics = runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAnalytics.getInstance(context)
        }.getOrNull()
        analytics?.setAnalyticsCollectionEnabled(isEnabled)
    }

    /** True when the app was built with a Firebase project. */
    val isConfigured get() = analytics != null

    val isEnabled get() = prefs?.getBoolean("enabled", false) == true
    private val hasAsked get() = prefs?.contains("enabled") == true

    fun setEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
        analytics?.setAnalyticsCollectionEnabled(on)
    }

    /** Shows the one-time consent prompt, if Firebase is set up and the user hasn't answered yet. */
    fun askOnce(activity: Activity) {
        if (!isConfigured || hasAsked) return
        AlertDialog.Builder(activity)
            .setTitle("Help improve Lapse Paint?")
            .setMessage(
                "Share anonymous usage data: how long the app is used and which tools are used. " +
                    "Your drawings and photos are never sent. You can change this any time in Actions.",
            )
            .setPositiveButton("Allow") { _, _ -> setEnabled(true) }
            .setNegativeButton("No thanks") { _, _ -> setEnabled(false) }
            .setCancelable(false)
            .show()
    }

    /** Logs an event. Values may be String, Int, Long, Double or Boolean. */
    fun log(event: String, vararg params: Pair<String, Any>) {
        val a = analytics ?: return
        if (!isEnabled) return
        val bundle = Bundle()
        for ((key, value) in params) {
            when (value) {
                is String -> bundle.putString(key, value.take(100))
                is Int -> bundle.putLong(key, value.toLong())
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                is Float -> bundle.putDouble(key, value.toDouble())
                is Boolean -> bundle.putLong(key, if (value) 1 else 0)
            }
        }
        a.logEvent(event, bundle)
    }
}

class LapsePaintApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UsageStats.init(this)
    }
}
