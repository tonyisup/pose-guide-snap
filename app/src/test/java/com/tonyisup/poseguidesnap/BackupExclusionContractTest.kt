package com.tonyisup.poseguidesnap

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class BackupExclusionContractTest {
    @Test
    fun manifestOptsOutAndReferencesBothBackupPolicies() {
        val document = parse("app/src/main/AndroidManifest.xml")
        val application = document.getElementsByTagName("application").singleElement()

        assertEquals("false", application.getAttributeNS(ANDROID_NAMESPACE, "allowBackup"))
        assertEquals(
            "@xml/backup_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "fullBackupContent"),
        )
        assertEquals(
            "@xml/data_extraction_rules",
            application.getAttributeNS(ANDROID_NAMESPACE, "dataExtractionRules"),
        )
        assertFalse(application.hasAttributeNS(ANDROID_NAMESPACE, "backupAgent"))

        val requestedPermissions = document.getElementsByTagName("uses-permission")
            .elements()
            .map { it.getAttributeNS(ANDROID_NAMESPACE, "name") }
            .toSet()
        assertFalse("android.permission.INTERNET" in requestedPermissions)
    }

    @Test
    fun legacyRulesExcludeEveryStorageDomainExactly() {
        val root = parse("app/src/main/res/xml/backup_rules.xml").documentElement

        assertEquals("full-backup-content", root.tagName)
        assertExactExclusions(root)
    }

    @Test
    fun api31RulesExcludeEveryStorageDomainInEveryTransferModeExactly() {
        val root = parse("app/src/main/res/xml/data_extraction_rules.xml").documentElement

        assertEquals("data-extraction-rules", root.tagName)
        assertEquals(
            setOf("cloud-backup", "device-transfer", "cross-platform-transfer"),
            root.directChildElements().map { it.tagName }.toSet(),
        )
        assertEquals(3, root.directChildElements().size)

        val cloudBackup = root.singleDirectChild("cloud-backup")
        val deviceTransfer = root.singleDirectChild("device-transfer")
        val crossPlatformTransfer = root.singleDirectChild("cross-platform-transfer")
        assertEquals("ios", crossPlatformTransfer.getAttribute("platform"))

        assertExactExclusions(cloudBackup)
        assertExactExclusions(deviceTransfer)
        assertExactExclusions(crossPlatformTransfer)
    }

    private fun assertExactExclusions(section: Element) {
        val children = section.directChildElements()
        assertEquals(EXCLUSIONS.size, children.size)
        assertEquals(setOf("exclude"), children.map { it.tagName }.toSet())
        assertEquals(
            EXCLUSIONS,
            children.map { Exclusion(it.getAttribute("domain"), it.getAttribute("path")) }.toSet(),
        )
    }

    private fun parse(relativePath: String): Document {
        val file = projectRoot().resolve(relativePath)
        assertNotNull("Missing source contract file: $file", file.takeIf(File::isFile))
        return DocumentBuilderFactory.newInstance().run {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            newDocumentBuilder().parse(file)
        }
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private fun Element.singleDirectChild(tagName: String): Element {
        val matches = directChildElements().filter { it.tagName == tagName }
        assertEquals("Expected one direct <$tagName> child", 1, matches.size)
        return matches.single()
    }

    private fun Element.directChildElements(): List<Element> = childNodes.elements()

    private fun org.w3c.dom.NodeList.singleElement(): Element {
        assertEquals(1, length)
        return item(0) as Element
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it).takeIf { node -> node.nodeType == Node.ELEMENT_NODE } as? Element }

    private data class Exclusion(val domain: String, val path: String)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val EXCLUSIONS = setOf(
            Exclusion("root", "."),
            Exclusion("file", "."),
            Exclusion("database", "."),
            Exclusion("sharedpref", "."),
            Exclusion("external", "."),
            Exclusion("device_root", "."),
            Exclusion("device_file", "."),
            Exclusion("device_database", "."),
            Exclusion("device_sharedpref", "."),
        )
    }
}
