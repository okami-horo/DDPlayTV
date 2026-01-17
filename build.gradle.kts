import governance.VerifyLegacyPagerApisTask
import governance.VerifyModuleDependenciesTask
import org.jlleitschuh.gradle.ktlint.KtlintExtension

buildscript {
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.25")
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

    register<VerifyModuleDependenciesTask>("verifyModuleDependencies")
    register<VerifyLegacyPagerApisTask>("verifyLegacyPagerApis")

    register("verifyArchitectureGovernance") {
        group = "verification"
        description =
            "Runs the recommended local/CI verification set for architecture governance (dependency, style, tests, lint)."
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

gradle.projectsEvaluated {
    tasks.named("verifyArchitectureGovernance").configure {
        dependsOn(tasks.named("verifyModuleDependencies"))
        dependsOn(tasks.named("verifyLegacyPagerApis"))

        val ktlintTasks = allprojects.mapNotNull { it.tasks.findByName("ktlintCheck") }
        dependsOn(ktlintTasks)

        val unitTestTasks = allprojects.mapNotNull { it.tasks.findByName("testDebugUnitTest") }
        dependsOn(unitTestTasks)

        val lintTasks =
            allprojects.mapNotNull { project ->
                project.tasks.findByName("lint") ?: project.tasks.findByName("lintDebug")
            }
        dependsOn(lintTasks)
    }
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}
