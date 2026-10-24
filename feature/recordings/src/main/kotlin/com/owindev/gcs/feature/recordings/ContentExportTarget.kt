package com.owindev.gcs.feature.recordings

import android.content.ContentResolver
import android.net.Uri
import com.owindev.gcs.core.domain.recording.ExportTarget
import java.io.IOException
import java.io.OutputStream

/**
 * A document the operator created with the system file picker (SAF) to save a recording to, written through
 * [resolver] and truncated first, so a document picked again holds only the new copy. Written only off the main
 * thread, by the copy.
 */
internal class ContentExportTarget(private val resolver: ContentResolver, private val uri: Uri) : ExportTarget {

    override fun open(): OutputStream =
        resolver.openOutputStream(uri, TRUNCATE) ?: throw IOException("The provider gave no stream")

    private companion object {
        const val TRUNCATE = "wt"
    }
}
