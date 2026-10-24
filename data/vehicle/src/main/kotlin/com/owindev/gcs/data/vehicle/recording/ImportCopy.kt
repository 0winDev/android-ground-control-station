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

internal enum class ImportCopy { COPIED, TOO_LARGE, RECORDING, FAILED }

/**
 * Copies this source into [temporary], stopping as soon as it counts more than [rule]'s limit or [isRecording] says a
 * flight is being recorded (REQ-036, REQ-038). A content provider's call can throw any exception a Binder parcel
 * carries, not only [IOException]; every one fails the import, never the app, but cancellation. A source stalled in a
 * read (a cloud document) is closed when the import is cancelled, so it never holds the next import back.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ImportSource.copyInto(temporary: File, rule: ImportRule, isRecording: () -> Boolean): ImportCopy =
    try {
        open().closingOnCancel { input ->
            FileOutputStream(temporary).use { output -> copyCounted(input, output, rule, isRecording) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "Could not read an import: ${e.javaClass.simpleName}")
        ImportCopy.FAILED
    } catch (e: RuntimeException) {
        Log.w(TAG, "The import's source failed: ${e.javaClass.simpleName}")
        ImportCopy.FAILED
    }

private suspend fun copyCounted(
    input: InputStream,
    output: FileOutputStream,
    rule: ImportRule,
    isRecording: () -> Boolean,
): ImportCopy {
    val chunk = ByteArray(COPY_CHUNK_SIZE)
    var copied = 0L
    var read = input.read(chunk)
    while (read >= 0 && !rule.exceeds(copied + read) && !isRecording()) {
        output.write(chunk, 0, read)
        copied += read
        currentCoroutineContext().ensureActive()
        read = input.read(chunk)
    }
    return when {
        isRecording() -> ImportCopy.RECORDING
        read >= 0 -> ImportCopy.TOO_LARGE
        else -> ImportCopy.COPIED.also { output.fd.sync() }
    }
}
