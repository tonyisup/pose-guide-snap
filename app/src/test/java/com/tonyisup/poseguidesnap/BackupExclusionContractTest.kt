package com.tonyisup.poseguidesnap

import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class BackupExclusionContractTest {
    @Test
    fun bundledModelAndFixtureBytesMatchReviewedDigests() {
        val root = projectRoot()
        val model = root.resolve(
            "app/src/main/assets/movenet_multipose_lightning_float16_v1.tflite",
        )
        val fixture = root.resolve(
            "app/src/androidTest/assets/pose-fixtures/meditation_pose.png",
        )

        assertEquals(9_585_276L, model.length())
        assertEquals(
            "d4489f89e6bd6777a8b9a1a16189832131f84ff90d82fae729e670b84d7948dd",
            sha256(model),
        )
        assertEquals(1_031_392L, fixture.length())
        assertEquals(
            "e4b26bbe800988cd208a77b23a412109bb2b629e65ead9fa86c4c8a61998eedb",
            sha256(fixture),
        )
    }

    @Test
    fun authoritativeContractsUseMoveNetAndBoundedBlockingScheduling() {
        val root = projectRoot()
        val contracts = listOf(
            root.resolve(".hermes/plans/2026-08-27_111939-pose-guide-snap-android-mvp.md"),
            root.resolve("docs/ARCHITECTURE.md"),
            root.resolve("docs/adr/0001-android-native-first.md"),
            root.resolve("docs/adr/0002-on-device-pose-processing.md"),
            root.resolve("docs/TESTING.md"),
        ).associateWith(File::readText)
        val combined = contracts.values.joinToString("\n")

        listOf(
            "pose/mediapipe",
            "MediaPipe model load",
            "PoseDetector(LIVE_STREAM)",
            "MediaPipe adapters follow their platform contracts",
        ).forEach { staleDirective ->
            assertFalse("Stale authoritative directive: $staleDirective", staleDirective in combined)
        }
        listOf(
            "pose/movenet",
            "one bounded off-UI worker",
            "keep-latest backpressure",
            "per-frame failure containment",
        ).forEach { requiredDirective ->
            assertTrue("Missing authoritative directive: $requiredDirective", requiredDirective in combined)
        }
    }

    @Test
    fun moveNetUsesPinnedMinimalLiteRtRuntime() {
        val root = projectRoot()
        val catalog = root.resolve("gradle/libs.versions.toml").readText()
        val appBuild = root.resolve("app/build.gradle.kts").readText()

        assertTrue(Regex("""liteRt\s*=\s*"1\.4\.2"""").containsMatchIn(catalog))
        assertTrue(
            Regex(
                """litert\s*=\s*\{\s*module\s*=\s*"com\.google\.ai\.edge\.litert:litert",\s*""" +
                    """version\.ref\s*=\s*"liteRt"\s*}""",
            ).containsMatchIn(catalog),
        )
        assertTrue(Regex("""implementation\(libs\.litert\)""").containsMatchIn(appBuild))
        assertFalse("MediaPipe Tasks must remain absent", "tasks-vision" in catalog || "mediapipe" in appBuild)
        assertFalse("LiteRT 2.2.0 broad runtime must remain absent", "2.2.0" in catalog || "2.2.0" in appBuild)
    }

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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
