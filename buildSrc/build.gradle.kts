plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("com.android.tools.build:gradle:8.7.2")
    implementation(kotlin("gradle-plugin", "2.0.21"))
}
