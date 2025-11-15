import setup.applicationSetup

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

applicationSetup()

val media3FallbackFlag =
    project.findProperty("media3_enabled")?.toString()?.equals("true", true) ?: false

android {
    namespace = "com.xyoye.dandanplay"
    compileSdk = Versions.compileSdkVersion
    defaultConfig {
        applicationId = Versions.applicationId
        minSdk = Versions.minSdkVersion
        targetSdk = Versions.targetSdkVersion
        targetSdk = Versions.targetSdkVersion
        versionCode = Versions.versionCode
        versionName = Versions.versionName
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "boolean",
            "MEDIA3_ENABLED_FALLBACK",
            media3FallbackFlag.toString()
        )

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    packagingOptions {
        jniLibs {
            pickFirsts.add("lib/**/libc++_shared.so")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", project.name)
    }
}

dependencies {
    implementation(project(":common_component"))
    implementation(project(":player_component"))
    implementation(project(":anime_component"))
    implementation(project(":user_component"))
    implementation(project(":local_component"))
    implementation(project(":storage_component"))

    kapt(Dependencies.Alibaba.arouter_compiler)
}
