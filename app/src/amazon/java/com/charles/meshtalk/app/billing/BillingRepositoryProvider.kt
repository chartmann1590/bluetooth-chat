package com.charles.meshtalk.app.billing

import android.content.Context

/** Resolved per-flavor via Gradle source sets (src/play, src/github, src/amazon) — see [BillingRepository]. */
object BillingRepositoryProvider {
    @Volatile private var instance: BillingRepository? = null

    fun create(context: Context): BillingRepository =
        instance ?: synchronized(this) {
            instance ?: AmazonBillingRepository(context.applicationContext).also { instance = it }
        }
}
