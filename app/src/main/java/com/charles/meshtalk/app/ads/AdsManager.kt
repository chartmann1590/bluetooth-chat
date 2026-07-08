package com.charles.meshtalk.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.charles.meshtalk.app.BuildConfig
import com.charles.meshtalk.app.billing.BillingRepository
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Thin wrapper around the Mobile Ads SDK. Ads only ever show while the walkie-talkie entitlement
 * (the app's one paid product) is locked — [BillingRepository.isFeatureUnlocked] doubles as the
 * "ad-free/pro" check, since purchasing or subscribing is the only way to go ad-free here too.
 */
object AdsManager {
    private const val TAG = "AdsManager"
    private const val INTERSTITIAL_COOLDOWN_MS = 60_000L

    @Volatile private var initialized = false
    @Volatile private var interstitialAd: InterstitialAd? = null
    @Volatile private var interstitialLoading = false
    @Volatile private var lastInterstitialShownAt = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        MobileAds.initialize(context.applicationContext) {}
    }

    fun loadInterstitialIfNeeded(context: Context, billing: BillingRepository) {
        if (billing.isFeatureUnlocked()) return
        if (interstitialAd != null || interstitialLoading) return
        interstitialLoading = true
        InterstitialAd.load(
            context.applicationContext,
            BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitialAd = ad
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    Log.d(TAG, "Interstitial failed to load: ${error.message}")
                }
            }
        )
    }

    /** Shows a preloaded interstitial if one is ready, at most once per [INTERSTITIAL_COOLDOWN_MS],
     * then starts loading the next one. No-op if [billing] reports the feature already unlocked. */
    fun maybeShowInterstitial(activity: Activity, billing: BillingRepository) {
        if (billing.isFeatureUnlocked()) return
        val ad = interstitialAd ?: return
        val now = System.currentTimeMillis()
        if (now - lastInterstitialShownAt < INTERSTITIAL_COOLDOWN_MS) return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadInterstitialIfNeeded(activity, billing)
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                loadInterstitialIfNeeded(activity, billing)
            }
        }
        lastInterstitialShownAt = now
        ad.show(activity)
    }
}
