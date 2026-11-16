pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "WingerVPN"
include(":app")
// Модуль для записи Baseline Profile (запускается на эмуляторе/устройстве).
include(":benchmark")
