import setup.moduleSetup

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    kotlin("android")
}

moduleSetup()

dependencies {
    api(project(":core_contract_component"))
    implementation(project(":core_network_component"))
    implementation(project(":core_system_component"))
    implementation(project(":core_log_component"))
    implementation(project(":core_database_component"))

    api(project(":repository:seven_zip"))
    api(project(":repository:thunder"))

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
