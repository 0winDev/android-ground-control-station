package com.owindev.gcs.navigation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.owindev.gcs.R
import java.io.File

/**
 * Offers [recording] to the Android share sheet (REQ-038) through the app's FileProvider, which serves the recordings
 * folder and nothing else, read-only and only to the app the operator picks.
 */
internal fun Context.shareRecording(recording: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName$RECORDINGS_AUTHORITY_SUFFIX", recording)
    // The read grant covers the Uris in ClipData; set it ourselves rather than rely on the platform copying the extra.
    val send = Intent(Intent.ACTION_SEND)
        .setType(TLOG_TYPE)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(recording.name, uri)
    try {
        startActivity(Intent.createChooser(send, getString(R.string.app_share_recording)))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app can receive a recording", e)
    }
}

/** Matches the provider's authority in the manifest. */
private const val RECORDINGS_AUTHORITY_SUFFIX = ".recordings"

/** A .tlog has no registered MIME type. */
private const val TLOG_TYPE = "application/octet-stream"

private const val TAG = "RecordingShare"
