plugins {
    id("com.android.library")
}

android {
    namespace = "com.xyoye.mpv"
    compileSdk = Versions.compileSdkVersion
    defaultConfig {
        minSdk = Versions.minSdkVersion
    }
    buildTypes {
        create("beta") {
            initWith(getByName("release"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // Keep upstream Kotlin 2.4 metadata off the Kotlin 2.2 consumer compile classpath.
    compileOnly(project(":repository:mpv_native"))
    runtimeOnly(project(":repository:mpv_native"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":repository:mpv_native"))
}
