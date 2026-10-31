import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
}

moduleSetup()

dependencies {
    implementation(project(":data_component"))
    implementation(project(":core_contract_component"))
    implementation(project(":core_network_component"))
    implementation(project(":core_database_component"))
    implementation(project(":core_storage_component"))
    implementation(project(":core_system_component"))
    implementation(Dependencies.AndroidX.room_ktx)
    testImplementation(Dependencies.Testing.robolectric)
    testImplementation(Dependencies.Testing.androidx_test_core)
    testImplementation(Dependencies.Kotlin.coroutines_test)
}

android {
    namespace = "com.xyoye.core_metadata_component"
}
