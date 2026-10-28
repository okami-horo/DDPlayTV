plugins {
    kotlin("jvm") version "2.2.10"
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("prebuiltAarConvention") {
            id = "setup.prebuilt-aar"
            implementationClass = "setup.PrebuiltAarConventionPlugin"
        }
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.10.1")
    implementation(kotlin("gradle-plugin", "2.2.10"))
    implementation(kotlin("compiler-embeddable", "2.2.10"))
}
