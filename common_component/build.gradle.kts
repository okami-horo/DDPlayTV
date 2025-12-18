import setup.moduleSetup
import java.util.Properties

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
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
        // Use AndroidX JUnit4 runner so @RunWith(AndroidJUnit4::class) tests work on device
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APPLICATION_ID", "\"${Versions.applicationId}\"")

        val media3FallbackFlag =
            project.findProperty("media3_enabled")?.toString()?.equals("true", true) ?: false
        buildConfigField("boolean", "MEDIA3_ENABLED_FALLBACK", media3FallbackFlag.toString())

        val media3Version = project.findProperty("media3Version")?.toString() ?: "1.8.0"
        buildConfigField("String", "MEDIA3_VERSION", "\"$media3Version\"")

        val localProperties =
            Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) {
                    file.inputStream().use { load(it) }
                }
            }

        // 从环境变量或属性读取密钥，用于GitHub Actions注入
        // 本地开发时使用默认值，CI/CD时从Secrets注入
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
    namespace = "com.xyoye.common_component"
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {
    debugImplementation(Dependencies.Square.leakcanary)

    api(project(":data_component"))
    api(project(":repository:seven_zip"))
    api(project(":repository:immersion_bar"))
    api(project(":repository:thunder"))

    api(files("libs/sardine-1.0.2.jar"))
    api(files("libs/simple-xml-2.7.1.jar"))
    implementation(files("libs/mmkv-annotation.jar"))

    api(Dependencies.Kotlin.stdlib_jdk7)
    api(Dependencies.Kotlin.coroutines_core)
    api(Dependencies.Kotlin.coroutines_android)

    api(Dependencies.AndroidX.core)
    api(Dependencies.AndroidX.lifecycle_viewmodel)
    api(Dependencies.AndroidX.lifecycle_runtime)
    api(Dependencies.AndroidX.room_ktx)
    api(Dependencies.AndroidX.constraintlayout)
    api(Dependencies.AndroidX.recyclerview)
    api(Dependencies.AndroidX.swiperefreshlayout)
    api(Dependencies.AndroidX.appcompat)
    api(Dependencies.AndroidX.multidex)
    api(Dependencies.AndroidX.palette)
    api(Dependencies.AndroidX.paging)
    api(Dependencies.AndroidX.startup)
    api(Dependencies.AndroidX.preference)
    api(Dependencies.AndroidX.activity_ktx)

    api(Dependencies.Google.material)
    api(Dependencies.Apache.commons_net)

    api(Dependencies.Tencent.mmkv)
    implementation(Dependencies.Tencent.bugly)

    api(Dependencies.Square.retrofit)
    implementation(Dependencies.Square.retrofit_moshi)

    api(Dependencies.Github.coil)
    api(Dependencies.Github.coil_video)
    api(Dependencies.Github.nano_http)
    api(Dependencies.Github.smbj)
    api(Dependencies.Github.dcerpc)

    kapt(files("libs/mmkv-compiler.jar"))
    kapt(Dependencies.AndroidX.room_compiler)
    kapt(Dependencies.Alibaba.arouter_compiler)
    implementation(kotlin("reflect"))

    testImplementation(Dependencies.Kotlin.coroutines_test)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
