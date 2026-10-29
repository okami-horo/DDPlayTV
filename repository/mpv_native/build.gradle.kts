import java.security.MessageDigest

plugins {
    base
}

val mpvArtifact = file("mpv-android-127aea1231b8.aar")

val verifyMpvArtifact by tasks.registering {
    inputs.file(mpvArtifact)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        mpvArtifact.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == "60aa28e89ca288d7a653a6a7c50c9dacaa91c4d992868b257f305b909bb26d59") {
            "Plezy mpv AAR SHA-256 mismatch: $actual"
        }
    }
}

configurations.maybeCreate("default")
artifacts.add("default", mpvArtifact) {
    builtBy(verifyMpvArtifact)
}
