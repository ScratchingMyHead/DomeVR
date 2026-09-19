plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.domevr.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.domevr.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 54
        versionName = "0.6.16"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Media3 ExoPlayer for playback (http from our local proxy)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // SMB2/3 client — directory listing + random-access reads
    implementation("com.hierynomus:smbj:0.14.0") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation("org.slf4j:slf4j-android:1.7.36")
}
