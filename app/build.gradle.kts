plugins { id("com.android.application") }

android {
    namespace = "dev.wirelessadb.autostart"
    // 保持 36：本机 SDK 无 platform 37；本模块对 framework 全部走反射，不依赖编译期新 API。
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.wirelessadb.autostart"
        minSdk = 28
        targetSdk = 36
        versionCode = 25
        versionName = "1.2.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        // 模块运行在 system_server，靠 LSPosed 绕过 hidden-api 限制反射 framework AIDL；
        // lint 的 BlockedPrivateApi 属静态误报，放行这一条。
        disable += "BlockedPrivateApi"
    }
}

dependencies { compileOnly("io.github.libxposed:api:102.0.0") }
