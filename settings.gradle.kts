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
        // Xposed / LSPosed 模块 API（compileOnly，不会打进 APK）
        maven(url = "https://api.xposed.info/")
    }
}

rootProject.name = "MiuiNotifShelter"
include(":app")