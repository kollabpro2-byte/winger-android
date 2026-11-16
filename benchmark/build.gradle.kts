plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

/**
 * Модуль записи Baseline Profile: он не входит в APK, а только «прогоняет»
 * приложение на устройстве/эмуляторе и записывает, какие методы реально
 * выполняются при старте. Полученный профиль AGP кладёт в приложение, и ART
 * заранее компилирует эти методы уже на телефоне пользователя.
 */
android {
    namespace = "pro.wingerapp.vpn.benchmark"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    // Тест инструментирует сам себя — требование baselineprofile-плагина.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    // Использовать подключённый по adb эмулятор или устройство.
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
