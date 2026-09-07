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
        // TDLib AAR (org.drinkless.tdlib + нативные .so) — зеркало TGX-Android prebuilt
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "sms-forwarder"
include(":app")