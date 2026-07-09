plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services") apply false
    id("com.google.firebase.crashlytics") apply false
    id("com.google.firebase.firebase-perf") apply false
}

// Release signing comes from environment variables (set by CI from GitHub Secrets) rather than a
// committed keystore.properties — nothing sensitive lives in this repo. Local `assembleRelease`
// builds without these set just produce an unsigned release APK, same as the previous default.
val releaseKeystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")

// GitHub feedback reporter config — read from local.properties (never committed) or from Gradle
// project properties / environment variables (for CI).
fun feedbackProp(name: String): String? {
    var result: String? = findProperty(name)?.toString()?.takeIf { it.isNotBlank() }
    if (result != null) return result
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.forEachLine { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2 && parts[0].trim() == name && result == null) {
                result = parts[1].trim()
            }
        }
    }
    if (result != null) return result
    return System.getenv(name.uppercase().replace('.', '_'))
}

// CI sets APP_VERSION_CODE to the GitHub Actions run number, which only ever increases, so every
// release build gets a strictly higher versionCode automatically with no manual bump needed.
// Local builds without it set fall back to a fixed dev versionCode.
val ciVersionCode: Int? = System.getenv("APP_VERSION_CODE")?.toIntOrNull()
val ciVersionName: String? = System.getenv("APP_VERSION_NAME")

android {
    namespace = "com.charles.meshtalk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.charles.meshtalk.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1"

        buildConfigField("String", "GITHUB_API_TOKEN",
            "\"${feedbackProp("github.api.token") ?: ""}\"")
        buildConfigField("String", "GITHUB_REPO_OWNER",
            "\"${feedbackProp("github.repo.owner") ?: ""}\"")
        buildConfigField("String", "GITHUB_REPO_NAME",
            "\"${feedbackProp("github.repo.name") ?: ""}\"")
        buildConfigField("String", "FEEDBACK_ASSETS_DIR", "\"feedback-assets\"")

        // AdMob config. Falls back to Google's official test IDs (never real ad traffic/revenue)
        // so a local build without the real secrets set still runs instead of crashing on a blank
        // <meta-data> value or failing to load ads with an invalid unit id.
        val admobAppId = feedbackProp("admob.app.id") ?: "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")
        buildConfigField("String", "ADMOB_BANNER_AD_UNIT_ID",
            "\"${feedbackProp("admob.banner.ad.unit.id") ?: "ca-app-pub-3940256099942544/9214589741"}\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_AD_UNIT_ID",
            "\"${feedbackProp("admob.interstitial.ad.unit.id") ?: "ca-app-pub-3940256099942544/1033173712"}\"")
        manifestPlaceholders["admobAppId"] = admobAppId
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Two distribution channels, kept completely separate: "play" uses Google Play Billing (the
    // only billing option Play Store policy allows for a digital feature like walkie-talkie);
    // "github" is the sideloaded build, which cannot use Play Billing at all and instead talks to
    // a Cloudflare Worker + Stripe for payment. Each flavor's billing implementation lives in its
    // own source set (src/play/..., src/github/...) behind the shared BillingRepository interface,
    // so neither flavor's dependencies or code ever ship in the other's build.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("github") {
            dimension = "distribution"
            // Lets both flavors install side-by-side on the same test device — this channel is
            // never uploaded to Play, so the differing package id has no downside.
            applicationIdSuffix = ".github"

            // Cloudflare Worker base URL (public, non-secret) and the Ed25519 *public* key used to
            // verify entitlement tokens locally offline — never the Worker's own secrets, which
            // live only in the Worker's own deployment (see cloudflare-worker/wrangler.toml).
            buildConfigField("String", "CLOUDFLARE_WORKER_BASE_URL",
                "\"${feedbackProp("cloudflare.worker.url") ?: ""}\"")
            buildConfigField("String", "ENTITLEMENT_VERIFY_PUBLIC_KEY_HEX",
                "\"${feedbackProp("entitlement.verify.public.key.hex") ?: ""}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

// google-services.json only registers an app for the "play" flavor's applicationId — the
// "github" flavor's whole point is not depending on Google services, so its build shouldn't
// require (or expect) a matching Firebase app registration. Without this, the google-services
// plugin fails the github build outright ("No matching client found").
afterEvaluate {
    tasks.matching { it.name.startsWith("processGithub") && it.name.endsWith("GoogleServices") }
        .configureEach { enabled = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // GitHub feedback reporter dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")

    // On-device Gemma 4 inference (fully offline once the model file is downloaded).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-perf-ktx")

    // AdMob (banner + interstitial); shown only while the walkie-talkie entitlement is locked —
    // see AdsManager.kt.
    implementation("com.google.android.gms:play-services-ads:23.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    // Play flavor: Google Play Billing for the one-time purchase + subscription-with-trial.
    "playImplementation"("com.android.billingclient:billing-ktx:7.1.1")
    // Github flavor: opens the Cloudflare Worker's Stripe Checkout URL in a Custom Tab; the
    // Retrofit/OkHttp/kotlinx-serialization deps it needs for talking to the Worker are already
    // shared dependencies above (used by the feedback feature), so nothing extra needed there.
    "githubImplementation"("androidx.browser:browser:1.8.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
    apply(plugin = "com.google.firebase.firebase-perf")
}
