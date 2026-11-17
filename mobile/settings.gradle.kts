pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RechargeMobile"
include(":shared")       // Kotlin Multiplatform: all logic and all screens, for Android and iOS
include(":androidApp")   // thin Android shell: Application, Activity, push
// iosApp/ is the thin iOS shell (an Xcode project), not a Gradle module
