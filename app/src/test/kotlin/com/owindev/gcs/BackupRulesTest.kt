package com.owindev.gcs

import java.io.File
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The backup rules the manifest points to, read from `src/main/res/xml` (S-4). */
class BackupRulesTest {

    private lateinit var builder: DocumentBuilder

    @BeforeEach
    fun setUp() {
        builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN the rules for Android 11 and lower WHEN reading what backup excludes THEN the flight logs are`() {
        val rules = builder.parse(File(RULES_FOLDER, "backup_rules.xml"))

        val result = rules.excludedFiles("full-backup-content")

        result shouldContain RECORDINGS_FOLDER
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN the rules for Android 12 and higher WHEN reading what cloud backup excludes THEN the flight logs are`() {
        val rules = builder.parse(File(RULES_FOLDER, "data_extraction_rules.xml"))

        val result = rules.excludedFiles("cloud-backup")

        result shouldContain RECORDINGS_FOLDER
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN the rules for Android 12 and higher WHEN reading what a transfer excludes THEN the flight logs are`() {
        val rules = builder.parse(File(RULES_FOLDER, "data_extraction_rules.xml"))

        val result = rules.excludedFiles("device-transfer")

        result shouldContain RECORDINGS_FOLDER
    }

    private fun Document.excludedFiles(section: String): List<String> {
        val excludes = (getElementsByTagName(section).item(0) as Element).getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == FILES_DOMAIN }
            .map { it.getAttribute("path") }
    }

    private companion object {
        const val RULES_FOLDER = "src/main/res/xml"
        const val FILES_DOMAIN = "file"

        /** `RECORDINGS_FOLDER` in `:data:vehicle`, internal to it. */
        const val RECORDINGS_FOLDER = "tlogs"
    }
}
