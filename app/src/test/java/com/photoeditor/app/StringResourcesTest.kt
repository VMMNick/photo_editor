package com.photoeditor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Перевірка локалізації без пристрою: читаємо strings.xml як XML.
 * Unit-тести Gradle запускаються з каталогу модуля app/, тому шляхи відносні до нього.
 */
class StringResourcesTest {

    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }
    private val res = File(moduleDir, "src/main/res")

    private fun load(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }
    }

    private val en by lazy { load("values") }
    private val uk by lazy { load("values-uk") }

    @Test
    fun bothLanguagesHaveSameKeys() {
        assertEquals("ключі без перекладу українською", emptySet<String>(), en.keys - uk.keys)
        assertEquals("зайві ключі в українській", emptySet<String>(), uk.keys - en.keys)
        assertTrue(en.size > 150)
    }

    @Test
    fun noEmptyStrings() {
        for ((k, v) in en + uk.mapKeys { "uk:" + it.key }) assertTrue("порожній рядок $k", v.isNotBlank())
    }

    @Test
    fun placeholdersMatchBetweenLanguages() {
        val ph = Regex("%(\\d+\\$)?[sdf]")
        for ((k, v) in en) {
            val a = ph.findAll(v).map { it.value }.sorted().toList()
            val b = ph.findAll(uk.getValue(k)).map { it.value }.sorted().toList()
            assertEquals("плейсхолдери в $k", a, b)
        }
    }

    @Test
    fun ukrainianIsActuallyTranslated() {
        // Рядки з латинськими словами, які не мають перекладатися (формати, назви брендів)
        val same = en.filter { (k, v) -> uk[k] == v }.keys
        val allowed = setOf(
            "image_size", "image_size_raw", "export_size_px", "color_tab_hsl", "aspect_square", "aspect_insta",
            "aspect_stories", "aspect_wide", "aspect_3_2", "aspect_a4", "mask_chip", "curve_rgb", "section_lut",
        )
        assertEquals("неперекладені рядки", emptySet<String>(), same - allowed)
    }

    @Test
    fun noHardcodedCyrillicInCode() {
        // Кирилиця в коді дозволена лише в коментарях; тексти для користувача — тільки через ресурси
        val cyr = Regex("\"[^\"\\n]*[А-Яа-яІіЇїЄєҐґ][^\"\\n]*\"")
        val offenders = File(moduleDir, "src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) null
                else if (cyr.containsMatchIn(code)) "${f.name}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertEquals("жорстко закодовані тексти:\n" + offenders.joinToString("\n"), 0, offenders.size)
    }

    @Test
    fun localeConfigListsBothLanguages() {
        val cfg = File(res, "xml/locales_config.xml").readText()
        assertTrue(cfg.contains("\"en\"") && cfg.contains("\"uk\""))
    }
}
