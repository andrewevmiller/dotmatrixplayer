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

rootProject.name = "DotMatrixMediaWidget"

// One installable app. :app is the only application module; the two widget
// modules below are libraries it depends on, so all three widgets ship in a
// single APK under a single applicationId and appear together in the launcher's
// widget picker.
include(":app")
include(":datawidget")
include(":scorewidget")
