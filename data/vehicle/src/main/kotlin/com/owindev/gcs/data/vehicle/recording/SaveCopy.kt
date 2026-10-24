package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.ExportTarget
import com.owindev.gcs.core.domain.recording.SaveResult
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "SaveCopy"

/**
 * Copies this recording to [target] (REQ-038), both streams closed when the caller is cancelled. A target is a
 * document from another app's provider, which can throw any exception a Binder parcel carries; every one fails the
 * copy, never the app, but cancellation. Logs only the kind of a failure (`docs/security.md` V-3).
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun File.copyTo(target: ExportTarget): SaveResult = try {
    inputStream().closingOnCancel { input ->
        target.open().closingOnCancel { output -> input.copyTo(output) }
    }
    SaveResult.Saved
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    Log.w(TAG, "Could not save a copy: ${e.javaClass.simpleName}")
    SaveResult.Failed
} catch (e: RuntimeException) {
    Log.w(TAG, "The copy's target failed: ${e.javaClass.simpleName}")
    SaveResult.Failed
}
