import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

dependencies {
    // Avoid leaking :data_component transitively; consumers should declare it explicitly when used.
    implementation(project(":data_component"))

    implementation(Dependencies.AndroidX.core)
    implementation(Dependencies.Tencent.mmkv)
    implementation(Dependencies.Tencent.bugly)

    // MMKV 配置表注解处理器：jar 统一放在 repository/mmkv
    implementation(files("../repository/mmkv/mmkv-annotation.jar"))
    kapt(files("../repository/mmkv/mmkv-compiler.jar"))

    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
}

android {
    namespace = "com.xyoye.core_log_component"
}
