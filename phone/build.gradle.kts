// phone/build.gradle.kts - the Android PHONE version. Shares the Navidrome/lyrics/metadata code with the TV app.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val versionOffset: Int = System.getenv("VERSION_CODE_OFFSET")?.toIntOrNull() ?: 0
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.oledtv.music.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oledtv.music.phone"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber + versionOffset
        versionName = "1.0.$runNumber"
    }

    // Reuse (not copy) the TV app's data layer: server API, lyrics lookup, tag clean-up.
    sourceSets.getByName("main").java.srcDir("../app/src/main/java/com/oledtv/music/data")

    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("ci") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("ci")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }

    lint { abortOnError = false }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("io.coil-kt:coil-compose:2.6.0")
}
