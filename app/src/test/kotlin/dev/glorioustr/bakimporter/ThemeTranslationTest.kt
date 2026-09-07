package dev.glorioustr.bakimporter

import dev.glorioustr.bakimporter.translation.ThemeGlossary
import dev.glorioustr.bakimporter.translation.ThemeTextLocalizer
import dev.glorioustr.bakimporter.translation.ConversationalThemeGlossary
import dev.glorioustr.bakimporter.translation.TranslationTextFilter
import dev.glorioustr.bakimporter.translation.ChineseTranslationSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ThemeTranslationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun translatesThemeDisplayTextWithoutTouchingIdentifiers() {
        val source = tempFolder.newFile("source.mtz")
        ZipOutputStream(FileOutputStream(source)).use { output ->
            output.putNextEntry(ZipEntry("description.xml"))
            output.write("<theme><title>壁纸设置</title><author>中文作者</author></theme>".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("config.xml"))
            output.write("<Root><Text name=\"中文标识\" text=\"晴天\"/></Root>".toByteArray())
            output.closeEntry()
        }
        val translated = File(tempFolder.root, "translated.mtz")

        val result = ThemeTextLocalizer(targetLanguage = "tr").rewrite(
            source.toPath(),
            translated.toPath(),
        ) { ThemeGlossary.resolve(it, "tr") ?: "Çevrildi" }

        assertTrue(result.translatedNodes >= 2)
        ZipFile(translated).use { zip ->
            val description = zip.getInputStream(zip.getEntry("description.xml")).bufferedReader().readText()
            val config = zip.getInputStream(zip.getEntry("config.xml")).bufferedReader().readText()
            assertTrue(description.contains("Duvar Kâğıdı Ayarları"))
            assertFalse(description.contains("壁纸设置"))
            assertTrue(config.contains("Güneşli"))
            assertTrue(config.contains("name=\"中文标识\""))
        }
        assertEquals(2, result.changedFiles.size)
    }

    @Test
    fun autoModeTranslatesEnglishDisplayTextWithoutTouchingCodeFields() {
        val source = tempFolder.newFile("english.mtz")
        ZipOutputStream(FileOutputStream(source)).use { output ->
            output.putNextEntry(ZipEntry("description.xml"))
            output.write("<theme><title>Swipe up to unlock</title><author>Theme Maker</author></theme>".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("lockscreen.xml"))
            output.write("<Root><Text name=\"unlock_status\" text=\"No notifications\"/></Root>".toByteArray())
            output.closeEntry()
        }
        val translated = File(tempFolder.root, "english-translated.mtz")

        val result = ThemeTextLocalizer(
            targetLanguage = "tr",
            shouldTranslate = TranslationTextFilter::isCandidate,
        ).rewrite(source.toPath(), translated.toPath()) { text ->
            ConversationalThemeGlossary.resolve(text, "tr")?.translation ?: text
        }

        assertEquals(2, result.translatedNodes)
        ZipFile(translated).use { zip ->
            val description = zip.getInputStream(zip.getEntry("description.xml")).bufferedReader().readText()
            val lockscreen = zip.getInputStream(zip.getEntry("lockscreen.xml")).bufferedReader().readText()
            assertTrue(description.contains("Kilidi açmak için yukarı kaydırın"))
            assertTrue(description.contains("Theme Maker"))
            assertTrue(lockscreen.contains("Bildirim yok"))
            assertTrue(lockscreen.contains("name=\"unlock_status\""))
        }
    }

    @Test
    fun automaticCandidateFilterKeepsChineseAndRejectsIdentifiers() {
        assertTrue(TranslationTextFilter.isCandidate("壁纸设置"))
        assertTrue(TranslationTextFilter.isCandidate("Settings"))
        assertTrue(TranslationTextFilter.isCandidate("Aucune notification"))
        assertFalse(TranslationTextFilter.isCandidate("com.miui.systemui"))
        assertFalse(TranslationTextFilter.isCandidate("unlock_status"))
        assertFalse(TranslationTextFilter.isCandidate("https://example.com/theme"))
        assertFalse(TranslationTextFilter.isCandidate("12:45"))
    }

    @Test
    fun collectsApiCandidatesWithoutCreatingARewrittenArchive() {
        val source = tempFolder.newFile("candidate-scan.mtz")
        ZipOutputStream(FileOutputStream(source)).use { output ->
            output.putNextEntry(ZipEntry("lockscreen.xml"))
            output.write("<Root><Text name=\"unlock_status\" text=\"Swipe up to unlock\"/></Root>".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("preview.png"))
            output.write(ByteArray(256 * 1024) { 7 })
            output.closeEntry()
        }

        val candidates = ThemeTextLocalizer(
            targetLanguage = "tr",
            shouldTranslate = TranslationTextFilter::isCandidate,
        ).collectCandidates(source.toPath())

        assertEquals(setOf("Swipe up to unlock"), candidates)
        assertEquals(listOf("candidate-scan.mtz"), tempFolder.root.listFiles()!!.map(File::getName).sorted())
    }

    @Test
    fun usesNaturalTranslationForProvidedThemeMetadata() {
        assertEquals("Parıltı", ThemeGlossary.resolve("璟", "tr"))
        assertEquals(
            "Tema özellikleri: Çok işlevli kilit ekranı, sıvı cam efekti ve ayarlanabilir cam rengi; derinlik efektli, uzamsal ve albüm duvar kâğıtları; mini oynatıcı ve büyük kapaklı oynatıcı; parmak izi simgesini gizleme ve gelişmiş malzeme efektleri.",
            ThemeGlossary.resolve(
                "主题特色：多功能锁屏，锁屏液态玻璃效果，可以调整玻璃颜色，景深壁纸，空间壁纸，专辑壁纸，mini播放器和大封面播放器，可以隐藏指纹，高级材质效果。",
                "tr",
            ),
        )
    }

    @Test
    fun preservesLargeIconComponentAndStillTranslatesThemeText() {
        val source = tempFolder.newFile("large-icons.mtz")
        val icons = Random(42).nextBytes(8 * 1024)
        ZipOutputStream(FileOutputStream(source)).use { output ->
            output.putNextEntry(ZipEntry("icons"))
            output.write(icons)
            output.closeEntry()
            output.putNextEntry(ZipEntry("description.xml"))
            output.write("<theme><title>壁纸设置</title></theme>".toByteArray())
            output.closeEntry()
        }
        val translated = File(tempFolder.root, "large-icons-translated.mtz")

        val result = ThemeTextLocalizer(
            maxEntryBytes = 1024,
            targetLanguage = "tr",
        ).rewrite(source.toPath(), translated.toPath()) { ThemeGlossary.resolve(it, "tr") ?: it }

        assertTrue(result.skippedFiles.contains("icons"))
        ZipFile(translated).use { zip ->
            assertTrue(zip.getInputStream(zip.getEntry("icons")).readBytes().contentEquals(icons))
            val description = zip.getInputStream(zip.getEntry("description.xml")).bufferedReader().readText()
            assertTrue(description.contains("Duvar Kâğıdı Ayarları"))
        }
    }

    @Test
    fun translatesDisplayVariablesAndFormatsWithoutChangingOriginalProgramVariable() {
        val source = tempFolder.newFile("maml-expressions.mtz")
        ZipOutputStream(FileOutputStream(source)).use { output ->
            output.putNextEntry(ZipEntry("lockscreen.xml"))
            output.write(
                """
                <Root>
                  <Var name="battery_message" expression="ifelse(#battery_state,'正在充电','电量过低')"/>
                  <Text text="@battery_message"/>
                  <Text format="最高:%s° 最低:%s°"/>
                </Root>
                """.trimIndent().toByteArray(),
            )
            output.closeEntry()
        }
        val translated = File(tempFolder.root, "maml-expressions-translated.mtz")

        ThemeTextLocalizer(targetLanguage = "tr").rewrite(source.toPath(), translated.toPath()) {
            when (it) {
                "正在充电" -> "Şarj ediliyor"
                "电量过低" -> "Pil seviyesi düşük"
                "最高:%s° 最低:%s°" -> "En yüksek: %s° En düşük: %s°"
                else -> it
            }
        }

        ZipFile(translated).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("lockscreen.xml")).bufferedReader().readText()
            assertTrue(xml.contains("name=\"battery_message\""))
            assertTrue(xml.contains("expression=\"ifelse(#battery_state,'正在充电','电量过低')\""))
            assertTrue(xml.contains("Şarj ediliyor"))
            assertTrue(xml.contains("Pil seviyesi düşük"))
            assertTrue(xml.contains("text=\"@__mtz_locale_"))
            assertTrue(xml.contains("format=\"En yüksek: %s° En düşük: %s°\""))
        }
    }

    @Test
    fun translatesEveryVisibleUnitInConcatenatedMamlExpressions() {
        val source = tempFolder.newFile("maml-units.mtz")
        ZipOutputStream(FileOutputStream(source)).use { output ->
            output.putNextEntry(ZipEntry("lockscreen.xml"))
            output.write(
                """
                <Root>
                  <Var name="duration" expression="ifelse(#hours&gt;0,#hours+'小时','')+#minutes+'分钟'" type="string"/>
                  <Text text="@duration"/>
                </Root>
                """.trimIndent().toByteArray(),
            )
            output.closeEntry()
        }
        val translated = File(tempFolder.root, "maml-units-translated.mtz")

        ThemeTextLocalizer(targetLanguage = "tr").rewrite(source.toPath(), translated.toPath()) {
            ThemeGlossary.resolve(it, "tr") ?: it
        }

        ZipFile(translated).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("lockscreen.xml")).bufferedReader().readText()
            assertTrue(xml.contains("__mtz_locale_"))
            assertTrue(xml.contains("saat"))
            assertTrue(xml.contains("dakika"))
        }
    }

    @Test
    fun usesNaturalTurkishForCommonHyperOsThemeTerms() {
        assertEquals("Dinamik Ada", ThemeGlossary.resolve("灵动岛", "tr"))
        assertEquals(
            "Klasördeki duvar kâğıtlarını otomatik değiştir",
            ThemeGlossary.resolve("文件夹壁纸自动切换", "tr"),
        )
        assertEquals(
            "En yüksek: %s°  En düşük: %s°",
            ThemeGlossary.resolve("最高:%s° 最低:%s°", "tr"),
        )
        assertEquals(
            "lock screen Dynamic Island wallpaper",
            ThemeGlossary.prepareChineseForEnglishPivot("锁屏灵动岛壁纸"),
        )
        assertEquals(
            "Tek dokunuşta iki şarkı atlama sorununu düzeltir. Yalnızca HyperOS 3'te açın; diğer sürümlerde kapalı tutun.",
            ThemeGlossary.resolve("修复点击一次切换两首歌的bug,仅澎湃OS3需要开启,非澎湃OS3禁止开启!", "tr"),
        )
    }

    @Test
    fun translatesRasterThemeMetadataWithCuratedNaturalCopy() {
        assertEquals("Lentiküler", ThemeGlossary.resolve("光栅", "tr"))
        val translated = ThemeGlossary.resolve(
            """【锁屏】
光栅壁纸（晃动手机切换三张壁纸，实现光栅效果，壁纸支持自定义）；
如果锁屏卡顿，请在锁屏右上角更多设置中关闭局部高斯模糊，自定义壁纸请勿超过手机分辨率；
动态星光充电动画，支持自定义充电动画；
居中动态唱片机音乐播放器，锁屏右上角开关随时显示；
锁屏显示日出日落、雨量图等多种小组件（双击可切换）；
锁屏显示指南针，跟随指纹图标区域显示，充分利用空间；
锁屏全局显示的底部动态音乐频谱；
锁屏全局显示的双侧边悬浮球，支持20+快捷功能，任意边缘点击或内滑均可呼出，跟手操作，方便快捷；
仿默认右下角滑动相机；
底部支持音乐播放器、计步、快捷开关（点击上方切换显示）；
双击底部小横条切换到横屏模式，再次双击同一位置退出。横屏模式支持报时，可以侧边菜单开启，横屏界面自动常亮。

【控制中心与状态栏】
超椭圆圆润矩形开关；
纯黑状态栏图标；
状态栏左侧时间大小（稍大）；
状态栏电池内数字大小（稍大）；
状态栏显示星期；
通知中心与控制中心左上角日期大小（稍大）。

【时钟】
锁屏和桌面时钟采用大粗体轨迹效果设计；
中间显示小秒钟；
桌面时钟有音乐播放时显示歌词；
桌面时钟下方节日提醒；
桌面时钟点击中部显示设置菜单，支持颜色切换、时钟字体切换、秒钟开关。

【反馈与交流】
QQ交流群号：204879473
微信公众号：墨飞主题
作者微信号：Mofy_123""",
            "tr",
        )

        assertTrue(translated!!.contains("Lentiküler duvar kâğıdı"))
        assertTrue(translated.contains("bölgesel Gauss bulanıklığını kapatın"))
        assertFalse(ThemeGlossary.containsChinese(translated.substringBefore("WeChat resmî hesabı")))
        assertEquals(
            "Parmak izi stili 16 – HyperOS logosu",
            ThemeGlossary.resolve("指纹样式16 澎湃OS logo", "tr"),
        )
        assertTrue(
            ThemeGlossary.resolve(
                "景深壁纸会覆盖到时钟，请先将主壁纸抠图后使用（相册打开主壁纸，长按，保存，即可抠图，使用抠图png格式即可实现景深效果）。使用景深壁纸请关闭左右光栅壁纸。",
                "tr",
            )!!.contains("arka planını kaldırıp PNG olarak kaydedin"),
        )
    }

    @Test
    fun segmentsLongChineseCopyWithoutLosingListStructure() {
        val seen = mutableListOf<String>()
        val translated = ChineseTranslationSegmenter.translate(
            "锁屏通知丨默认开启，双击可切换；关闭后不显示。",
            "tr",
        ) { clause -> seen += clause; "<$clause>" }

        assertTrue(translated.contains(" | "))
        assertTrue(translated.contains(", "))
        assertTrue(translated.contains("; "))
        assertTrue(seen.all { it.none(Char::isWhitespace) || it.isNotBlank() })
        assertFalse(translated.contains('丨'))
    }

    @Test
    fun shortensTranslatedRasterSideMenuLabelsToFitTheirCards() {
        val source = tempFolder.newFile("raster-menu.mtz")
        ZipOutputStream(FileOutputStream(source)).use { output ->
            output.putNextEntry(ZipEntry("lockscreen.xml"))
            output.write(
                """<Lockscreen>
                    <Text x="210" y="207" size="40" textExp="ifelse(#enabled,'光栅 - 开','光栅 - 关')"/>
                    <Text x="210" y="480" size="40" text="- 时钟1 +"/>
                    <Text x="210" y="571" size="40" rotation="90" text="- 唱片机 +"/>
                    <Text x="210" y="662" size="40" rotation="90" text="- 轨迹钟 +"/>
                    <Text x="210" y="753" size="30" rotation="90" text="报时-整点"/>
                </Lockscreen>""".trimIndent().toByteArray(),
            )
            output.closeEntry()
        }
        val translated = File(tempFolder.root, "raster-menu-translated.mtz")

        ThemeTextLocalizer(targetLanguage = "tr").rewrite(source.toPath(), translated.toPath()) {
            ThemeGlossary.resolve(it, "tr") ?: it
        }

        ZipFile(translated).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("lockscreen.xml")).bufferedReader().readText()
            assertTrue(xml.contains("Lentiküler: Açık"))
            assertTrue(xml.contains("Lentiküler: Kapalı"))
            assertTrue(xml.contains("Saat 1"))
            assertTrue(xml.contains("Pikap"))
            assertTrue(xml.contains("İz efektli saat"))
            assertFalse(xml.contains("时钟"))
            assertFalse(xml.contains("唱片机"))
            assertFalse(xml.contains("轨迹钟"))
            assertTrue(xml.contains("size=\"30\""))
            assertTrue(xml.contains("size=\"23\""))
        }
    }

    @Test
    fun rendersChineseLunarDateAsCompactNumbers() {
        val source = tempFolder.newFile("lunar-clock.mtz")
        ZipOutputStream(FileOutputStream(source)).use { output ->
            output.putNextEntry(ZipEntry("clock.xml"))
            output.write(
                "<Clock><DateTime name=\"date\" format=\"M-d  N月e  EE\" value=\"#time_sys\"/></Clock>".toByteArray(),
            )
            output.closeEntry()
        }
        val translated = File(tempFolder.root, "lunar-clock-translated.mtz")

        ThemeTextLocalizer(targetLanguage = "tr").rewrite(source.toPath(), translated.toPath()) {
            ThemeGlossary.resolve(it, "tr") ?: it
        }

        ZipFile(translated).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("clock.xml")).bufferedReader().readText()
            assertTrue(xml.contains("1*#month_lunar+1"))
            assertTrue(xml.contains("#date_lunar"))
            assertFalse(xml.contains("Çin takvimi"))
            assertFalse(xml.contains("N月e"))
        }
    }

    @Test
    fun keepsShortChargingAndMusicLabelsNatural() {
        assertEquals("Mi Şarj", ConversationalThemeGlossary.resolve("Mi Charge", "tr")!!.translation)
        assertEquals("Müzik", ConversationalThemeGlossary.resolve("MUSIC", "tr")!!.translation)
        assertTrue(TranslationTextFilter.isCandidate("MUSIC"))
    }
}
