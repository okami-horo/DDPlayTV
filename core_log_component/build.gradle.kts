import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

dependencies {
    api(project(":data_component"))

    implementation(Dependencies.AndroidX.core)
    implementation(Dependencies.Tencent.mmkv)
    implementation(Dependencies.Tencent.bugly)

    // MMKV 配置表注解处理器：当前仍复用 common_component/libs 中的编译器 jar
    implementation(files("../common_component/libs/mmkv-annotation.jar"))
    kapt(files("../common_component/libs/mmkv-compiler.jar"))

    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
}

android {
    namespace = "com.xyoye.core_log_component"
}
