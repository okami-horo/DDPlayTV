import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
}

moduleSetup()

dependencies {
    implementation(project(":core_contract_component"))
    implementation(project(":core_network_component"))
    implementation(project(":core_log_component"))
    implementation(project(":core_system_component"))
    implementation(project(":core_database_component"))
    implementation(project(":data_component"))

    implementation(Dependencies.Tencent.mmkv)

    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
}

android {
    namespace = "com.xyoye.bilibili_component"
}
