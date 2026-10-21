package com.owindev.gcs.data.vehicle.recording

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The name of the last flight log closed, kept in [directory] across app restarts (REQ-039), so retention never has to
 * guess it from file times that a wrong wall clock or an imported log can reorder. It is not a recording: without the
 * `.tlog` extension it never counts for the limit nor shows among the recordings, so it is never exported.
 */
internal class LastClosedMarker(private val directory: File) {

    /** The log it names, or `null` when it is missing, unreadable, not a log name or names a log no longer there. */
    fun read(): String? {
        val file = File(directory, FILE_NAME)
        val name = if (file.isFile && file.length() <= MAX_BYTES) textOf(file) else null
        return name?.takeIf { it.matches(LOG_NAME) && File(directory, it).isFile }
    }

    /** Names [log], on the disk before it returns; a crash leaves the old name or the new one, never a torn file. */
    fun write(log: String) {
        val temporary = File(directory, TEMPORARY_NAME)
        FileOutputStream(temporary).use { output ->
            output.write(log.toByteArray())
            output.fd.sync()
        }
        Files.move(temporary.toPath(), File(directory, FILE_NAME).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun textOf(file: File): String? = try {
        file.readText()
    } catch (_: IOException) {
        null
    }

    companion object {
        const val FILE_NAME = ".last-closed"
        const val TEMPORARY_NAME = ".last-closed.tmp"

        /** Far above any log name this recorder gives, so a longer file is not one it wrote. */
        private const val MAX_BYTES = 255L

        /** One file in the folder: no separators, so a name can never point outside it. */
        val LOG_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.tlog")
    }
}
