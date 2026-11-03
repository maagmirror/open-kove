plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.zanderp.opencfmoto"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.whoismaag.openkove"
        minSdk = 29
        targetSdk = 36
        versionCode = 39
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phones only: x86/x86_64 are emulator-only and add ~38 MB of native libs to the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Default OpenRouteService key used when the rider hasn't entered their own. Supply it via
        // `-PorsApiKey=...`, an `orsApiKey` in gradle.properties, or the ORS_API_KEY env var so the
        // key isn't hardcoded in source. Empty → routing falls back to the OSRM demo, then beeline.
        val orsDefaultKey = (project.findProperty("orsApiKey") as String?)
            ?: System.getenv("ORS_API_KEY")
            ?: ""
        buildConfigField("String", "ORS_API_KEY", "\"$orsDefaultKey\"")

        // Anonymous telemetry Worker base URL (no trailing slash). Empty disables uploads.
        // Override: -PtelemetryUrl=https://….workers.dev  or TELEMETRY_URL env / gradle.properties
        val telemetryUrl = (project.findProperty("telemetryUrl") as String?)
            ?: System.getenv("TELEMETRY_URL")
            ?: ""
        buildConfigField("String", "TELEMETRY_URL", "\"$telemetryUrl\"")
    }

    // Compress dex and native libs inside the APK (~60 → ~25 MB download). Android extracts the
    // libs at install time, so the installed size grows a bit; behaviour is unchanged.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        dex {
            useLegacyPackaging = true
        }
    }

    // Release key from the environment (CI secrets) or gradle.properties; absent → unsigned release.
    val releaseStore = (project.findProperty("releaseStoreFile") as String?)
        ?: System.getenv("RELEASE_STORE_FILE")
    val releaseStoreFile = releaseStore?.let { file(it) }?.takeIf { it.exists() }
    if (releaseStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = releaseStoreFile
            storePassword = (project.findProperty("releaseStorePassword") as String?)
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = (project.findProperty("releaseKeyAlias") as String?)
                ?: System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = (project.findProperty("releaseKeyPassword") as String?)
                ?: System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    // Fixed key for the GitHub APKs. CI restores it from the DEBUG_KEYSTORE_BASE64 secret and points
    // GITHUB_KEYSTORE_FILE at it; Gradle's own debug keystore location is not reliable on the runner.
    // Locally, without the file, the default debug key is used.
    val githubStoreFile = ((project.findProperty("githubKeystoreFile") as String?)
        ?: System.getenv("GITHUB_KEYSTORE_FILE"))?.let { file(it) }?.takeIf { it.exists() }
    val githubSigning = if (githubStoreFile != null) {
        signingConfigs.create("github") {
            storeFile = githubStoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    } else {
        signingConfigs.getByName("debug")
    }

    // github: full app, distributed as APK from GitHub Releases, always signed with the key above
    // so installed copies keep updating. Only the play flavor uses the Play release key.
    // play: same app without the accessibility service and the background-kill permission.
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "AUTO_SERVER_START", "true")
            signingConfig = githubSigning
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "AUTO_SERVER_START", "false")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // No signingConfig here: a build type's would override the flavor's choice above.
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    // Wireless Android Auto needs the packaged aa_privkey (same as prior releases).
    lint {
        disable += "PackagedPrivateKey"
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.mlkit.barcodescanner)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.jmdns)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    implementation(libs.osmdroid)
    implementation(libs.maplibre)
    // Compile-time OkHttp for MapLibre cellular pin (MapLibre brings it as runtime only).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}