import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// One version for the app and the server: VERSION at the repo root. The version code follows from it
// (0.8.1 is 801), so it always grows with the version.
val krewdVersion = providers.fileContents(rootProject.layout.projectDirectory.file("../VERSION")).asText.get().trim()
val krewdVersionCode = krewdVersion.split(".").map(String::toInt).let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

// Signing key, kept outside the repo: a properties file with storeFile (relative to the file),
// storePassword, keyAlias and keyPassword. Default ~/.android/keystores/release.properties;
// override with ANDROID_SIGNING_PROPERTIES. Without it, release APKs are unsigned and debug APKs use
// the Android debug key. One key for every build means a release installs over a debug build.
val signingFile = System.getenv("ANDROID_SIGNING_PROPERTIES")?.let(::File)
    ?: File(System.getProperty("user.home"), ".android/keystores/release.properties")
val signing = signingFile.takeIf { it.isFile }?.let { file -> Properties().apply { file.inputStream().use(::load) } }

// Push (FCM) for the "google" flavor: the Firebase project's Android app config, the google-services.json that the
// Firebase console gives for app.krewd. Kept outside the repo (it names the owner's own Firebase project):
// ~/.config/krewd/firebase/google-services.json, or KREWD_FIREBASE_CONFIG. Without it the google flavor still builds
// and the app says push is not set up in this build. No google-services Gradle plugin: the app starts Firebase itself
// from these four values (push/PushProvider.kt).
val firebaseFile = System.getenv("KREWD_FIREBASE_CONFIG")?.let(::File)
    ?: File(System.getProperty("user.home"), ".config/krewd/firebase/google-services.json")
val firebase: Map<String, String> = providers.fileContents(layout.projectDirectory.file(firebaseFile.absolutePath)).asText.orNull
    ?.let { text ->
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>
        val project = json["project_info"] as? Map<String, Any?> ?: emptyMap()
        val client = (json["client"] as? List<Map<String, Any?>>).orEmpty().firstOrNull { c ->
            ((c["client_info"] as? Map<String, Any?>)?.get("android_client_info") as? Map<String, Any?>)?.get("package_name") == "app.krewd"
        }
        val appId = (client?.get("client_info") as? Map<String, Any?>)?.get("mobilesdk_app_id") as? String
        val apiKey = ((client?.get("api_key") as? List<Map<String, Any?>>)?.firstOrNull())?.get("current_key") as? String
        if (appId == null || apiKey == null) emptyMap() else mapOf(
            "APP_ID" to appId,
            "API_KEY" to apiKey,
            "PROJECT_ID" to (project["project_id"] as? String).orEmpty(),
            "SENDER_ID" to (project["project_number"] as? String).orEmpty(),
        )
    } ?: emptyMap()

android {
    namespace = "app.krewd"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.krewd"
        minSdk = 36
        targetSdk = 37
        versionCode = krewdVersionCode
        versionName = krewdVersion
        buildConfigField("boolean", "DEMO", "false")
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
        // The demo app for screenshots and videos (./master.sh demo app): installs beside the real app and
        // connects to the demo server. The only difference: screen recording is allowed on the sign-in sheet.
        create("demo") {
            initWith(getByName("release"))
            applicationIdSuffix = ".demo"
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "DEMO", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // google: push through Firebase Cloud Messaging (Google Play services). plain: no Google push; alerts arrive
    // only while the app is open. Both are the same app (app.krewd), signed with the same key.
    flavorDimensions += "push"
    productFlavors {
        create("google") {
            dimension = "push"
            isDefault = true
            for (name in listOf("APP_ID", "API_KEY", "PROJECT_ID", "SENDER_ID")) {
                buildConfigField("String", "FIREBASE_$name", "\"${firebase[name].orEmpty()}\"")
            }
        }
        create("plain") {
            dimension = "push"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.meniscus)
    implementation(libs.vitreous)
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
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)

    "googleImplementation"(platform(libs.firebase.bom))
    "googleImplementation"(libs.firebase.messaging)

    testImplementation(libs.junit)
    // Real org.json for unit tests (Android stubs throw "not mocked").
    testImplementation(libs.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
