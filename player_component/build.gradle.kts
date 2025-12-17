import setup.moduleSetup
import java.io.File

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

moduleSetup()

val media3Version = project.findProperty("media3Version")?.toString() ?: "1.8.0"
val unstrippedJniLibsDir = layout.projectDirectory.dir("libs")
val strippedJniLibsDir = layout.buildDirectory.dir("strippedJniLibs")

fun computeNdkHostTag(ndkDir: File): String {
    val osName = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val hostTags = when {
        osName.contains("mac") && arch.contains("aarch64") ->
            listOf("darwin-arm64", "darwin-aarch64", "darwin-x86_64")
        osName.contains("mac") -> listOf("darwin-x86_64")
        osName.contains("win") -> listOf("windows-x86_64")
        osName.contains("linux") -> listOf("linux-x86_64")
        else -> emptyList()
    }
    return hostTags.firstOrNull { ndkDir.resolve("toolchains/llvm/prebuilt/$it/bin").isDirectory }
        ?: error("Unsupported host OS for NDK strip tools")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.media3") {
            useVersion(media3Version)
        }
    }
}

android {
    ndkVersion = "25.2.9519653"
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
            jniLibs.setSrcDirs(emptyList<String>())
        }
        getByName("debug") {
            jniLibs.srcDir(unstrippedJniLibsDir)
        }
        getByName("release") {
            jniLibs.srcDir(strippedJniLibsDir)
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

val stripReleaseJniLibs by tasks.registering {
    description = "Copy and strip release jniLibs so only stripped binaries ship in release builds."
    inputs.dir(unstrippedJniLibsDir)
    outputs.dir(strippedJniLibsDir)

    doLast {
        val ndkDir = android.ndkDirectory?.takeIf { it.isDirectory }
            ?: error("NDK not found, cannot strip release .so files.")
        val hostTag = computeNdkHostTag(ndkDir)
        val stripExecutableName = if (hostTag.startsWith("windows")) "llvm-strip.exe" else "llvm-strip"
        val stripExecutable = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin/$stripExecutableName")
        check(stripExecutable.exists()) { "llvm-strip not found at $stripExecutable" }

        strippedJniLibsDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }

        project.copy {
            from(unstrippedJniLibsDir)
            include("**/*.so")
            into(strippedJniLibsDir)
        }

        strippedJniLibsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "so" }
            .forEach { so ->
                project.exec {
                    commandLine(stripExecutable.absolutePath, "--strip-unneeded", so.absolutePath)
                }
            }

        val duplicateNames = setOf("libmpv.so", "libass.so")
        val cxxIntermediates = layout.buildDirectory.dir("intermediates/cxx").get().asFile
        if (cxxIntermediates.isDirectory) {
            cxxIntermediates.walkTopDown()
                .filter { it.isFile && it.name in duplicateNames }
                .forEach { duplicate ->
                    duplicate.delete()
                }
        }
    }
}

tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn(stripReleaseJniLibs)
}

dependencies {
    implementation(project(":common_component"))
    implementation(project(":repository:panel_switch"))
    implementation(project(":repository:danmaku"))
    implementation(project(":repository:video_cache"))

    implementation(Dependencies.Github.keyboard_panel)

    // TODO 暂时移除，编译出64位后再考虑重新添加
    // implementation "com.github.ctiao:ndkbitmap-armv7a:0.9.21"

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
