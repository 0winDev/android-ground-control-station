package com.owindev.gcs.data.vehicle.replay

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.replay.ReplayFailure
import com.owindev.gcs.core.domain.replay.ReplayRefusal
import com.owindev.gcs.core.domain.replay.ReplayRule
import com.owindev.gcs.core.domain.replay.ReplayState
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.testing.verifyNever
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.core.transport.DatagramSource
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.LinkStatus
import com.owindev.gcs.data.vehicle.recording.RecordingsFolder
import io.mockk.CapturingSlot
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The session is a hand-fed contract; the pin, the clocks and the rule are the real ones; files are real. */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-037")
class DefaultReplayRepositoryTest {

    @TempDir
    lateinit var temp: File

    private val testScope = TestScope()
    private val device = TestTimeSource()
    private val clock = PipelineClock(device)
    private val session: LinkSession = mockk()
    private val status = MutableStateFlow<LinkStatus>(LinkStatus.Running(datagrams = 0))
    private val vehicleRepository: VehicleRepository = mockk()
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val replayClock: CapturingSlot<ReplayClock> = slot()
    private val events = mutableListOf<String>()
    private val replayed = mutableListOf<File>()
    private val gapReports = mutableListOf<(Duration?) -> Unit>()
    private val recordings get() = File(temp, "tlogs").apply { mkdirs() }
    private val recording get() = File(recordings, "2026-10-16_17-09-58.tlog").apply { writeBytes(ByteArray(0)) }
    private val folder by lazy { RecordingsFolder(recordings, RecordingRule()) }

    private lateinit var repository: DefaultReplayRepository

    @BeforeEach
    fun setUp() {
        every { session.status } returns status
        every { session.replay(any(), capture(replayClock)) } answers { events += "replay keeping ${folder.replayed}" }
        every { session.live() } answers { events += "live keeping ${folder.replayed}" }
        every { vehicleRepository.vehicles } returns vehicles
        repository = DefaultReplayRepository(
            session = session,
            vehicleRepository = vehicleRepository,
            clock = clock,
            folder = folder,
            rule = ReplayRule(LinkLossRule()),
            replaySource = { file, _, onGap ->
                replayed += file
                gapReports += onGap
                emptySource()
            },
            scope = testScope.backgroundScope,
        )
    }

    @Test
    fun `GIVEN no live vehicle WHEN playing a recording THEN plays it through the session`() {
        val file = recording

        val result = repository.play(file)

        result shouldBeEqualTo ReplayState.Playing(file)
        replayed shouldBeEqualTo listOf(file.toPath().toRealPath().toFile())
        verifyOnce {
            session.replay(any(), any())
        }
    }

    @Test
    fun `GIVEN a live vehicle lost and disarmed WHEN playing a recording THEN plays it`() {
        liveVehicle(link = VehicleLink.LOST, armed = false)
        device += LONG_SILENT
        val file = recording

        val result = repository.play(file)

        result shouldBeEqualTo ReplayState.Playing(file)
    }

    @Test
    fun `GIVEN a live vehicle connected WHEN playing a recording THEN is refused and the live link stays open`() {
        liveVehicle(link = VehicleLink.CONNECTED, armed = false)
        val file = recording

        val result = repository.play(file)

        result shouldBeEqualTo ReplayState.Refused(ReplayRefusal.VEHICLE_CONNECTED)
        replayed shouldBeEqualTo emptyList()
        verifyNever {
            session.replay(any(), any())
        }
    }

    @Test
    fun `GIVEN a live vehicle armed and lost WHEN playing a recording THEN is refused as armed`() {
        liveVehicle(link = VehicleLink.LOST, armed = true)
        device += LONG_SILENT
        val file = recording

        val result = repository.play(file)

        result shouldBeEqualTo ReplayState.Refused(ReplayRefusal.VEHICLE_ARMED)
        verifyNever {
            session.replay(any(), any())
        }
    }

    @Test
    fun `GIVEN a refused replay WHEN reading the state THEN says so and the link is still the live one`() {
        liveVehicle(link = VehicleLink.CONNECTED, armed = true)
        repository.play(recording)

        val result = repository.state.value

        (result to result.origin) shouldBeEqualTo
            (ReplayState.Refused(ReplayRefusal.VEHICLE_ARMED) to DataOrigin.LIVE)
    }

    @Test
    fun `GIVEN a file outside the recordings folder WHEN playing it THEN fails as not a recording, never opened`() {
        val outside = File(temp, "elsewhere.tlog").apply { writeBytes(ByteArray(0)) }

        val result = repository.play(outside)

        result shouldBeEqualTo ReplayState.Failed(outside, ReplayFailure.NOT_A_RECORDING)
        replayed shouldBeEqualTo emptyList()
        verifyNever {
            session.replay(any(), any())
        }
    }

    @Test
    fun `GIVEN a path that climbs out of the recordings folder WHEN playing it THEN fails as not a recording`() {
        File(temp, "elsewhere.tlog").writeBytes(ByteArray(0))
        val climbing = File(recordings, "../elsewhere.tlog")

        val result = repository.play(climbing)

        result shouldBeEqualTo ReplayState.Failed(climbing, ReplayFailure.NOT_A_RECORDING)
    }

    @Test
    fun `GIVEN a link in the recordings folder to a file outside WHEN playing it THEN fails as not a recording`() {
        val outside = File(temp, "elsewhere.tlog").apply { writeBytes(ByteArray(0)) }
        val link = File(recordings, "link.tlog")
        assumeTrue(createdLink(link, outside), "This file system does not let the test create a symbolic link")

        val result = repository.play(link)

        result shouldBeEqualTo ReplayState.Failed(link, ReplayFailure.NOT_A_RECORDING)
    }

