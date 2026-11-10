pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "geulbeot"

// core-hwp is a pure Kotlin/JVM module: it builds and unit-tests without the Android SDK.
include(":core-hwp")

// The Android app module needs the Android SDK. Android Studio writes local.properties on first
// open, so it is picked up automatically there. On a machine without the SDK (e.g. a CI container
// where dl.google.com is unreachable) the build still works for :core-hwp alone.
val androidSdkAvailable =
    file("local.properties").exists() ||
        System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null
if (androidSdkAvailable) {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle("[geulbeot] Android SDK not found - skipping :app. Only :core-hwp is configured.")
    }
}
