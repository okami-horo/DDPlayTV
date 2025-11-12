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
    implementation(project(":common_component"))

    implementation(Dependencies.Huawei.scan)

    kapt(Dependencies.Alibaba.arouter_compiler)
}
android {
    namespace = "com.xyoye.storage_component"
    defaultConfig {
        val media3Version = project.findProperty("media3Version")?.toString() ?: "1.8.0"
        buildConfigField("String", "MEDIA3_VERSION", "\"$media3Version\"")
    }
}
