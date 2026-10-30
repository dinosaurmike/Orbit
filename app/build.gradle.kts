import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Reads private values (like the Gemini API key) from local.properties,
// which stays on this computer and is never uploaded
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.companion2"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.companion2"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // The app reads BuildConfig.GEMINI_API_KEY as a fallback when no key is entered in settings
        debug {
            // Test builds run from Android Studio use your own key from local.properties
            buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("GEMINI_API_KEY", "")}\"")
        }
        release {
            // Published builds never contain a key: people enter their own in settings
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // MediaPipe's library comes already stripped, so don't try (and don't warn about it)
            keepDebugSymbols += "**/libmediapipe_tasks_vision_jni.so"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Camera
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    // On-device face detection
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Internet connection to Gemini Live (WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Wake word: Vosk, a free offline speech recognizer (plus JNA, which it needs to run)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // On-phone hand gestures and recognizing who's who (Google MediaPipe; models download on first use)
    implementation("com.google.mediapipe:tasks-vision:0.10.26")  // 0.10.26+ supports 16 KB page sizes (needed for Google Play)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}