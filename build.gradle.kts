plugins {
    // Bumped from 8.7.3: KSP 2.3.9 needs a newer AndroidComponentsExtension API.
    id("com.android.application") version "8.13.0" apply false
    // Bumped from 2.0.21: litertlm-android 0.13.1 was compiled with Kotlin 2.3.0 metadata, which
    // an older compiler can't read.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
    id("com.google.firebase.firebase-perf") version "1.4.2" apply false
}
