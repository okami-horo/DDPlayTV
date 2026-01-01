import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

dependencies {
    api(project(":data_component"))
    implementation(project(":core_system_component"))

    api(Dependencies.AndroidX.room)
    api(Dependencies.AndroidX.lifecycle_livedata)
    implementation(Dependencies.AndroidX.room_ktx)
    kapt(Dependencies.AndroidX.room_compiler)

    api(Dependencies.Kotlin.coroutines_core)

    implementation(Dependencies.Tencent.mmkv)

    // MMKV 配置表注解处理器：当前仍复用 common_component/libs 中的编译器 jar
    implementation(files("../common_component/libs/mmkv-annotation.jar"))
    kapt(files("../common_component/libs/mmkv-compiler.jar"))
}

android {
    namespace = "com.xyoye.core_database_component"
}
