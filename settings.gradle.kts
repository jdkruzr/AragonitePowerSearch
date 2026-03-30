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

rootProject.name = "AragonitePowerSearch"
include(":app", ":fleece")
includeBuild("../AragoniteHWR") {
    dependencySubstitution {
        substitute(module("dev.aragonite:hwr")).using(project(":lib"))
    }
}
