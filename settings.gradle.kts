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

rootProject.name = "Veltrio"

// shared: the core, the services, the player and the screens, also used by the Android
// TV app (veltrio-androidtv, as a git submodule). app: the phone app.
include(":shared", ":app")
