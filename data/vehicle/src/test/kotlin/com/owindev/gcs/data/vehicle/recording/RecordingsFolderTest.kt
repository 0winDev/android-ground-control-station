package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.ImportRule
import com.owindev.gcs.core.domain.recording.RecordingRule
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real files in a temporary folder, with a limit of [LIMIT_BYTES]. */
class RecordingsFolderTest {

    @TempDir
    lateinit var temp: File

    private val directory get() = File(temp, RECORDINGS_FOLDER).apply { mkdirs() }
    private val names = ImportRule(maxBytes = LIMIT_BYTES)

    private lateinit var folder: RecordingsFolder

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        folder = RecordingsFolder(directory, RecordingRule(maxTotalBytes = LIMIT_BYTES))
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Tag("REQ-038")
    @Test
    fun `GIVEN a closed recording WHEN deleting it THEN it is gone`() {
        log(OLDEST, 1, lastModifiedMillis = 1)

        val result = folder.delete(OLDEST)

        (result to logs()) shouldBeEqualTo (DeleteResult.Deleted to emptyList())
    }

    @Tag("REQ-038")
    @Test
    fun `GIVEN the recording in progress WHEN deleting it THEN it is refused and kept`() {
        log(OLDEST, 1, lastModifiedMillis = 1)
        folder.recording = OLDEST

        val result = folder.delete(OLDEST)

        (result to logs()) shouldBeEqualTo (DeleteResult.InProgress to listOf(OLDEST))
    }

    @Tag("REQ-038")
    @Tag("REQ-037")
    @Test
    fun `GIVEN the recording being replayed WHEN deleting it THEN it is refused and kept`() {
        log(OLDEST, 1, lastModifiedMillis = 1)
        folder.replayed = OLDEST

        val result = folder.delete(OLDEST)

        (result to logs()) shouldBeEqualTo (DeleteResult.Replaying to listOf(OLDEST))
    }

    @Tag("REQ-038")
    @Test
    fun `GIVEN no such recording WHEN deleting it THEN not found`() {
        val name = OLDEST

        val result = folder.delete(name)

        result shouldBeEqualTo DeleteResult.NotFound
    }

    @Tag("REQ-038")
    @Test
    fun `GIVEN a name that leaves the folder WHEN deleting it THEN not found and the file outside is kept`() {
        val outside = File(temp, OLDEST).apply { writeBytes(ByteArray(1)) }

        val result = folder.delete("../$OLDEST")

        (result to outside.isFile) shouldBeEqualTo (DeleteResult.NotFound to true)
    }

    @Tag("REQ-038")
    @Test
    fun `GIVEN the marker's name WHEN deleting it THEN not found and the marker is kept`() {
        log(OLDEST, 1, lastModifiedMillis = 1)
        folder.marker.write(OLDEST)

        val result = folder.delete(LastClosedMarker.FILE_NAME)

        (result to folder.marker.read()) shouldBeEqualTo (DeleteResult.NotFound to OLDEST)
    }

    @Tag("REQ-038")
    @Test
    fun `GIVEN room for an import WHEN admitting it THEN it joins the recordings under its own name`() {
        val copy = temporary(1)

        val result = folder.admit(copy, "flight from QGC.tlog", names)

        (result to logs()) shouldBeEqualTo
            (ImportResult.Imported("flight_from_QGC.tlog", deleted = emptyList()) to listOf("flight_from_QGC.tlog"))
    }

    @Tag("REQ-038")
    @Test
    fun `GIVEN a recording with the import's name WHEN admitting it THEN the import takes the next free name`() {
        log(OLDEST, 1, lastModifiedMillis = 1)
        val copy = temporary(1)

        val result = folder.admit(copy, OLDEST, names)

        result shouldBeEqualTo ImportResult.Imported(OLDEST_TAKEN, deleted = emptyList())
    }

    @Tag("REQ-038")
    @Tag("REQ-039")
    @Test
    fun `GIVEN an import over the limit with the others WHEN admitting it THEN the oldest go and are named`() {
        log(OLDEST, HALF, lastModifiedMillis = 1)
        log(MIDDLE, HALF, lastModifiedMillis = 2)
        folder.marker.write(MIDDLE)
        val copy = temporary(HALF)

        val result = folder.admit(copy, IMPORTED, names)

        (result to logs()) shouldBeEqualTo
            (ImportResult.Imported(IMPORTED, deleted = listOf(OLDEST)) to listOf(MIDDLE, IMPORTED))
    }

    @Tag("REQ-038")
    @Tag("REQ-039")
    @Test
    fun `GIVEN the recording in progress as the oldest WHEN an import goes over the limit THEN it is kept`() {
        log(OLDEST, HALF, lastModifiedMillis = 1)
        log(MIDDLE, HALF / 2, lastModifiedMillis = 2)
        folder.recording = OLDEST
        val copy = temporary(HALF)

        val result = folder.admit(copy, IMPORTED, names)

        (result to logs()) shouldBeEqualTo
            (ImportResult.Imported(IMPORTED, deleted = listOf(MIDDLE)) to listOf(OLDEST, IMPORTED))
    }

    @Tag("REQ-038")
    @Tag("REQ-039")
    @Test
    fun `GIVEN the protected recordings fill the limit WHEN admitting an import THEN no room and nothing changes`() {
        log(OLDEST, HALF, lastModifiedMillis = 1)
        log(MIDDLE, HALF, lastModifiedMillis = 2)
        folder.recording = OLDEST
        folder.marker.write(MIDDLE)
        val copy = temporary(1)

        val result = folder.admit(copy, IMPORTED, names)

        (result to logs()) shouldBeEqualTo (ImportResult.NoRoom to listOf(OLDEST, MIDDLE))
    }

    @Tag("REQ-038")
    @Test
    fun `GIVEN the marker and a temporary file WHEN listing the recordings THEN only the recordings`() {
        log(OLDEST, 1, lastModifiedMillis = 1)
        folder.marker.write(OLDEST)
        temporary(1)

        val result = folder.recordingFiles().map(File::getName)

        result shouldBeEqualTo listOf(OLDEST)
    }

    private fun log(name: String, sizeBytes: Long, lastModifiedMillis: Long) {
        File(directory, name).apply {
            writeBytes(ByteArray(sizeBytes.toInt()))
            setLastModified(lastModifiedMillis)
        }
    }

    private fun temporary(sizeBytes: Long): File =
        File(directory, ".import-1.tmp").apply { writeBytes(ByteArray(sizeBytes.toInt())) }

    private fun logs(): List<String> = directory.list { _, name -> name.endsWith(".tlog") }.orEmpty().sorted()

    private companion object {
        const val LIMIT_BYTES = 1_000L
        const val HALF = LIMIT_BYTES / 2
        const val OLDEST = "2026-10-20_14-30-05.tlog"
        const val OLDEST_TAKEN = "2026-10-20_14-30-05_1.tlog"
        const val MIDDLE = "2026-10-21_09-12-44.tlog"
        const val IMPORTED = "imported.tlog"
    }
}
