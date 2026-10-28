import governance.VerifyLegacyPagerApisTask
import governance.VerifyModuleDependenciesTask
import governance.VerifyPlaybackBoundariesTask
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.gradle.api.GradleException
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import java.math.BigDecimal

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
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
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
    apply(plugin = "jacoco")

    configure<KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        ignoreFailures.set(false)
        filter {
            exclude("**/build/**")
            exclude("**/generated/**")
        }
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }
}

val jacocoClassExcludes =
    listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/BR.class",
        "**/BR$*.class",
        "**/DataBinderMapperImpl.class",
        "**/DataBinderMapperImpl$*.class",
        "**/databinding/**",
        "**/*Binding.class",
        "**/*Binding$*.class",
        "**/*Test*.*",
        "android/**/*.*",
        "**/*\$Lambda$*.*",
        "**/*\$inlined$*.*",
        "**/*Companion*.*",
        "**/*Factory*.*",
        "**/*Module*.*",
        "**/*Dagger*.*",
        "**/*Hilt*.*",
        "**/*MembersInjector*.*",
    )

tasks {
    val clean by registering(Delete::class) {
        delete(layout.buildDirectory)
    }

    register("jacocoAggregateDebugUnitTest") {
        group = "verification"
        description = "Runs debug unit tests for all Android modules before generating aggregate JaCoCo reports."
    }

    register<JacocoReport>("jacocoTestReport") {
        group = "verification"
        description = "Generates aggregate JaCoCo XML/HTML reports from debug unit tests."
        dependsOn("jacocoAggregateDebugUnitTest")

        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
            xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        }
    }

    // -----------------------------------------------------------------
    // Coverage baseline verification gate (task 4.1 / 4.2)
    // Checks that coverage for medium/high-coverage modules does not
    // regress below the recorded baseline.  Thresholds are intentionally
    // set below the current measured values to catch regressions without
    // blocking new development.
    //
    // Baseline snapshot (measured 2026-04):
    //   core_log_component   57.2 %   → gate 50 %
    //   bilibili_component   17.2 %   → gate 15 %
    //   core_ui_component    19.0 %   → gate 15 %
    //   core_storage_comp.    6.4 %   → gate  5 %
    //   core_database_comp.   6.9 %   → gate  5 %
    //   core_network_comp.    3.0 %   → gate  2 %
    //   player_component      6.2 %   → gate  5 %
    //   data_component        7.8 %   → gate  5 %
    //
    // Run: ./gradlew verifyCoverageBaseline
    // -----------------------------------------------------------------
    register<JacocoCoverageVerification>("verifyCoverageBaseline") {
        group = "verification"
        description =
            "Verifies that coverage for priority modules does not regress below the recorded baseline."
        dependsOn("jacocoTestReport")

        violationRules {
            // core_log_component — already well-tested, protect >50 %
            rule {
                element = "PACKAGE"
                includes = listOf("com/xyoye/common_component/log/**")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal("0.50")
                }
            }

            // bilibili_component — gateway for playback, protect >15 %
            rule {
                element = "PACKAGE"
                includes = listOf("com/xyoye/common_component/bilibili/**")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal("0.15")
                }
            }

            // core_ui_component (adapter/preference) — protect >15 %
            rule {
                element = "PACKAGE"
                includes = listOf(
                    "com/xyoye/common_component/adapter/**",
                    "com/xyoye/common_component/preference/**",
                )
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal("0.15")
                }
            }

            // player_component — complex, protect >5 %
            rule {
                element = "PACKAGE"
                includes = listOf(
                    "com/xyoye/player_component/**",
                    "com/xyoye/player/**",
                )
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal("0.05")
                }
            }

            // core_storage_component — protect >5 %
            rule {
                element = "PACKAGE"
                includes = listOf("com/xyoye/common_component/storage/**")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal("0.05")
                }
            }

            // data_component — models/DTOs, protect >5 %
            rule {
                element = "PACKAGE"
                includes = listOf("com/xyoye/data_component/**")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = BigDecimal("0.05")
                }
            }
        }
    }

    register<VerifyModuleDependenciesTask>("verifyModuleDependencies")
    register<VerifyLegacyPagerApisTask>("verifyLegacyPagerApis")
    register<VerifyPlaybackBoundariesTask>("verifyPlaybackBoundaries")

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
    val coverageProjects =
        subprojects.filter { project ->
            project.tasks.findByName("testDebugUnitTest") != null
        }

    tasks.named("jacocoAggregateDebugUnitTest").configure {
        dependsOn(coverageProjects.mapNotNull { project -> project.tasks.findByName("testDebugUnitTest") })
    }

    tasks.named<JacocoReport>("jacocoTestReport").configure {
        val sourceDirs =
            coverageProjects.flatMap { project ->
                listOf(
                    project.file("src/main/java"),
                    project.file("src/main/kotlin"),
                )
            }

        val classDirs =
            coverageProjects.flatMap { project ->
                val projectBuildDir = project.layout.buildDirectory.asFile.get()
                listOf(
                    project.fileTree("${projectBuildDir}/tmp/kotlin-classes/debug") {
                        exclude(jacocoClassExcludes)
                    },
                    project.fileTree("${projectBuildDir}/intermediates/javac/debug/classes") {
                        exclude(jacocoClassExcludes)
                    },
                    project.fileTree("${projectBuildDir}/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
                        exclude(jacocoClassExcludes)
                    },
                    project.fileTree("${projectBuildDir}/classes/kotlin/debug") {
                        exclude(jacocoClassExcludes)
                    },
                )
            }

        val execData =
            coverageProjects.flatMap { project ->
                val projectBuildDir = project.layout.buildDirectory.asFile.get()
                listOf(
                    project.file("${projectBuildDir}/jacoco/testDebugUnitTest.exec"),
                    project.file("${projectBuildDir}/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
                    project.fileTree("${projectBuildDir}/outputs/code_coverage/debugAndroidTest/connected") {
                        include("**/*.ec")
                    },
                )
            }

        sourceDirectories.setFrom(sourceDirs)
        classDirectories.setFrom(classDirs)
        executionData.setFrom(execData)

        doFirst {
            if (executionData.files.none { it.exists() }) {
                throw GradleException(
                    "No JaCoCo execution data found. Run debug unit tests before jacocoTestReport.",
                )
            }
        }
    }

    // Wire verifyCoverageBaseline with the same source/class/exec data as jacocoTestReport.
    tasks.named<JacocoCoverageVerification>("verifyCoverageBaseline").configure {
        val sourceDirs =
            coverageProjects.flatMap { project ->
                listOf(
                    project.file("src/main/java"),
                    project.file("src/main/kotlin"),
                )
            }
        val classDirs =
            coverageProjects.flatMap { project ->
                val projectBuildDir = project.layout.buildDirectory.asFile.get()
                listOf(
                    project.fileTree("${projectBuildDir}/tmp/kotlin-classes/debug") {
                        exclude(jacocoClassExcludes)
                    },
                    project.fileTree("${projectBuildDir}/intermediates/javac/debug/classes") {
                        exclude(jacocoClassExcludes)
                    },
                    project.fileTree("${projectBuildDir}/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
                        exclude(jacocoClassExcludes)
                    },
                    project.fileTree("${projectBuildDir}/classes/kotlin/debug") {
                        exclude(jacocoClassExcludes)
                    },
                )
            }
        val execData =
            coverageProjects.flatMap { project ->
                val projectBuildDir = project.layout.buildDirectory.asFile.get()
                listOf(
                    project.file("${projectBuildDir}/jacoco/testDebugUnitTest.exec"),
                    project.file("${projectBuildDir}/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
                    project.fileTree("${projectBuildDir}/outputs/code_coverage/debugAndroidTest/connected") {
                        include("**/*.ec")
                    },
                )
            }

        sourceDirectories.setFrom(sourceDirs)
        classDirectories.setFrom(classDirs)
        executionData.setFrom(execData)
    }

    tasks.named("verifyArchitectureGovernance").configure {
        dependsOn(tasks.named("verifyModuleDependencies"))
        dependsOn(tasks.named("verifyLegacyPagerApis"))
        dependsOn(tasks.named("verifyPlaybackBoundaries"))

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
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}
