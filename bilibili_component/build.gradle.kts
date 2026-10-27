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

    testImplementation(Dependencies.Testing.androidx_test_core)
    testImplementation(Dependencies.Testing.robolectric)
    testImplementation(Dependencies.Kotlin.coroutines_test)
}

android {
    namespace = "com.xyoye.bilibili_component"
}
