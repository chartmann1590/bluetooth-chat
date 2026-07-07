plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
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
        targetSdk = 34
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1"

        buildConfigField("String", "GITHUB_API_TOKEN",
            "\"${feedbackProp("github.api.token") ?: ""}\"")
        buildConfigField("String", "GITHUB_REPO_OWNER",
            "\"${feedbackProp("github.repo.owner") ?: ""}\"")
        buildConfigField("String", "GITHUB_REPO_NAME",
            "\"${feedbackProp("github.repo.name") ?: ""}\"")
        buildConfigField("String", "FEEDBACK_ASSETS_DIR", "\"feedback-assets\"")
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

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
