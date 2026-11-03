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
        versionCode = 18
        // Shown on the splash screen under the wordmark, e.g. "V0.1.0-A001".
        versionName = "0.1.5-A007"
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
    // The launcher's wire protocol and the OTA manifest are plain Kotlin with no
    // Android in them, so they are tested on the JVM against a fake launcher
    // instead of only against a core on the bench.
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM: the mockable android.jar stub would throw.
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.webkit:webkit:1.18.0")
    // USB-serial drivers: CH34x (Attaky core USB-C), CP210x, FTDI, CDC-ACM, PL2303.
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
