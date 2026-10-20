package com.owindev.gcs.data.vehicle.replay

import com.owindev.gcs.core.domain.link.DataOrigin
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
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
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
    private val activeVehicle = ActiveVehicle()
    private val replayed = mutableListOf<File>()
    private val recordings get() = File(temp, "tlogs").apply { mkdirs() }
    private val recording get() = File(recordings, "2026-10-16_17-09-58.tlog").apply { writeBytes(ByteArray(0)) }

    private lateinit var repository: DefaultReplayRepository

    @BeforeEach
    fun setUp() {
        every { session.status } returns status
        every { session.replay(any()) } just runs
        every { session.live() } just runs
        every { vehicleRepository.vehicles } returns vehicles
        repository = DefaultReplayRepository(
            session = session,
            vehicleRepository = vehicleRepository,
            activeVehicle = activeVehicle,
            clock = clock,
            rule = ReplayRule(LinkLossRule()),
            recordings = recordings,
            replaySource = { file, _ ->
                replayed += file
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
        replayed shouldBeEqualTo listOf(file)
        verifyOnce {
            session.replay(any())
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
            session.replay(any())
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
            session.replay(any())
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
            session.replay(any())
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
    fun `GIVEN a replay playing WHEN paused THEN the pipeline's time stands still`() {
        repository.play(recording)
        val mark = clock.markNow()

        repository.pause()
        device += LONG_SILENT
        val result = repository.state.value to mark.elapsedNow()

        result shouldBeEqualTo (ReplayState.Paused(recording) to 0.seconds)
    }

    @Test
    fun `GIVEN a paused replay WHEN resumed THEN plays and the pipeline's time goes on`() {
        repository.play(recording)
        repository.pause()
        val mark = clock.markNow()

        repository.resume()
        device += PLAYED
        val result = repository.state.value to mark.elapsedNow()

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
    fun `GIVEN a live and a replay vehicle pinned WHEN the replay stops THEN the live one is active again`() {
        activeVehicle.pinFirst(SystemId(1), DataOrigin.LIVE)
        repository.play(recording)
        activeVehicle.pinFirst(SystemId(2), DataOrigin.REPLAY)

        repository.stop()
        val result = repository.state.value to activeVehicle.systemId.value

        result shouldBeEqualTo (ReplayState.Off to SystemId(1))
        verifyOnce {
            session.live()
        }
    }

    @Test
    fun `GIVEN a replay stopped WHEN time passes THEN the pipeline follows the device again`() {
        repository.play(recording)
        repository.pause()
        repository.stop()
        val mark = clock.markNow()

        device += PLAYED
        val result = mark.elapsedNow()

        result shouldBeEqualTo PLAYED
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
        activeVehicle.pinFirst(SystemId(1), DataOrigin.LIVE)
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
