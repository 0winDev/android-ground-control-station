package com.owindev.gcs.feature.hud

import java.io.File
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

@Tag("REQ-028")
@Tag("REQ-048")
class SpanishLevelWordsTest {

    private lateinit var builder: DocumentBuilder

    @BeforeEach
    fun setUp() {
        builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    }

    @Test
    fun `GIVEN the Spanish strings WHEN reading the checklist's caution THEN it is the alerts' caution word`() {
        val strings = builder.parse(SPANISH_STRINGS).values()

        val result = strings.getValue(CHECKLIST_CAUTION)

        result shouldBeEqualTo strings.getValue(ALERT_CAUTION)
    }

    @Test
    fun `GIVEN the Spanish strings WHEN reading the checklist's caution THEN it is not the advisory word`() {
        val strings = builder.parse(SPANISH_STRINGS).values()

        val result = strings.getValue(CHECKLIST_CAUTION)

        result shouldNotBeEqualTo strings.getValue(ALERT_ADVISORY)
    }

    @Test
    fun `GIVEN the Spanish strings WHEN looking for the advisory word THEN only the advisory level uses it`() {
        val strings = builder.parse(SPANISH_STRINGS).values()

        val result = strings.filter { (name, text) -> name != ALERT_ADVISORY && text.contains(ADVISORY_WORD, true) }

        result.shouldBeEmpty()
    }

    private fun Document.values(): Map<String, String> {
        val strings = getElementsByTagName("string").elements().associate { it.getAttribute("name") to it.textContent }
        val pluralItems = getElementsByTagName("plurals").elements().flatMap { plurals ->
            plurals.getElementsByTagName("item").elements().map { item ->
                "${plurals.getAttribute("name")}:${item.getAttribute("quantity")}" to item.textContent
            }
        }
        return strings + pluralItems
    }

    private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private companion object {
        val SPANISH_STRINGS = File("src/main/res/values-es/strings.xml")
        const val CHECKLIST_CAUTION = "hud_checklist_caution"
        const val ALERT_CAUTION = "hud_alert_level_caution"
        const val ALERT_ADVISORY = "hud_alert_level_advisory"
        const val ADVISORY_WORD = "aviso"
    }
}
