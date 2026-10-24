package com.owindev.gcs.feature.recordings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.owindev.gcs.core.domain.recording.ImportSource
import java.io.IOException
import java.io.InputStream

/**
 * A document picked with the system file picker (SAF), read through [resolver]. Its name and size are what its
 * provider says, unchecked (REQ-038): a provider that fails to say gives neither, and is read anyway, since the import
 * counts the bytes it copies. Read only off the main thread, by the import.
 */
internal class ContentImportSource(private val resolver: ContentResolver, private val uri: Uri) : ImportSource {

    private val described by lazy { describe() }

    override val displayName: String? get() = described.displayName

    override val declaredBytes: Long? get() = described.sizeBytes

    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("The provider gave no stream")

    /**
     * A provider's query can throw any exception a Binder parcel carries; none of them may stop the import, which
     * fails on its own when the document cannot be opened.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun describe(): Description = try {
        resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE)
            Description(
                displayName = name.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString),
                sizeBytes = size.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
            )
        } ?: Description()
    } catch (failure: RuntimeException) {
        Description()
    }

    private data class Description(val displayName: String? = null, val sizeBytes: Long? = null)

    private companion object {
        val COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