    @Test
    fun `GIVEN the recordings folder itself WHEN playing it THEN fails as not a recording`() {
        val folder = recordings

        val result = repository.play(folder)

        result shouldBeEqualTo ReplayState.Failed(folder, ReplayFailure.NOT_A_RECORDING)
    }

    @Test
    fun `GIVEN a replay playing WHEN asked to play a file that is not a recording THEN the replay goes on`() {
        val file = recording
        repository.play(file)

        repository.play(File(temp, "elsewhere.tlog"))
        val result = repository.state.value

        result shouldBeEqualTo ReplayState.Playing(file)
    }

    @Test
    fun `GIVEN a replay playing WHEN paused THEN its time stands still`() {
        repository.play(recording)
        device += PLAYED

        repository.pause()
        device += LONG_SILENT
        val result = repository.state.value to replayClock.captured.position

        result shouldBeEqualTo (ReplayState.Paused(recording) to PLAYED)
    }

    @Test
    fun `GIVEN a paused replay WHEN resumed THEN plays and its time goes on`() {
        repository.play(recording)
        repository.pause()

        repository.resume()
        device += PLAYED
        val result = repository.state.value to replayClock.captured.position

        result shouldBeEqualTo (ReplayState.Playing(recording) to PLAYED)
    }

    @Test
    fun `GIVEN a replay playing WHEN the session plays it to its end THEN it has ended`() = testScope.runTest {
        val file = recording
        repository.play(file)
        runCurrent()

        status.value = LinkStatus.ReplayEnded(datagrams = 3)
        runCurrent()
        val result = repository.state.value

        result shouldBeEqualTo ReplayState.Ended(file)
    }

    @Test
    fun `GIVEN a replay playing WHEN the session cannot read it THEN it failed as unreadable, still a replay`() =
        testScope.runTest {
            val file = recording
            repository.play(file)
            runCurrent()

            status.value = LinkStatus.ReplayFailed(IOException("unreadable"))
            runCurrent()
            val result = repository.state.value

            (result to result.origin) shouldBeEqualTo
                (ReplayState.Failed(file, ReplayFailure.UNREADABLE) to DataOrigin.REPLAY)
        }

    @Test
    fun `GIVEN a replay WHEN its source skips a gap THEN the time skipped is shown`() {
        repository.play(recording)

        gapReports.single()(SKIPPED)
        val result = repository.skippedGap.value

        result shouldBeEqualTo SKIPPED
    }

    @Test
    fun `GIVEN a replay after another WHEN the first one's source reports a gap late THEN it is ignored`() {
        val file = recording
        repository.play(file)
        repository.play(file)

        gapReports.first()(SKIPPED)
        val result = repository.skippedGap.value

        result shouldBeEqualTo null
    }

    @Test
    fun `GIVEN a gap being skipped WHEN the replay stops THEN no gap is shown`() {
        repository.play(recording)
        gapReports.single()(SKIPPED)

        repository.stop()
        val result = repository.skippedGap.value

        result shouldBeEqualTo null
    }

    @Test
    fun `GIVEN a recording WHEN playing it THEN retention keeps it before the live recording closes`() {
        val file = recording

        repository.play(file)

        events shouldBeEqualTo listOf("replay keeping ${file.name}")
    }

    @Test
    fun `GIVEN a replay WHEN stopped THEN the live link is back and retention no longer keeps the file`() {
        val file = recording
        repository.play(file)

        repository.stop()
        val result = repository.state.value

        (result to folder.replayed) shouldBeEqualTo (ReplayState.Off to null)
        events shouldBeEqualTo listOf("replay keeping ${file.name}", "live keeping ${file.name}")
    }

    @Test
    fun `GIVEN the recorder's marker in the recordings folder WHEN playing it THEN fails as not a recording`() {
        val marker = File(recordings, ".last-closed").apply { writeText(recording.name) }

        val result = repository.play(marker)

        result shouldBeEqualTo ReplayState.Failed(marker, ReplayFailure.NOT_A_RECORDING)
    }

    @Test
    fun `GIVEN a path that leaves the folder and comes back WHEN playing it THEN plays the file at its real path`() {
        val file = recording
        val roundabout = File(recordings, "../tlogs/${file.name}")

        repository.play(roundabout)

        replayed shouldBeEqualTo listOf(file.toPath().toRealPath().toFile())
    }

    @Test
    fun `GIVEN no replay WHEN stopped THEN the live link is left alone`() {
        val before = repository.state.value

        repository.stop()

        before shouldBeEqualTo ReplayState.Off
        verifyNever {
            session.live()
        }
    }

    private fun liveVehicle(link: VehicleLink, armed: Boolean) {
        vehicles.value = mapOf(
            SystemId(1) to Vehicle(
                state = VehicleState(SystemId(1), VehicleType.COPTER, FlightMode.Named("Loiter"), armed = armed),
                link = link,
                lastHeartbeat = clock.markNow(),
                origin = DataOrigin.LIVE,
            ),
        )
    }
}

private fun emptySource(): DatagramSource = object : DatagramSource {
    override fun datagrams() = emptyFlow<com.owindev.gcs.core.transport.Datagram>()
}

/** Whether [link] could be made a symbolic link to [target]: Windows allows it only with Developer Mode or admin. */
private fun createdLink(link: File, target: File): Boolean = try {
    Files.createSymbolicLink(link.toPath(), target.toPath())
    true
} catch (@Suppress("SwallowedException") notAllowed: FileSystemException) {
    false
} catch (@Suppress("SwallowedException") notSupported: UnsupportedOperationException) {
    false
}

private val PLAYED = 2.seconds
private val LONG_SILENT = 10.seconds
private val SKIPPED = 8.minutes
