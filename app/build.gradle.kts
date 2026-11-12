import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.namchamvinhcuu.signagekiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.namchamvinhcuu.signagekiosk"
        minSdk = 26 // WebViewClient.onRenderProcessGone (API 26)
        targetSdk = 34
        // Per release: ./gradlew assembleRelease -PversionCode=2 -PversionName=0.2.0 (see docs/RELEASE.md).
        versionCode = providers.gradleProperty("versionCode").orNull
            ?.let { raw -> raw.toIntOrNull()?.takeIf { it > 0 } ?: error("-PversionCode must be a positive integer, got '$raw'") }
            ?: 1
        versionName = providers.gradleProperty("versionName").orNull
            ?.also { raw -> require(Regex("^[A-Za-z0-9._-]{1,32}$").matches(raw)) { "-PversionName must be 1-32 chars of A-Za-z0-9._-, got '$raw'" } }
            ?: "0.1.0"
        // The app's own public repository is the GitHub update source (plan kiosk-selfupdate D8). Build-time only, never
        // a dialog field or an intent extra. Forks: -PupdateGithubRepo=<owner>/<name>.
        val updateRepo = providers.gradleProperty("updateGithubRepo").orNull ?: "namchamvinhcuu/signage-kiosk"
        require(Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$").matches(updateRepo)) { "-PupdateGithubRepo must be <owner>/<name>, got '$updateRepo'" }
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"$updateRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Release key from <repo>/keystore.properties (never in git): storeFile, storePassword, keyAlias, keyPassword.
    // Every TV update must be signed with this SAME key. Without the file the release APK is left unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }
    if (keystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Debug-only update test source (plan kiosk-selfupdate D1b): -PupdateTestOrigin=http://10.0.2.2:PORT makes a DEBUG
    // build read updates from that cleartext origin (src/debug/.../DebugOriginSource). Never an intent extra, and the
    // release build field is always empty: the GitHub allow-list of release builds is never loosened.
    val updateTestOrigin = providers.gradleProperty("updateTestOrigin").orNull.orEmpty()
    require(updateTestOrigin.isEmpty() || Regex("^http://[A-Za-z0-9.-]+(:[0-9]{1,5})?$").matches(updateTestOrigin)) {
        "-PupdateTestOrigin must look like http://host[:port], got '$updateTestOrigin'"
    }

    buildTypes {
        debug {
            buildConfigField("String", "UPDATE_TEST_ORIGIN", "\"$updateTestOrigin\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "UPDATE_TEST_ORIGIN", "\"\"")
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
