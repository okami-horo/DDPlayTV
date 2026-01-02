import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
}

moduleSetup()

dependencies {
    api(project(":data_component"))
    api(Dependencies.Alibaba.arouter_api)

    api(Dependencies.AndroidX.appcompat)
    api(Dependencies.AndroidX.lifecycle_livedata)

    // Media3SessionClient/Store expose StateFlow in public API.
    api(Dependencies.Kotlin.coroutines_core)
}

android {
    namespace = "com.xyoye.core_contract_component"
}
