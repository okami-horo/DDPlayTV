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

    implementation(Dependencies.Kotlin.lib)
    implementation(Dependencies.Github.banner)

    kapt(Dependencies.Alibaba.arouter_compiler)
}
android {
    namespace = "com.xyoye.anime_component"
}
