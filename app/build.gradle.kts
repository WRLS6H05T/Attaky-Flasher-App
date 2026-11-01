import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.attaky.flasher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.attaky.flasher"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        // Shown on the splash screen under the wordmark, e.g. "V0.1.0-A001".
        versionName = "0.1.0-A001"
        // SVG/vector + WebView assets only; no need to split ABI or language.
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.webkit:webkit:1.18.0")
    // USB-serial drivers: CH34x (Attaky deck USB-C), CP210x, FTDI, CDC-ACM, PL2303.
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
