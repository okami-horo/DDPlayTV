import setup.moduleSetup

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    kotlin("android")
    kotlin("kapt")
    id("com.google.devtools.ksp")
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

    implementation(Dependencies.AndroidX.lifecycle_livedata)
    implementation(Dependencies.AndroidX.paging)
    implementation(Dependencies.Github.jsoup)

    // Keep repository wrappers internal to storage implementation.
    implementation(project(":repository:seven_zip"))
    implementation(project(":repository:thunder"))

    implementation(files("libs/sardine-1.0.2.jar"))
    implementation(files("libs/simple-xml-2.7.1.jar"))

    // Keep NanoHTTPD visible transitively because public classes in this module
    // (e.g. HttpPlayServer/HttpServer) currently expose NanoHTTPD supertypes.
    api(Dependencies.Github.nano_http)
    implementation(Dependencies.Github.smbj)
    implementation(Dependencies.Github.dcerpc)
    implementation(Dependencies.Apache.commons_net)

    implementation(Dependencies.Tencent.mmkv)
    implementation(Dependencies.Square.moshi)
    ksp(Dependencies.Square.moshi_codegen)

    testImplementation(Dependencies.Testing.robolectric)
}

android {
    namespace = "com.xyoye.core_storage_component"
}
