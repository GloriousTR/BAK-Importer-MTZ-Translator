package dev.glorioustr.bakimporter

import dev.glorioustr.bakimporter.backup.BakInspector
import dev.glorioustr.bakimporter.backup.DescriptXmlGenerator
import dev.glorioustr.bakimporter.backup.MtzToBakConverter
import dev.glorioustr.bakimporter.model.BakArchiveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BakLogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDescriptXmlGeneration() {
        val dummyFile = tempFolder.newFile("dummy.bak").apply { writeBytes(ByteArray(1024)) }
        val info = BakArchiveInfo(
            file = dummyFile,
            displayName = "dummy.bak",
            sizeBytes = 1024L,
            backupVersionCode = 200420L,
            appDisplayName = "Themes",
            packageName = "com.android.thememanager",
            tarOffset = 128L,
            entryCount = 5,
        )

        val xml = DescriptXmlGenerator.generate(info, "Themes(com.android.thememanager).bak", "V2.0")

        assertTrue("Should contain MIUI-backup tag", xml.contains("<MIUI-backup"))
        assertTrue("Should contain bakVersion element", xml.contains("<bakVersion>2</bakVersion>"))
        assertTrue("Should contain packages wrapper", xml.contains("<packages><package>"))
        assertTrue("Should contain packageName", xml.contains("<packageName>com.android.thememanager</packageName>"))
        assertTrue("Should contain exact restore file name", xml.contains("<bakFile>Themes(com.android.thememanager).bak</bakFile>"))
        assertTrue("Should contain completed size", xml.contains("<completedSize>1024</completedSize>"))
        assertTrue("Should contain backup file size", xml.contains("<bakFileSize>1024</bakFileSize>"))
        assertTrue("Should contain size", xml.contains("<size>1024</size>"))
    }

    @Test
    fun testMtzConversionAndInspection() {
        // 1. Create a dummy MTZ zip file
        val mtzFile = tempFolder.newFile("test_theme.mtz")
        ZipOutputStream(FileOutputStream(mtzFile)).use { zos ->
            // description.xml
            zos.putNextEntry(ZipEntry("description.xml"))
            val desc = """
                <theme>
                  <title>Test HyperOS Theme</title>
                  <author>GloriousTR</author>
                  <version>1.0.0</version>
                </theme>
            """.trimIndent()
            zos.write(desc.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Wallpaper is stored as a raw MRC resource.
            zos.putNextEntry(ZipEntry("wallpaper/default_wallpaper.jpg"))
            zos.write("fake_wallpaper_binary_data".toByteArray())
            zos.closeEntry()

            // Lock screen and status bar remain independent ZIP-backed MRC components.
            zos.putNextEntry(ZipEntry("lockscreen"))
            zos.write(nestedZip("manifest.xml", "<Root><Text text=\"Hello\"/></Root>"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("com.android.systemui"))
            zos.write(nestedZip("theme_values.xml", "<resources/>"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("boots/bootanimation.zip"))
            zos.write(nestedZip("desc.txt", "1080 2400 30"))
            zos.closeEntry()
        }

        // 2. Convert MTZ to BAK
        val outputBak = tempFolder.newFile("converted.bak")
        MtzToBakConverter.convert(
            mtzFile = mtzFile,
            outputBakFile = outputBak,
            targetVersionCode = 200500L,
            targetVersionName = "V2.0.5",
            manifestMetadata = MtzToBakConverter.BackupManifestMetadata(
                platformSdk = 35,
                signatures = listOf("test-signature"),
            ),
        )

        assertTrue("Output BAK file should exist and have size", outputBak.exists() && outputBak.length() > 0)

        // 3. Inspect the converted BAK archive using BakInspector
        val inspected = BakInspector.inspect(outputBak)
        assertEquals("com.android.thememanager", inspected.packageName)
        assertEquals(200500L, inspected.backupVersionCode)
        assertTrue("Entry count should be >= 3", inspected.entryCount >= 3)
        assertTrue(inspected.entryNames.any { it.contains("/.data/meta/theme/") && it.endsWith(".mrm") })
        assertTrue(inspected.entryNames.any { it.contains("/.data/content/lockstyle/") && it.endsWith(".mrc") })
        assertTrue(inspected.entryNames.any { it.contains("/.data/content/statusbar/") && it.endsWith(".mrc") })
        assertTrue(inspected.entryNames.any { it.contains("/.data/content/bootanimation/") && it.endsWith(".mrc") })
        assertTrue(inspected.entryNames.none { it.contains("/content/offline/") })
        assertEquals(0, inspected.rightsFileCount)
        assertTrue(!inspected.hasApplyRights)

        // Restored local themes must not advertise a synthetic online product.
        // A non-null productId makes global ThemeManager request downloadRight and can return 409.
        val archiveText = outputBak.readText(Charsets.ISO_8859_1)
        assertTrue(archiveText.contains("\"productId\": null"))
        assertTrue(!archiveText.contains("\"productId\": \""))

        val header = outputBak.inputStream().bufferedReader().use { reader ->
            List(9) { reader.readLine() }
        }
        assertEquals("com.android.thememanager Themes", header[2])
        assertEquals("-1", header[3])
        assertEquals("0", header[4])
        assertEquals("ANDROID BACKUP", header[5])
    }

    @Test
    fun mtzMetadataRemovesCdataMarkers() {
        val mtzFile = tempFolder.newFile("cdata_theme.mtz")
        ZipOutputStream(FileOutputStream(mtzFile)).use { zip ->
            zip.putNextEntry(ZipEntry("description.xml"))
            zip.write(
                "<theme><title><![CDATA[璟]]></title><author><![CDATA[鲸落雨]]></author><version><![CDATA[26.8.26]]></version><uiVersion>120</uiVersion><miuiAdapterVersion>18.0</miuiAdapterVersion></theme>"
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }

        val metadata = MtzToBakConverter.inspectMtz(mtzFile)

        assertEquals("璟", metadata.title)
        assertEquals("鲸落雨", metadata.author)
        assertEquals("26.8.26", metadata.version)
        assertEquals(120, metadata.sourceUiVersion)
        assertEquals("18.0", metadata.sourceAdapterVersion)
    }

    @Test
    fun backupResourceCodesAreAsciiSafeAndStable() {
        assertEquals("clock_2x4_kapsul", MtzToBakConverter.backupSafeResourceCode("clock_2x4 kapsül"))
        assertEquals("com.android.systemui", MtzToBakConverter.backupSafeResourceCode("com.android.systemui"))
        assertTrue(MtzToBakConverter.backupSafeResourceCode("时钟样式").matches(Regex("[A-Za-z0-9._-]+")))
    }

    private fun nestedZip(name: String, text: String): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        bytes.toByteArray()
    }
}
