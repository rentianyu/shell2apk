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
        // Shizuku（rikka）的库只在自己的仓库
        maven { url = uri("https://maven.rikka.app/releases") }
    }
}
rootProject.name = "shell2apk"
include(":app")
include(":runner")
