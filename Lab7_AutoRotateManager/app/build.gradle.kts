import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // KSP generates Room DAO/database code at compile time
    alias(libs.plugins.ksp)
}

// Secrets come from local.properties (not committed to Git)
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.lab7_autorotatemanager"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.lab7_autorotatemanager"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FIREBASE_DB_URL", "\"${localProps.getProperty("FIREBASE_DB_URL", "")}\"")
        buildConfigField("String", "FIREBASE_AUTH", "\"${localProps.getProperty("FIREBASE_AUTH", "")}\"")
        // Firebase SDK options (if empty, REST streaming is used)
        buildConfigField("String", "FIREBASE_APP_ID", "\"${localProps.getProperty("FIREBASE_APP_ID", "")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${localProps.getProperty("FIREBASE_API_KEY", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
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
    // Keep .tflite uncompressed so it can be memory-mapped
    androidResources {
        noCompress += "tflite"
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
    // collectAsStateWithLifecycle()
    implementation(libs.androidx.lifecycle.runtime.compose)

    // REST client, JSON, HTTP logging
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Firebase Realtime Database SDK
    implementation("com.google.firebase:firebase-database:22.0.2")

    // Room (SQLite) + KSP code generation
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Charts (MPAndroidChart via JitPack)
    implementation(libs.mpandroidchart)

    // TensorFlow Lite on-device inference
    implementation(libs.tensorflow.lite)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}