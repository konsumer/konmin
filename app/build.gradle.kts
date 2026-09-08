import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Release signing is conditional: it activates only when a keystore is fully
// configured via environment variables AND the keystore file actually exists.
// GitHub Actions passes absent secrets through as empty strings (not null), so
// blank values are treated as absent here. When the keys/keystore are missing,
// the release buildType is left unsigned (its existing behavior), so local
// builds and CI without the secret still produce a working unsigned release APK.
val keystoreFile = System.getenv("KEYSTORE_FILE")
val hasReleaseKey = !keystoreFile.isNullOrBlank() &&
    !System.getenv("KEYSTORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("KEY_PASSWORD").isNullOrBlank() &&
    File(keystoreFile).isFile

android {
    namespace = "com.konsumer.konmin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.konsumer.konmin"
        minSdk = 26
        targetSdk = 34
        // Version is overridable via env vars so CI stamps the build from the
        // release git tag (VERSION_CODE / VERSION_NAME, set by the release
        // workflow). A normal local build without them falls back to the
        // defaults below. Local builds (no env vars) stay at 1 / 0.1.0.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(keystoreFile!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Name every built APK konmin-<versionName>-<buildType>.apk, e.g.
    // konmin-0.1.0-debug.apk / konmin-0.1.0-release.apk (the unsigned release
    // artifact gets no "-unsigned" suffix either). versionName comes from
    // defaultConfig, so CI-stamped tags and the 0.1.0 local fallback both flow
    // through. AGP 8.7's new variant API no longer exposes output file naming
    // (VariantOutput.outputFileName is gone), so the legacy applicationVariants
    // DSL is used; it still works on AGP 8.x and is removed only in AGP 9.
    applicationVariants.all {
        val variantName = name
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "konmin-${android.defaultConfig.versionName}-$variantName.apk"
        }
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore for settings/theme prefs
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WorkManager for the widget tick scheduler
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Palette for wallpaper auto-accent color extraction
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Serialization for plugin manifests and cached Line[] output
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // QuickJS: sandboxed JS for plugins. ~2MB of native across 4 ABIs, no
    // DOM/WebView surface, and native async/await so plugins can be written
    // as `async function render(ctx)`.
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
}
