import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Release signing key, kept out of git (see PROGRESS.md). Without it, release builds fall back to
// the debug key so anyone can build from source.
val releaseSigning = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "app.parity.android"
    compileSdk = 37

    defaultConfig {
        // Decide the final ID before the first Play Store upload; after that it can never change.
        applicationId = "app.parity"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.3.0-beta.4"
    }

    // One APK per CPU type: ML Kit, translation and Tesseract ship large native libraries, so a
    // universal APK would carry three copies. Modern phones use the arm64-v8a APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (!releaseSigning.isEmpty) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources {
        // Tesseract model files must stay uncompressed so they can be copied quickly.
        noCompress += "traineddata"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text)
    // Chinese, Japanese, Korean and Devanagari labels, read live like Latin ones (design §14).
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.langid)
    implementation(libs.mlkit.translate)
    implementation(libs.play.location)
    implementation(libs.zxing.core)
    implementation(libs.androidx.exifinterface)
    implementation(libs.tesseract4android)
}
