plugins {
    id("com.android.application")
}

android {
    namespace = "com.notifshelter.miui"
    // 说明：目标机是 Android 17 (API 37)，但 API 37 的平台包在 SDK 里是 "android-37.0"
    // 这种 minor 版本命名，只有 AGP 9.x 支持。本模块所有 hook 都走反射，
    // 编译期用不到任何高版本 API，因此这里用 36 编译即可，运行时在 17 上正常工作。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.notifshelter.miui"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 显式指定 debug 签名，避免 AGP 去创建默认 debug keystore
    // （在某些容器/CI 环境里 AGP 创建默认 keystore 会因为 JDK 的 cgroup 探测 NPE 而失败）
    signingConfigs {
        create("projectDebug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("projectDebug")
        }
        release {
            isMinifyEnabled = false
            // 本地打 release 包时同样用 debug 签名，方便直接装上验证；
            // 真要发布请换成自己的正式签名。
            signingConfig = signingConfigs.getByName("projectDebug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    lint {
        abortOnError = false
        // 关掉 release 的 lintVital 检查：它会在部分容器/CI 环境里因 JDK 探测容器信息而崩溃，
        // 跟本模块的正确性无关。发布前想跑 lint 可以单独执行 gradle lint。
        checkReleaseBuilds = false
    }
}

dependencies {
    // Xposed/LSPosed API：仅编译期使用，运行时由框架提供
    compileOnly("de.robv.android.xposed:api:82")
}