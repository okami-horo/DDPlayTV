import org.jlleitschuh.gradle.ktlint.KtlintExtension

buildscript {
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}

plugins {
    id("com.github.ben-manes.versions") version "0.44.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://developer.huawei.com/repo/")
        maven("https://maven.aliyun.com/nexus/content/repositories/releases/")
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        ignoreFailures.set(false)
        filter {
            exclude("**/build/**")
            exclude("**/generated/**")
        }
    }
}

tasks {
    val clean by registering(Delete::class) {
        delete(buildDir)
    }

    //检查依赖库更新
    //gradlew dependencyUpdates
    dependencyUpdates {
        rejectVersionIf {
            isNonStable(candidate.version)
        }
        checkForGradleUpdate = true
        outputFormatter = "html"
        outputDir = "build/dependencyUpdates"
        reportfileName = "report"
    }
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}
