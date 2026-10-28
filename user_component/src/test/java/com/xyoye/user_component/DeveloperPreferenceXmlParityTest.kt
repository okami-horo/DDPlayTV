package com.xyoye.user_component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeveloperPreferenceXmlParityTest {
    @Test
    fun televisionPreferenceMatchesDefaultKeyOrder() {
        val defaultXml =
            readXmlFile("user_component/src/main/res/xml/preference_developer_setting.xml")
        val televisionXml =
            readXmlFile("user_component/src/main/res/xml-television/preference_developer_setting.xml")

        val defaultKeys = extractKeys(defaultXml)
        val televisionKeys = extractKeys(televisionXml)

        assertTrue(defaultKeys.contains("bugly_test_report"))
        assertEquals(defaultKeys, televisionKeys)
    }

    private fun readXmlFile(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "Missing user.dir" }
        val workingDir = File(userDir)
        val parentDir = workingDir.parentFile
        val rootDir =
            when {
                File(workingDir, path).exists() -> workingDir
                parentDir != null && File(parentDir, path).exists() -> parentDir
                else -> throw IllegalStateException("Cannot locate file: $path")
            }
        return File(rootDir, path).readText()
    }

    private fun extractKeys(xml: String): List<String> =
        KEY_REGEX
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()

    companion object {
        private val KEY_REGEX = Regex("""android:key="([^"]+)"""")
    }
}
