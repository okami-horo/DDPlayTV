import setup.moduleSetup

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    kotlin("android")
}

moduleSetup()

dependencies {
    // Avoid leaking contract transitively; consumers should declare :core_contract_component explicitly.
    implementation(project(":core_contract_component"))
    implementation(project(":data_component"))
    implementation(project(":core_network_component"))
    implementation(project(":core_system_component"))
    implementation(project(":core_log_component"))
    implementation(project(":core_database_component"))
    implementation(project(":bilibili_component"))

    // Keep repository wrappers internal to storage implementation.
    implementation(project(":repository:seven_zip"))
    implementation(project(":repository:thunder"))

    api(files("libs/sardine-1.0.2.jar"))
    api(files("libs/simple-xml-2.7.1.jar"))

    api(Dependencies.Github.nano_http)
    api(Dependencies.Github.smbj)
    api(Dependencies.Github.dcerpc)
    api(Dependencies.Apache.commons_net)

    implementation(Dependencies.Tencent.mmkv)
}

android {
    namespace = "com.xyoye.core_storage_component"
}
