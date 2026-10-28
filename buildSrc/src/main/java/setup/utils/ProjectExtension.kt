@file:Suppress("DEPRECATION")

package setup.utils

import Dependencies
import Versions
import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.ApkVariantOutput
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.fileTree
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.io.ByteArrayOutputStream

fun Project.setupKotlinOptions() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
}

fun Project.setupDefaultDependencies() {
    dependencies.apply {
        add("implementation", fileTree("include" to listOf("*.jar"), "dir" to "libs"))

        add("testImplementation", Dependencies.Junit.junit)
        add("androidTestImplementation", Dependencies.AndroidX.junit_ext)
        add("androidTestImplementation", Dependencies.AndroidX.espresso)
    }

    // Avoid duplicate ListenableFuture between Guava (from dcerpc) and AndroidX test stack.
    // Only touch AndroidTest-related configurations so main/runtime dependencies stay intact.
    configurations.configureEach {
        if (name.contains("AndroidTest", ignoreCase = true)) {
            exclude(group = "com.google.guava", module = "listenablefuture")
        }
    }
}

fun Project.currentCommit(): String {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine = "git log --pretty=format:%h -1".split(" ")
        standardOutput = stdout
    }
    return stdout.toString()
}

fun AppExtension.setupSignConfigs(project: Project) = apply {
    signingConfigs {
        named("debug") {
            SignConfig.debug(project, this)
        }

        create("release") {
            SignConfig.release(project, this)
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.findByName(this.name)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "DDPlayTV (Debug)")
            isMinifyEnabled = false
        }

        getByName("release") {
            signingConfig = signingConfigs.findByName(this.name)
            isMinifyEnabled = false
        }

        create("beta") {
            initWith(getByName("release"))
        }
    }
}

fun AppExtension.setupOutputApk() = apply {
    applicationVariants.configureEach {
        outputs
            .filterIsInstance<ApkVariantOutput>()
            .forEach { output ->
                val abi = output.filters.firstOrNull()?.identifier ?: "universal"
                val buildTypeName = buildType.name
                val versionName = versionName ?: Versions.versionName
                output.outputFileName = "ddplaytv_v${versionName}_${abi}-${buildTypeName}.apk"
            }
    }
}
