package com.owindev.gcs.feature.recordings

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.IOException
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The provider is untrusted (REQ-038): whatever it does, the source says what it knows and throws only when opened.
 */
@Tag("REQ-038")
class ContentImportSourceTest {

    private val resolver: ContentResolver = mockk()
    private val uri: Uri = mockk()
    private val cursor: Cursor = mockk()

    private lateinit var source: ContentImportSource

    @BeforeEach
    fun setUp() {
        source = ContentImportSource(resolver, uri)
    }

    @Test
    fun `GIVEN a provider with name and size WHEN describing the document THEN both are given`() {
        describedAs(name = NAME, size = SIZE)

        val result = source.displayName to source.declaredBytes

        result shouldBeEqualTo (NAME to SIZE)
    }

    @Test
    fun `GIVEN a provider without size WHEN describing the document THEN the size is unknown`() {
        describedAs(name = NAME, size = null)

        val result = source.displayName to source.declaredBytes

        result shouldBeEqualTo (NAME to null)
    }

    @Test
    fun `GIVEN a provider that refuses the query WHEN describing the document THEN nothing is known`() {
        every { resolver.query(uri, any(), null, null, null) } throws SecurityException("revoked")

        val result = source.displayName to source.declaredBytes

        result shouldBeEqualTo (null to null)
    }

    @Test
    fun `GIVEN a provider with no row WHEN describing the document THEN nothing is known`() {
        every { resolver.query(uri, any(), null, null, null) } returns cursor
        every { cursor.moveToFirst() } returns false
        every { cursor.close() } just runs

        val result = source.displayName to source.declaredBytes

        result shouldBeEqualTo (null to null)
    }

    @Test
    fun `GIVEN a provider that gives no stream WHEN opening the document THEN fails as an IO error`() {
        every { resolver.openInputStream(uri) } returns null

        val result = invoking { source.open() }

        result shouldThrow IOException::class
    }

    private fun describedAs(name: String?, size: Long?) {
        every { resolver.query(uri, any(), null, null, null) } returns cursor
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns NAME_COLUMN
        every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns SIZE_COLUMN
        every { cursor.isNull(NAME_COLUMN) } returns (name == null)
        every { cursor.isNull(SIZE_COLUMN) } returns (size == null)
        every { cursor.getString(NAME_COLUMN) } returns name
        every { cursor.getLong(SIZE_COLUMN) } returns (size ?: 0)
        every { cursor.close() } just runs
    }

    private companion object {
        const val NAME = "flight.tlog"
        const val SIZE = 49_741L
        const val NAME_COLUMN = 0
        const val SIZE_COLUMN = 1
    }
}
