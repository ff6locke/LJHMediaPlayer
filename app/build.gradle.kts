val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
val appBuildNumber = maxOf(2, ciBuildNumber)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ljh.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ljh.player"
        minSdk = 23
        targetSdk = 28
        versionCode = appBuildNumber
        versionName = "0.1.$appBuildNumber"
    }

    // Keep Java and Kotlin bytecode targets aligned for the CI build.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
