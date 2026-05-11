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

    // The CERT_CONFIG_URL bootstraps the GET /v1/cert-config fetch that
    // overrides RuntimeConfigDefaults at launch. Debug points at the lab
    // backend on the dev Mac (192.168.10.233:8080); release points at the
    // production hostname declared in the contract's openapi.yaml. Keep
    // the network_security_config.xml allow-list aligned with the debug IP.
    //
    // APP_UPDATE_URL feeds the GET /v1/app/version manifest fetch that
    // gates "Run cert" on having the latest version. APP_SIGNING_CERT_SHA256
    // is the pinned hex SHA-256 of the platform signing certificate the
    // installer must see on any downloaded APK before installing it.
    // Empty string disables the pin (debug builds — sign with the local
    // debug keystore which varies per-machine). Release fills this in
    // from the KEYSTORE_CERT_SHA256 env var (the CI computes the hash
    // from the production signing cert at build time).
    //
    // Silent-vs-interactive install is decided at runtime by whether the
    // OS has granted INSTALL_PACKAGES (signature permission, only granted
    // to apps signed with the platform key). No build-time flag.
    val releaseSigningCertSha = System.getenv("KEYSTORE_CERT_SHA256")?.lowercase() ?: ""

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
        debug {
            // Dev backend lives on port 18080 (not the common :8080) to
            // avoid clashes with Docker/OrbStack/etc. on the dev Mac.
            // scripts/dev-update-server.py defaults to the same port.
            buildConfigField("String", "CERT_CONFIG_URL", "\"http://192.168.10.233:18080/v1/cert-config\"")
            buildConfigField("String", "APP_UPDATE_URL", "\"http://192.168.10.233:18080/v1/app/version\"")
            buildConfigField("String", "APP_SIGNING_CERT_SHA256", "\"\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (canSignRelease) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            buildConfigField("String", "CERT_CONFIG_URL", "\"https://certifier-api.gethotwired.com/v1/cert-config\"")
            buildConfigField("String", "APP_UPDATE_URL", "\"https://certifier-api.gethotwired.com/v1/app/version\"")
            buildConfigField("String", "APP_SIGNING_CERT_SHA256", "\"$releaseSigningCertSha\"")
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
        buildConfig = true
    }

    // Ookla binary is shipped as jniLibs/armeabi-v7a/libookla.so. Modern
    // AGP keeps .so files compressed inside the APK (linker-mapped, faster
    // install) but that prevents Runtime.exec() from running them. Legacy
    // packaging extracts the libs to nativeLibraryDir on install where
    // they're real files SELinux allows untrusted_app to execute.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    testImplementation(libs.mockwebserver)
}
