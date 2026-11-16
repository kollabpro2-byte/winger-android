plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    // Генерация Baseline Profile: профиль «горячих» методов вшивается в APK и
    // заранее компилируется ART'ом на устройстве пользователя.
    id("androidx.baselineprofile") version "1.2.4" apply false
}
