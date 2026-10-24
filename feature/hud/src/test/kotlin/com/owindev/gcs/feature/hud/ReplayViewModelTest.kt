package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.replay.ReplayFailure
import com.owindev.gcs.core.domain.replay.ReplayRefusal
import com.owindev.gcs.core.domain.replay.ReplayRepository
import com.owindev.gcs.core.domain.replay.ReplayState
import com.owindev.gcs.core.testing.MainDispatcherExtension
import com.owindev.gcs.core.testing.verifyOnce
import io.mockk.Runs as runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@Tag("REQ-037")
class ReplayViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val state = MutableStateFlow<ReplayState>(ReplayState.Off)
    private val skippedGap = MutableStateFlow<Duration?>(null)
    private val replay: ReplayRepository = mockk()

    private lateinit var viewModel: ReplayViewModel

    @BeforeEach
    fun setUp() {
        every { replay.state } returns state
        every { replay.skippedGap } returns skippedGap
        viewModel = ReplayViewModel(replay)
    }

    @Test
    fun `GIVEN the live link WHEN observing the banner THEN there is none`() = runTest {
        turbineScope {
            state.value = ReplayState.Off

            val result = viewModel.banner.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN a replay refused WHEN observing the banner THEN there is none, the link is still live`() = runTest {
        turbineScope {
            state.value = ReplayState.Refused(ReplayRefusal.VEHICLE_CONNECTED)

            val result = viewModel.banner.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN a recording playing WHEN observing the banner THEN names it as playing`() = runTest {
        turbineScope {
            state.value = ReplayState.Playing(RECORDING)

            val result = viewModel.banner.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo ReplayBanner(NAME, ReplayStatus.PLAYING)
        }
    }

    @Test
    fun `GIVEN a recording playing WHEN it skips a gap THEN the banner says the time skipped`() = runTest {
        turbineScope {
            state.value = ReplayState.Playing(RECORDING)

            skippedGap.value = SKIPPED
            val result = viewModel.banner.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo ReplayBanner(NAME, ReplayStatus.PLAYING, skipped = SKIPPED)
        }
    }

    @Test
    fun `GIVEN a gap being skipped WHEN paused THEN the banner keeps the time skipped`() = runTest {
        turbineScope {
            skippedGap.value = SKIPPED

            state.value = ReplayState.Paused(RECORDING)
            val result = viewModel.banner.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo ReplayBanner(NAME, ReplayStatus.PAUSED, skipped = SKIPPED)
        }
    }

    @Test
    fun `GIVEN a recording that ended WHEN observing the banner THEN it stays, ended, until stopped`() = runTest {
        turbineScope {
            state.value = ReplayState.Ended(RECORDING)

            val result = viewModel.banner.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo ReplayBanner(NAME, ReplayStatus.ENDED)
        }
    }

    @Test
    fun `GIVEN a recording that cannot be read WHEN observing the banner THEN it stays, unreadable`() = runTest {
        turbineScope {
            state.value = ReplayState.Failed(RECORDING, ReplayFailure.UNREADABLE)

            val result = viewModel.banner.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo ReplayBanner(NAME, ReplayStatus.UNREADABLE)
        }
    }

    @Test
    fun `GIVEN a file that is not a recording WHEN observing the banner THEN there is none, the link is live`() =
        runTest {
            turbineScope {
                state.value = ReplayState.Failed(RECORDING, ReplayFailure.NOT_A_RECORDING)

                val result = viewModel.banner.testIn(backgroundScope)

                result.expectMostRecentItem() shouldBeEqualTo null
            }
        }

    @Test
    fun `GIVEN a replay already playing WHEN the screen opens THEN its first banner is already the replay's`() {
        state.value = ReplayState.Playing(RECORDING)

        val result = ReplayViewModel(replay).banner.value

        result shouldBeEqualTo ReplayBanner(NAME, ReplayStatus.PLAYING)
    }

    @Test
    fun `GIVEN a replay WHEN the operator pauses, resumes and stops it THEN each goes to the replay`() {
        every { replay.pause() } just runs
        every { replay.resume() } just runs
        every { replay.stop() } just runs

        viewModel.onPause()
        viewModel.onResume()
        viewModel.onStop()

        verifyOnce {
            replay.pause()
            replay.resume()
            replay.stop()
        }
    }

    private companion object {
        const val NAME = "2026-10-20_14-30-05.tlog"
        val RECORDING = File(NAME)
        val SKIPPED = 8.minutes
    }
}
