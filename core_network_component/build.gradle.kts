import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
}

moduleSetup()

dependencies {
    // Avoid leaking :data_component transitively; consumers should declare it explicitly when used.
    implementation(project(":data_component"))

    implementation(project(":core_log_component"))
    implementation(project(":core_system_component"))

    api(Dependencies.Square.retrofit)
    implementation(Dependencies.Square.retrofit_moshi)
    implementation(Dependencies.Square.moshi_kotlin)

    implementation(Dependencies.Kotlin.coroutines_core)
    implementation(Dependencies.Kotlin.coroutines_android)

    // Moshi KotlinJsonAdapterFactory uses reflection at runtime.
    implementation(kotlin("reflect"))

    testImplementation(project(":core_contract_component"))
    testImplementation(Dependencies.Kotlin.coroutines_test)
}

android {
    namespace = "com.xyoye.core_network_component"
}
