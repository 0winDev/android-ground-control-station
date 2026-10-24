package com.owindev.gcs.feature.recordings

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The provider is another app's (REQ-038): the target opens the document truncated and throws only as an IO error. */
@Tag("REQ-038")
class ContentExportTargetTest {

    private val resolver: ContentResolver = mockk()
    private val uri: Uri = mockk()

    private lateinit var target: ContentExportTarget

    @BeforeEach
    fun setUp() {
        target = ContentExportTarget(resolver, uri)
    }

    @Test
    fun `GIVEN a provider WHEN opening the document THEN it is opened truncated`() {
        val stream: OutputStream = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri, TRUNCATE) } returns stream

        val result = target.open()

        result shouldBe stream
    }

    @Test
    fun `GIVEN a provider that gives no stream WHEN opening the document THEN fails as an IO error`() {
        every { resolver.openOutputStream(uri, TRUNCATE) } returns null

        val result = invoking { target.open() }

        result shouldThrow IOException::class
    }

    private companion object {
        const val TRUNCATE = "wt"
    }
}
