import setup.moduleSetup
import java.util.Properties

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

android {
    sourceSets {
        named("main").configure {
            jniLibs.srcDir("libs")
        }
    }

    defaultConfig {
        val localProperties =
            Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) {
                    file.inputStream().use { load(it) }
                }
            }

        val buglyAppId =
            System.getenv("BUGLY_APP_ID")
                ?: project.findProperty("BUGLY_APP_ID")?.toString()
                ?: localProperties.getProperty("BUGLY_APP_ID")
                ?: "DEFAULT_BUGLY_ID"
        buildConfigField("String", "BUGLY_APP_ID", buildConfigString(buglyAppId))

        val dandanAppId =
            System.getenv("DANDAN_APP_ID")
                ?: project.findProperty("DANDAN_APP_ID")?.toString()
                ?: localProperties.getProperty("DANDAN_APP_ID")
                ?: ""
        buildConfigField("String", "DANDAN_APP_ID", buildConfigString(dandanAppId))

        val dandanAppSecret =
            System.getenv("DANDAN_APP_SECRET")
                ?: project.findProperty("DANDAN_APP_SECRET")?.toString()
                ?: localProperties.getProperty("DANDAN_APP_SECRET")
                ?: ""
        buildConfigField("String", "DANDAN_APP_SECRET", buildConfigString(dandanAppSecret))

        buildConfigField(
            "boolean",
            "DANDAN_DEV_CREDENTIAL_INJECTED",
            (dandanAppId.isNotBlank() && dandanAppSecret.isNotBlank()).toString(),
        )
    }
    namespace = "com.xyoye.core_system_component"
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {
    api(project(":core_contract_component"))
    implementation(project(":core_log_component"))

    // BaseApplication is part of public API; expose Coil types to consumers.
    api(Dependencies.Github.coil)
    api(Dependencies.Github.coil_video)

    api(Dependencies.AndroidX.core)
    api(Dependencies.AndroidX.appcompat)
    api(Dependencies.AndroidX.activity_ktx)
    api(Dependencies.AndroidX.startup)

    api(Dependencies.Kotlin.coroutines_core)
    api(Dependencies.Kotlin.coroutines_android)

    implementation(Dependencies.Tencent.mmkv)

    // MMKV 配置表注解处理器：当前仍复用 common_component/libs 中的编译器 jar
    implementation(files("../common_component/libs/mmkv-annotation.jar"))
    kapt(files("../common_component/libs/mmkv-compiler.jar"))

    kapt(Dependencies.Alibaba.arouter_compiler)
}

