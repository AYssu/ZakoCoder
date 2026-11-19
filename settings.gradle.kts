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
        maven("https://jitpack.io") {
            content { includeGroup("com.github.topjohnwu.libsu") }
        }
    }
}
rootProject.name = "ZakoCoder"
include(":app")

include(":terminal-emulator", ":terminal-view")
project(":terminal-emulator").projectDir = file("third_party/terminal-emulator")
project(":terminal-view").projectDir = file("third_party/terminal-view")
