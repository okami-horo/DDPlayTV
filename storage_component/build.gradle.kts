import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {
    implementation(project(":core_ui_component"))
    implementation(project(":core_system_component"))
    implementation(project(":core_log_component"))
    implementation(project(":core_network_component"))
    implementation(project(":core_database_component"))
    implementation(project(":core_storage_component"))
    implementation(project(":core_metadata_component"))
    implementation(project(":core_contract_component"))
    implementation(project(":data_component"))

    implementation(Dependencies.Huawei.scan)

    kapt(Dependencies.Alibaba.arouter_compiler)
    testImplementation(Dependencies.Testing.robolectric)
    testImplementation(Dependencies.Testing.androidx_test_core)
    testImplementation(Dependencies.Kotlin.coroutines_test)
}
android {
    namespace = "com.xyoye.storage_component"
}
