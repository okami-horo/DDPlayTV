import setup.moduleSetup

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

        val media3FallbackFlag =
            project.findProperty("media3_enabled")?.toString()?.equals("true", true) ?: false
        buildConfigField("boolean", "MEDIA3_ENABLED_FALLBACK", media3FallbackFlag.toString())

        val media3Version = project.findProperty("media3Version")?.toString() ?: "1.9.0"
        buildConfigField("String", "MEDIA3_VERSION", "\"$media3Version\"")
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

    api(project(":core_contract_component"))
    api(project(":core_log_component"))
    api(project(":core_system_component"))
    api(project(":core_network_component"))
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
    api(Dependencies.AndroidX.preference)
    api(Dependencies.AndroidX.activity_ktx)

    api(Dependencies.Google.material)
    api(Dependencies.Apache.commons_net)

    api(Dependencies.Tencent.mmkv)
    // Bugly & crash 上报已收敛到 core_log_component

    // Network stack has been extracted to core_network_component.

    api(Dependencies.Github.coil)
    api(Dependencies.Github.coil_video)
    api(Dependencies.Github.nano_http)
    api(Dependencies.Github.smbj)
    api(Dependencies.Github.dcerpc)

    api(Dependencies.Huawei.scan)

    kapt(files("libs/mmkv-compiler.jar"))
    kapt(Dependencies.AndroidX.room_compiler)
    kapt(Dependencies.Alibaba.arouter_compiler)
    implementation(kotlin("reflect"))

    testImplementation(Dependencies.Kotlin.coroutines_test)
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
