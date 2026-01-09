import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

android {
    namespace = "com.xyoye.core_ui_component"
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {
    implementation(project(":core_contract_component"))
    implementation(project(":core_system_component"))
    implementation(project(":core_log_component"))

    // Dialog/UI public API leaks some data types.
    api(project(":data_component"))

    api(project(":repository:immersion_bar"))

    api(Dependencies.Kotlin.coroutines_core)
    api(Dependencies.Kotlin.coroutines_android)

    api(Dependencies.AndroidX.core)
    api(Dependencies.AndroidX.appcompat)
    api(Dependencies.AndroidX.activity_ktx)
    api(Dependencies.AndroidX.lifecycle_viewmodel)
    api(Dependencies.AndroidX.lifecycle_runtime)
    api(Dependencies.AndroidX.lifecycle_livedata)

    api(Dependencies.AndroidX.constraintlayout)
    api(Dependencies.AndroidX.recyclerview)
    api(Dependencies.AndroidX.swiperefreshlayout)
    api(Dependencies.AndroidX.palette)
    api(Dependencies.AndroidX.paging)
    api(Dependencies.AndroidX.preference)

    api(Dependencies.Google.material)

    api(Dependencies.Github.coil)
    api(Dependencies.Github.coil_video)

    // Adapter diff creator uses Kotlin reflection at runtime.
    implementation(kotlin("reflect"))

    kapt(Dependencies.Alibaba.arouter_compiler)
}
