plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// versionName is read from the FISION_VERSION_NAME env var (set by CI from
// the git tag, e.g. v0.2.0 -> 0.2.0). versionCode comes from
// FISION_VERSION_CODE (CI passes `git rev-list --count HEAD`). Local builds
// fall back to a placeholder so devs aren't forced to set env vars.
val ciVersionName: String = System.getenv("FISION_VERSION_NAME") ?: "0.0.0-local"
val ciVersionCode: Int = System.getenv("FISION_VERSION_CODE")?.toIntOrNull() ?: 1

// Release signing reads from a base64-encoded keystore that CI decodes to a
// temp file. Local release builds work too if KEYSTORE_PATH points to the
// real .jks; otherwise the release buildType falls back to the debug
// signing config so `./gradlew assembleRelease` still produces an APK
// locally for ad-hoc testing.
val ksPath: String? = System.getenv("KEYSTORE_PATH")
val ksPassword: String? = System.getenv("KEYSTORE_PASSWORD")
val ksKeyAlias: String? = System.getenv("KEY_ALIAS")
val canSignRelease = ksPath != null && ksPassword != null && ksKeyAlias != null

android {
    namespace = "com.hotwire.fisiontv.networkqual"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hotwire.fisiontv.networkqual"
        minSdk = 24
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    if (canSignRelease) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                keyAlias = ksKeyAlias
                keyPassword = ksPassword  // PKCS12: store and key passwords are the same
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSignRelease) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
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
    }

    testOptions {
        unitTests {
            // android.util.Log and other framework stubs throw by default
            // in unit tests; flip them to silently return zero so the
            // engine's logging calls don't blow up the suite.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
