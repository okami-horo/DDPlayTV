plugins {
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
    implementation("com.android.tools.build:gradle:8.7.2")
    implementation(kotlin("gradle-plugin", "1.9.25"))
    implementation(kotlin("compiler-embeddable", "1.9.25"))
}
