package setup.utils

import com.android.build.gradle.internal.dsl.SigningConfig
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.io.FileInputStream
import java.util.*

object SignConfig {

    fun debug(project: Project, config: SigningConfig) {
        val properties = loadProperties(project) ?: return
        config.apply {
            storeFile(project.getAssembleFile(properties["KEY_LOCATION"].toString()))
            storePassword(properties["KEYSTORE_PASS"].toString())
            keyAlias(properties["ALIAS_NAME"].toString())
            keyPassword(properties["ALIAS_PASS"].toString())

            enableV1Signing = true
        }
    }

    fun release(project: Project, config: SigningConfig) {
        val keystoreFile = project.getAssembleFile("dandanplay.jks")
        val storePassword = System.getenv("KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
        val aliasName = System.getenv("ALIAS_NAME")?.takeIf { it.isNotBlank() }
        val aliasPassword = System.getenv("ALIAS_PASS")?.takeIf { it.isNotBlank() }

        if (keystoreFile.exists() && storePassword != null && aliasName != null && aliasPassword != null) {
            config.apply {
                storeFile = keystoreFile
                storePassword(storePassword)
                keyAlias(aliasName)
                keyPassword(aliasPassword)
            }
            return
        }

        val isCi =
            System.getenv("CI")?.equals("true", ignoreCase = true) == true ||
                System.getenv("GITHUB_ACTIONS")?.equals("true", ignoreCase = true) == true

        if (isCi) {
            throw GradleException(
                "Release signing config not ready in CI: keystoreFileExists=${keystoreFile.exists()} env(KEYSTORE_PASS/ALIAS_NAME/ALIAS_PASS)=${storePassword != null}/${aliasName != null}/${aliasPassword != null}",
            )
        }

        project.logger.warn(
            "Release signing config not ready (missing keystore or env), fallback to debug signing config",
        )
        debug(project, config)
    }

    private fun loadProperties(project: Project): Properties? {
        var propertiesFile = project.getAssembleFile("keystore.properties")
        if (propertiesFile.exists().not()) {
            propertiesFile = project.getAssembleFile("debug.properties")
        }
        if (propertiesFile.exists()) {
            val properties = Properties()
            properties.load(FileInputStream(propertiesFile))
            return properties
        }
        return null
    }

    private fun Project.getAssembleFile(fileName: String): File {
        return File(rootDir,"gradle/assemble/$fileName")
    }
}
