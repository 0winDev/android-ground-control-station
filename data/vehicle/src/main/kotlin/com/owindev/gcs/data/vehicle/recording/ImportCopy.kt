package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.ImportRule
import com.owindev.gcs.core.domain.recording.ImportSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TAG = "ImportCopy"
private const val COPY_CHUNK_SIZE = 64 * 1024

/** How copying an import into its temporary file ended. */
internal enum class ImportCopy { COPIED, TOO_LARGE, FAILED }

/**
 * Copies this source into [temporary], stopping as soon as it counts more than [rule]'s limit (REQ-038). A content
 * provider's call can throw any exception a Binder parcel carries, not only [IOException]; every one fails the
 * import, never the app, but cancellation.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ImportSource.copyInto(temporary: File, rule: ImportRule): ImportCopy = try {
    open().use { input -> FileOutputStream(temporary).use { output -> copyCounted(input, output, rule) } }
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    Log.w(TAG, "Could not read an import", e)
    ImportCopy.FAILED
} catch (e: RuntimeException) {
    Log.w(TAG, "The import's source failed", e)
    ImportCopy.FAILED
}

private suspend fun copyCounted(input: InputStream, output: FileOutputStream, rule: ImportRule): ImportCopy {
    val chunk = ByteArray(COPY_CHUNK_SIZE)
    var copied = 0L
    var read = input.read(chunk)
    while (read >= 0 && !rule.exceeds(copied + read)) {
        output.write(chunk, 0, read)
        copied += read
        currentCoroutineContext().ensureActive()
        read = input.read(chunk)
    }
    if (read >= 0) return ImportCopy.TOO_LARGE
    output.fd.sync()
    return ImportCopy.COPIED
}
