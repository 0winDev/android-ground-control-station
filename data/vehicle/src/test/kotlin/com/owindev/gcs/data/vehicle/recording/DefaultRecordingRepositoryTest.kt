package com.owindev.gcs.data.vehicle.recording

import android.util.Log
import app.cash.turbine.test
import com.owindev.gcs.core.domain.recording.DeleteResult
import com.owindev.gcs.core.domain.recording.ExportTarget
import com.owindev.gcs.core.domain.recording.ImportResult
import com.owindev.gcs.core.domain.recording.ImportRule
import com.owindev.gcs.core.domain.recording.ImportSource
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.recording.RecordingSummary
import com.owindev.gcs.core.domain.recording.SaveResult
import com.owindev.gcs.core.domain.replay.ReplayRepository
import com.owindev.gcs.core.domain.replay.ReplayState
import com.owindev.gcs.core.mavlink.TlogReader
import com.owindev.gcs.core.testing.readFixture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Real files in a temporary folder and the real scanner; the recorder's status and the replay's state are hand-fed.
 * Expected spans from pymavlink 2.4.50 (see `RecordingScanTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-038")
class DefaultRecordingRepositoryTest {

    @TempDir
    lateinit var temp: File

    private val testScope = TestScope()
    private val recorder: FlightRecorder = mockk()
    private val replay: ReplayRepository = mockk()
    private val recorderStatus = MutableStateFlow(RecorderStatus.IDLE)
    private val replayState = MutableStateFlow<ReplayState>(ReplayState.Off)
    private val qgroundcontrolLog = readFixture(QGROUNDCONTROL_LOG)
    private val directory get() = File(temp, RECORDINGS_FOLDER).apply { mkdirs() }
    private val folder by lazy { RecordingsFolder(directory, RecordingRule(maxTotalBytes = LIMIT_BYTES)) }

    private lateinit var repository: DefaultRecordingRepository

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { recorder.status } returns recorderStatus
        every { replay.state } returns replayState
        repository = DefaultRecordingRepository(
            folder = folder,
            recorder = recorder,
            replay = replay,
            rule = ImportRule(maxBytes = LIMIT_BYTES),
            ioDispatcher = StandardTestDispatcher(testScope.testScheduler),
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN QGroundControl's log WHEN listing THEN shows its start, duration and size`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)

        repository.recordings.test {
            val result = awaitItem()

            result shouldBeEqualTo listOf(summary(OLDEST))
        }
    }

    @Test
    fun `GIVEN a log with no valid frame WHEN listing THEN it is listed without start nor duration`() =
        testScope.runTest {
            log(OLDEST, Random(SEED).nextBytes(GARBAGE_BYTES))

            repository.recordings.test {
                val result = awaitItem()

                result shouldBeEqualTo listOf(
                    RecordingSummary(
                        name = OLDEST,
                        startMillis = null,
                        duration = null,
                        sizeBytes = GARBAGE_BYTES.toLong(),
                        inProgress = false,
                        replaying = false,
                    ),
                )
            }
        }

    @Test
    fun `GIVEN two logs WHEN listing THEN the one that started last comes first`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)
        log(NEWEST, shiftedLog())

        repository.recordings.test {
            val result = awaitItem()

            result.map(RecordingSummary::name) shouldBeEqualTo listOf(NEWEST, OLDEST)
        }
    }

    @Test
    fun `GIVEN the marker and an import's leftover WHEN listing THEN only the recordings`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)
        folder.marker.write(OLDEST)
        File(directory, LEFTOVER).writeBytes(qgroundcontrolLog)

        repository.recordings.test {
            val result = awaitItem()

            result.map(RecordingSummary::name) shouldBeEqualTo listOf(OLDEST)
        }
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a listed log WHEN the recorder starts writing it THEN it is shown in progress`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)
        repository.recordings.test {
            awaitItem()

            folder.recording = OLDEST
            recorderStatus.value = RecorderStatus.RECORDING
            val result = awaitItem()

            result shouldBeEqualTo listOf(summary(OLDEST).copy(inProgress = true))
        }
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a listed log WHEN a replay starts on it THEN it is shown replaying`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)
        repository.recordings.test {
            awaitItem()

            folder.replayed = OLDEST
            replayState.value = ReplayState.Playing(File(directory, OLDEST))
            val result = awaitItem()

            result shouldBeEqualTo listOf(summary(OLDEST).copy(replaying = true))
        }
    }

    @Test
    fun `GIVEN a listed log WHEN deleting it THEN it is gone from the list`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)
        repository.recordings.test {
            awaitItem()

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.delete(OLDEST) }
            val result = deferred.await()

            (result to awaitItem()) shouldBeEqualTo (DeleteResult.Deleted to emptyList())
        }
    }

    @Test
    fun `GIVEN QGroundControl's log WHEN importing it THEN it is listed under its name`() = testScope.runTest {
        repository.recordings.test {
            awaitItem()

            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                repository.import(BytesSource(qgroundcontrolLog, QGROUNDCONTROL_NAME))
            }
            val result = deferred.await()

            (result to awaitItem()) shouldBeEqualTo
                (
                    ImportResult.Imported(QGROUNDCONTROL_NAME, deleted = emptyList()) to
                        listOf(summary(QGROUNDCONTROL_NAME))
                    )
        }
    }

    @Tag("REQ-039")
    @Test
    fun `GIVEN an import over the limit with an older log WHEN importing THEN the older one is named as deleted`() =
        testScope.runTest {
            log(OLDEST, qgroundcontrolLog, lastModifiedMillis = 1)
            log(NEWEST, qgroundcontrolLog, lastModifiedMillis = 2)
            folder.marker.write(NEWEST)

            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                repository.import(BytesSource(qgroundcontrolLog, QGROUNDCONTROL_NAME))
            }
            val result = deferred.await()

            (result to logs()) shouldBeEqualTo (
                ImportResult.Imported(QGROUNDCONTROL_NAME, deleted = listOf(OLDEST)) to
                    listOf(NEWEST, QGROUNDCONTROL_NAME)
                )
        }

    @Test
    fun `GIVEN a source declaring more than the limit WHEN importing THEN refused without opening it`() =
        testScope.runTest {
            val declared = LIMIT_BYTES + 1
            val source = BytesSource(qgroundcontrolLog, QGROUNDCONTROL_NAME, declaredBytes = declared)

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.import(source) }
            val result = deferred.await()

            (result to source.opened) shouldBeEqualTo (ImportResult.TooLarge(LIMIT_BYTES, declared) to false)
        }

    @Test
    fun `GIVEN a source that lies about its size WHEN importing THEN the copy stops at the limit and leaves nothing`() =
        testScope.runTest {
            val bytes = Random(SEED).nextBytes(LIMIT_BYTES.toInt() + 1)

            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                repository.import(BytesSource(bytes, QGROUNDCONTROL_NAME, declaredBytes = 1))
            }
            val result = deferred.await()

            (result to files()) shouldBeEqualTo (ImportResult.TooLarge(LIMIT_BYTES) to emptyList())
        }

    @Test
    fun `GIVEN random bytes WHEN importing them THEN not a tlog and nothing is left`() = testScope.runTest {
        val bytes = Random(SEED).nextBytes(GARBAGE_BYTES)

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            repository.import(BytesSource(bytes, QGROUNDCONTROL_NAME))
        }
        val result = deferred.await()

        (result to files()) shouldBeEqualTo (ImportResult.NotATlog to emptyList())
    }

    @Test
    fun `GIVEN an empty file WHEN importing it THEN not a tlog`() = testScope.runTest {
        val bytes = ByteArray(0)

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            repository.import(BytesSource(bytes, QGROUNDCONTROL_NAME))
        }
        val result = deferred.await()

        result shouldBeEqualTo ImportResult.NotATlog
    }

    @Test
    fun `GIVEN a source that fails in every way a provider can WHEN importing THEN each fails and nothing is left`() =
        testScope.runTest {
            val failures = listOf(IOException("gone"), SecurityException("revoked"), IllegalStateException("closed"))

            val result = failures.map { failure -> repository.import(FailingSource(failure)) }

            (result to files()) shouldBeEqualTo (List(failures.size) { ImportResult.Failed } to emptyList())
        }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a flight being recorded WHEN importing THEN refused without opening the source`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)
        folder.recording = OLDEST
        val source = BytesSource(qgroundcontrolLog, QGROUNDCONTROL_NAME)

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.import(source) }
        val result = deferred.await()

        (result to source.opened) shouldBeEqualTo (ImportResult.Recording to false)
    }

    @Tag("REQ-036")
    @Test
    fun `GIVEN a recording that opens while copying WHEN importing THEN refused and nothing is left`() =
        testScope.runTest {
            val source = RecordingOpensSource(qgroundcontrolLog)

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.import(source) }
            val result = deferred.await()

            (result to files()) shouldBeEqualTo (ImportResult.Recording to emptyList())
        }

    @Test
    fun `GIVEN a source failing halfway WHEN importing THEN fails and nothing is left`() = testScope.runTest {
        val source = BytesSource(qgroundcontrolLog, QGROUNDCONTROL_NAME, failAfterBytes = HALFWAY_BYTES)

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.import(source) }
        val result = deferred.await()

        (result to files()) shouldBeEqualTo (ImportResult.Failed to emptyList())
    }

    @Test
    fun `GIVEN a leftover of an import cut short WHEN importing THEN the leftover is deleted`() = testScope.runTest {
        File(directory, LEFTOVER).writeBytes(qgroundcontrolLog)

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            repository.import(BytesSource(qgroundcontrolLog, QGROUNDCONTROL_NAME))
        }
        deferred.await()

        files() shouldBeEqualTo listOf(QGROUNDCONTROL_NAME)
    }

    @Test
    fun `GIVEN a recording WHEN asking for its file THEN its real path`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)

        val result = repository.fileOf(OLDEST)

        result shouldBeEqualTo File(directory, OLDEST).toPath().toRealPath().toFile()
    }

    @Test
    fun `GIVEN a closed recording WHEN saving a copy THEN the target gets every byte`() = testScope.runTest {
        log(OLDEST, qgroundcontrolLog)
        val target = BytesTarget()

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.saveCopy(OLDEST, target) }
        val result = deferred.await()

        (result to target.bytes.toList()) shouldBeEqualTo (SaveResult.Saved to qgroundcontrolLog.toList())
    }

    @Test
    fun `GIVEN the recording in progress WHEN saving a copy THEN refused without opening the target`() =
        testScope.runTest {
            log(OLDEST, qgroundcontrolLog)
            folder.recording = OLDEST
            val target = BytesTarget()

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { repository.saveCopy(OLDEST, target) }
            val result = deferred.await()

            (result to target.opened) shouldBeEqualTo (SaveResult.InProgress to false)
        }

    @Test
    fun `GIVEN no such recording or one outside the folder WHEN saving a copy THEN fails`() = testScope.runTest {
        File(temp, OLDEST).writeBytes(qgroundcontrolLog)
        val target = BytesTarget()

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            listOf(repository.saveCopy(OLDEST, target), repository.saveCopy("../$OLDEST", target))
        }
        val result = deferred.await()

        (result to target.opened) shouldBeEqualTo (listOf(SaveResult.Failed, SaveResult.Failed) to false)
    }

    @Test
    fun `GIVEN a target failing with an IO, security or state exception WHEN saving a copy THEN fails`() =
        testScope.runTest {
            log(OLDEST, qgroundcontrolLog)
            val failures = listOf(IOException("full"), SecurityException("revoked"), IllegalStateException("dead"))

            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                failures.map { repository.saveCopy(OLDEST, FailingTarget(it)) }
            }
            val result = deferred.await()

            result shouldBeEqualTo List(failures.size) { SaveResult.Failed }
        }

    @Test
    fun `GIVEN a name leaving the folder WHEN asking for its file THEN none`() = testScope.runTest {
        File(temp, OLDEST).writeBytes(qgroundcontrolLog)

        val result = repository.fileOf("../$OLDEST")

        result shouldBeEqualTo null
    }

    private fun summary(name: String) = RecordingSummary(
        name = name,
        startMillis = FIRST_MILLIS,
        duration = DURATION_MILLIS.milliseconds,
        sizeBytes = qgroundcontrolLog.size.toLong(),
        inProgress = false,
        replaying = false,
    )

    private fun log(name: String, bytes: ByteArray, lastModifiedMillis: Long? = null) {
        File(directory, name).apply {
            writeBytes(bytes)
            lastModifiedMillis?.let(::setLastModified)
        }
    }

    /** QGroundControl's log with every record stamped one hour later; the frames are untouched. */
    private fun shiftedLog(): ByteArray {
        val shifted = qgroundcontrolLog.copyOf()
        val reader = TlogReader()
        var offset = 0
        (reader.read(qgroundcontrolLog) + reader.finish()).forEach { record ->
            val stamp = record.timestampMicros + ONE_HOUR_MICROS
            for (i in 0 until STAMP_BYTES) {
                shifted[offset + i] = (stamp ushr (Long.SIZE_BITS - Byte.SIZE_BITS * (i + 1))).toByte()
            }
            offset += STAMP_BYTES + record.frame.size
        }
        return shifted
    }

    private fun files(): List<String> = directory.list().orEmpty().sorted()

    private fun logs(): List<String> = directory.list { _, name -> name.endsWith(".tlog") }.orEmpty().sorted()

    /** Bytes from elsewhere; [failAfterBytes] makes the stream fail as a provider whose file went away. */
    private class BytesSource(
        private val bytes: ByteArray,
        override val displayName: String?,
        override val declaredBytes: Long? = bytes.size.toLong(),
        private val failAfterBytes: Int? = null,
    ) : ImportSource {
        var opened = false

        override fun open(): InputStream {
            opened = true
            val limit = failAfterBytes ?: return bytes.inputStream()
            return object : InputStream() {
                private var position = 0

                override fun read(): Int {
                    if (position == limit) throw IOException("The provider went away")
                    return if (position < bytes.size) bytes[position++].toInt() and BYTE_MASK else -1
                }
            }
        }
    }

    /** A vehicle heard as the copy starts: the recorder opens a log while the import reads. */
    private inner class RecordingOpensSource(private val bytes: ByteArray) : ImportSource {
        override val displayName = QGROUNDCONTROL_NAME
        override val declaredBytes: Long? = bytes.size.toLong()

        override fun open(): InputStream {
            folder.recording = OPENED_DURING_COPY
            return bytes.inputStream()
        }
    }

    /** Another app's document, kept in memory. */
    private class BytesTarget : ExportTarget {
        private val output = ByteArrayOutputStream()
        var opened = false
        val bytes: ByteArray get() = output.toByteArray()

        override fun open(): OutputStream {
            opened = true
            return output
        }
    }

    private class FailingTarget(private val failure: Exception) : ExportTarget {
        override fun open(): OutputStream = throw failure
    }

    private class FailingSource(private val failure: Exception) : ImportSource {
        override val displayName = QGROUNDCONTROL_NAME
        override val declaredBytes: Long? = null

        override fun open(): InputStream = throw failure
    }

    private companion object {
        const val QGROUNDCONTROL_LOG = "replay/qgroundcontrol.tlog"
        const val QGROUNDCONTROL_NAME = "qgroundcontrol.tlog"
        const val OPENED_DURING_COPY = "2026-10-24_10-00-00.tlog"
        const val FIRST_MILLIS = 1_792_091_306_747L
        const val DURATION_MILLIS = 9_935L
        const val LIMIT_BYTES = 120_000L
        const val SEED = 74
        const val GARBAGE_BYTES = 4_096
        const val HALFWAY_BYTES = 20_000
        const val OLDEST = "2026-10-20_14-30-05.tlog"
        const val NEWEST = "2026-10-21_09-12-44.tlog"
        const val LEFTOVER = ".import-1234.tmp"
        const val ONE_HOUR_MICROS = 3_600_000_000L
        const val STAMP_BYTES = 8
        const val BYTE_MASK = 0xFF
    }
}
