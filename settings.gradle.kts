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

rootProject.name = "FraudCallDetector"

include(":app")
include(":core")
include(":domain")
include(":data")
include(":feature:callmonitor")
include(":feature:audiorecorder")
include(":feature:stt")
include(":feature:frauddetection")
include(":feature:notifications")
include(":feature:callhistory")
