package com.charles.meshtalk.app

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.perf.FirebasePerformance

class FirebaseHelper private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("meshtalk_prefs", Context.MODE_PRIVATE)

    fun init() {
        val enabled = prefs.getBoolean(PREF_ANALYTICS_CRASHLYTICS_ENABLED, true)
        applyToAll(enabled)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_ANALYTICS_CRASHLYTICS_ENABLED, enabled).apply()
        applyToAll(enabled)
    }

    fun isEnabled(): Boolean = prefs.getBoolean(PREF_ANALYTICS_CRASHLYTICS_ENABLED, true)

    private fun applyToAll(enabled: Boolean) {
        // The github/Stripe flavor deliberately has no Firebase app registered (no matching
        // google-services.json entry, google-services processing disabled for that flavor — see
        // app/build.gradle.kts) since it's meant to be Google-services-free entirely. Guard so this
        // shared call from MainActivity.onCreate() is a safe no-op there instead of crashing.
        if (FirebaseApp.getApps(context).isEmpty()) return
        with(Firebase) {
            analytics.setAnalyticsCollectionEnabled(enabled)
            crashlytics.setCrashlyticsCollectionEnabled(enabled)
        }
        FirebasePerformance.getInstance().setPerformanceCollectionEnabled(enabled)
    }

    companion object {
        private const val PREF_ANALYTICS_CRASHLYTICS_ENABLED = "analytics_crashlytics_enabled"

        @Volatile private var instance: FirebaseHelper? = null

        fun get(context: Context): FirebaseHelper =
            instance ?: synchronized(this) {
                instance ?: FirebaseHelper(context.applicationContext).also { instance = it }
            }
    }
}
