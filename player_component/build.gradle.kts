import setup.moduleSetup

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

val media3Version = project.findProperty("media3Version")?.toString() ?: "1.8.0"

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.media3") {
            useVersion(media3Version)
        }
    }
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("libs")
        }
    }
    namespace = "com.xyoye.player_component"
}

kapt {
    arguments {
        arg("AROUTER_MODULE_NAME", name)
    }
}

dependencies {
    // Media3 dependencies will be switched on once the migration is ready.

    implementation(project(":common_component"))
    implementation(project(":repository:panel_switch"))
    implementation(project(":repository:danmaku"))
    implementation(project(":repository:video_cache"))

    implementation(Dependencies.Github.keyboard_panel)

    // TODO 暂时移除，编译出64位后再考虑重新添加
    //implementation "com.github.ctiao:ndkbitmap-armv7a:0.9.21"

    implementation(Dependencies.Google.exoplayer)
    implementation(Dependencies.Google.exoplayer_core)
    implementation(Dependencies.Google.exoplayer_dash)
    implementation(Dependencies.Google.exoplayer_hls)
    implementation(Dependencies.Google.exoplayer_smoothstraming)
    implementation(Dependencies.Google.exoplayer_rtmp)

    implementation(Dependencies.VLC.vlc)

    kapt(Dependencies.Alibaba.arouter_compiler)

    testImplementation(Dependencies.Kotlin.coroutines_test)
    androidTestImplementation(Dependencies.Kotlin.coroutines_test)
}
