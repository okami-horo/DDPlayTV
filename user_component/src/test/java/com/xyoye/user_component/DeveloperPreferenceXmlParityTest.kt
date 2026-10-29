package com.xyoye.user_component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DeveloperPreferenceXmlParityTest {
    @Test
    fun defaultPreferenceContainsRemoteSettingsInOrderWithoutTelevisionOverride() {
        val defaultXml =
            locateXmlFile("user_component/src/main/res/xml/preference_developer_setting.xml")
        assertEquals(
            listOf(
                "developer_log_category",
                "developer_log_level",
                "app_log_enable",
                "http_log_server_enable",
                "http_log_server_address",
                "http_log_server_token",
                "http_log_server_reset_token",
                "http_log_server_clear_logs",
                "http_log_server_retention_days",
                "http_log_server_storage_usage",
                "developer_subtitle_category",
                "subtitle_session_status",
                "developer_bugly_category",
                "bugly_status",
                "bugly_test_report",
                "developer_credential_category",
                "developer_credential_plaintext_fallback",
                "developer_credential_migrate_plaintext"
            ),
            extractKeys(defaultXml)
        )
        val resourceDirectory = defaultXml.parentFile.parentFile
        assertTrue(
            "Developer preferences must share one layout on all devices",
            resourceDirectory.listFiles().orEmpty().none {
                it.name.startsWith("xml-") && File(it, defaultXml.name).exists()
            }
        )
    }

    private fun locateXmlFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "Missing user.dir" }
        val workingDir = File(userDir)
        val parentDir = workingDir.parentFile
        val rootDir =
            when {
                File(workingDir, path).exists() -> workingDir
                parentDir != null && File(parentDir, path).exists() -> parentDir
                else -> throw IllegalStateException("Cannot locate file: $path")
            }
        return File(rootDir, path)
    }

    private fun extractKeys(xml: File): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val elements = factory.newDocumentBuilder().parse(xml).getElementsByTagName("*")
        return (0 until elements.length).mapNotNull {
            (elements.item(it) as Element)
                .getAttributeNS("http://schemas.android.com/apk/res/android", "key")
                .takeIf(String::isNotEmpty)
        }
    }
}
