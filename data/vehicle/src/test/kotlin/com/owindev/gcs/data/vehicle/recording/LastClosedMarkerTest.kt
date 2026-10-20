package com.owindev.gcs.data.vehicle.recording

import java.io.File
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@Tag("REQ-039")
class LastClosedMarkerTest {

    @TempDir
    lateinit var temp: File

    private val directory get() = File(temp, RECORDINGS_FOLDER)

    private lateinit var marker: LastClosedMarker

    @BeforeEach
    fun setUp() {
        directory.mkdirs()
        marker = LastClosedMarker(directory)
    }

    @Test
    fun `GIVEN no marker WHEN reading it THEN names no log`() {
        log(FIRST)

        val result = marker.read()

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a marker written for a log on disk WHEN reading it THEN names that log`() {
        log(FIRST)
        marker.write(FIRST)

        val result = marker.read()

        result shouldBeEqualTo FIRST
    }

    @Test
    fun `GIVEN a marker written twice WHEN reading it THEN names the second log and leaves no temporary file`() {
        log(FIRST)
        log(SECOND)
        marker.write(FIRST)
        marker.write(SECOND)

        val result = marker.read() to File(directory, LastClosedMarker.TEMPORARY_NAME).exists()

        result shouldBeEqualTo (SECOND to false)
    }

    @Test
    fun `GIVEN a marker naming a log no longer on disk WHEN reading it THEN names no log`() {
        marker.write(FIRST)

        val result = marker.read()

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a marker of bytes that are not a name WHEN reading it THEN names no log`() {
        log(FIRST)
        markerFile().writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x0A))

        val result = marker.read()

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a marker naming a file that is not a log WHEN reading it THEN names no log`() {
        log(NOT_A_LOG)
        markerFile().writeText(NOT_A_LOG)

        val result = marker.read()

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a marker naming a log outside the folder WHEN reading it THEN names no log`() {
        File(temp, OUTSIDE).writeBytes(ByteArray(1))
        markerFile().writeText("../$OUTSIDE")

        val result = marker.read()

        result.shouldBeNull()
    }

    @Test
    fun `GIVEN a marker longer than any log name WHEN reading it THEN names no log`() {
        log(FIRST)
        markerFile().writeText(FIRST + " ".repeat(TOO_LONG))

        val result = marker.read()

        result.shouldBeNull()
    }

    private fun log(name: String) = File(directory, name).writeBytes(ByteArray(1))

    private fun markerFile() = File(directory, LastClosedMarker.FILE_NAME)

    private companion object {
        const val FIRST = "2026-10-20_14-30-05.tlog"
        const val SECOND = "2026-10-20_14-32-05_1.tlog"
        const val NOT_A_LOG = "notes.txt"
        const val OUTSIDE = "outside.tlog"
        const val TOO_LONG = 256
    }
}
