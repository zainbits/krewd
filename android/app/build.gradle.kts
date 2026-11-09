import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing key, kept outside the repo: a properties file with storeFile (relative to the file),
// storePassword, keyAlias and keyPassword. Default ~/.android/keystores/release.properties;
// override with ANDROID_SIGNING_PROPERTIES. Without it, release APKs are unsigned and debug APKs use
// the Android debug key. One key for every build means a release installs over a debug build.
val signingFile = System.getenv("ANDROID_SIGNING_PROPERTIES")?.let(::File)
    ?: File(System.getProperty("user.home"), ".android/keystores/release.properties")
val signing = signingFile.takeIf { it.isFile }?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "app.crew"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.crew"
        minSdk = 36
        targetSdk = 37
        versionCode = 7
        versionName = "0.4.3"
    }

    signingConfigs {
        if (signing != null) {
            create("shared") {
                storeFile = signingFile.absoluteFile.parentFile.resolve(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (signing != null) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            if (signing != null) signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)

    testImplementation(libs.junit)
    // Real org.json for unit tests (Android stubs throw "not mocked").
    testImplementation(libs.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
