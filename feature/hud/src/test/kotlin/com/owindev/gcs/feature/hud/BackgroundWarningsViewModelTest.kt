package com.owindev.gcs.feature.hud

import app.cash.turbine.test
import com.owindev.gcs.core.domain.background.BackgroundLinkRepository
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.background.BackgroundLinkStatus
import com.owindev.gcs.core.domain.background.BackgroundLinkWarning
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.testing.MainDispatcherExtension
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The HUD's background link warnings (REQ-046). */
@Tag("REQ-046")
class BackgroundWarningsViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val status = MutableStateFlow(BackgroundLinkStatus())
    private val repository: BackgroundLinkRepository = mockk()

    private lateinit var viewModel: BackgroundWarningsViewModel

    @BeforeEach
    fun setUp() {
        every { repository.status } returns status
        viewModel = BackgroundWarningsViewModel(repository, BackgroundLinkRule(LinkLossRule(), RecordingRule()))
    }

    @Test
    fun `GIVEN the link running on a device's defaults WHEN shown THEN the HUD advises allowing background activity`() =
        runTest {
            status.value = BackgroundLinkStatus(running = true, batteryOptimized = true)

            viewModel.warnings.test {
                val result = expectMostRecentItem()

                result shouldBeEqualTo setOf(BackgroundLinkWarning.BATTERY_OPTIMIZED)
            }
        }

    @Test
    fun `GIVEN warnings shown WHEN the link stops THEN the HUD shows none`() = runTest {
        status.value = BackgroundLinkStatus(running = true, notificationHidden = true, backgroundRestricted = true)
        viewModel.warnings.test {
            skipItems(1)

            status.value = status.value.copy(running = false)
            val result = awaitItem()

            result shouldBeEqualTo emptySet()
        }
    }
}
