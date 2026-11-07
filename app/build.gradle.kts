import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key never lives in the repository: keystore.properties (gitignored)
// for local builds, environment variables for CI.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(prop: String, env: String): String? =
    keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStore = secret("storeFile", "WINGER_KS")

android {
    namespace = "pro.wingerapp.vpn"
    compileSdk = 35
    defaultConfig {
        applicationId = "pro.wingerapp.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 73
        versionName = "0.1.73"
    }
    // libbox.so is ~80 MB per ABI: ship one APK per architecture instead of a
    // 160 MB universal bundle.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = secret("storePassword", "WINGER_KS_PASS")
                keyAlias = secret("keyAlias", "WINGER_KEY_ALIAS")
                keyPassword = secret("keyPassword", "WINGER_KEY_PASS")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            buildConfigField("boolean", "CORE_LOG", "true")
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "CORE_LOG", "false")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(files("libs/libbox.aar"))
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.security:security-crypto:1.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
