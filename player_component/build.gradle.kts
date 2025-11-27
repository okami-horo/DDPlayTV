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
    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DASS_GPU_RENDER=ON")
                cppFlags += listOf("-DASS_GPU_RENDER")
            }
        }
    }
    packagingOptions {
        jniLibs {
            pickFirsts.add("lib/**/libc++_shared.so")
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("libs")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
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
    implementation(project(":common_component"))
    implementation(project(":repository:panel_switch"))
    implementation(project(":repository:danmaku"))
    implementation(project(":repository:video_cache"))

    implementation(Dependencies.Github.keyboard_panel)

    // TODO 暂时移除，编译出64位后再考虑重新添加
    //implementation "com.github.ctiao:ndkbitmap-armv7a:0.9.21"

    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3Version")
    implementation("androidx.media3:media3-exoplayer-workmanager:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-cast:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    testImplementation("androidx.media3:media3-test-utils:$media3Version")
    androidTestImplementation("androidx.media3:media3-test-utils:$media3Version")

    implementation(Dependencies.VLC.vlc)

    kapt(Dependencies.Alibaba.arouter_compiler)

    testImplementation(Dependencies.Kotlin.coroutines_test)
    androidTestImplementation(Dependencies.Kotlin.coroutines_test)
}
