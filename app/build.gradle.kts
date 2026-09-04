import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// pCloud OAuth client config lives in local.properties (gitignored) rather
// than hardcoded, so swapping in a different registered app is a one-line
// config change - see local.properties' own comment on why the current
// values (reused from the Big Finish player's registration) may end up
// scoped to only the "Big Finish" folder.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.sjpickard.pcloudmusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sjpickard.pcloudmusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-prototype"

        buildConfigField("String", "PCLOUD_CLIENT_ID", "\"${localProperties.getProperty("pcloud.clientId", "")}\"")
        buildConfigField("String", "PCLOUD_REDIRECT_URI", "\"${localProperties.getProperty("pcloud.redirectUri", "pcloudmusic://oauth")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room (local catalog: Artist / Album / Track)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Media3: playback + MediaSession (lock screen, Bluetooth, Android Auto)
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")

    // Background downloads for "keep offline"
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Cover art loading/decoding/caching in Compose
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Home screen widget (now-playing + prev/play-pause/next) - Compose-style
    // DSL rather than classic RemoteViews/View-XML, to match the rest of
    // this app's all-Compose UI.
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // pCloud OAuth (plain browser token-flow, no SDK dependency - see
    // PCloudAuthManager) and REST API access (PCloudApiClient).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
