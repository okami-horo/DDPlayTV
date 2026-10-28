import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    id("com.google.devtools.ksp")
}

moduleSetup()

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

dependencies {
    // Avoid leaking :data_component transitively; consumers should declare it explicitly when used.
    implementation(project(":data_component"))
    implementation(project(":core_system_component"))

    api(Dependencies.AndroidX.room)
    api(Dependencies.AndroidX.lifecycle_livedata)
    implementation(Dependencies.AndroidX.room_ktx)
    ksp(Dependencies.AndroidX.room_compiler)
    // KSP resolves @IgnoredOnParcel on entities compiled in :data_component.
    compileOnly(kotlin("parcelize-runtime"))

    api(Dependencies.Kotlin.coroutines_core)

    implementation(Dependencies.Tencent.mmkv)

    // MMKV 配置表注解处理器：jar 统一放在 repository/mmkv
    implementation(files("../repository/mmkv/mmkv-annotation.jar"))
    kapt(files("../repository/mmkv/mmkv-compiler.jar"))

    testImplementation(Dependencies.Testing.androidx_test_core)
    testImplementation(Dependencies.Testing.robolectric)
}

android {
    namespace = "com.xyoye.core_database_component"
}
