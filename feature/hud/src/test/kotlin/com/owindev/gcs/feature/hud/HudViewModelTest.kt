package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.link.LinkRepository
import com.owindev.gcs.core.domain.link.LinkTraffic
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

@Tag("REQ-005")
class HudViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val traffic = MutableStateFlow<LinkTraffic>(LinkTraffic.Receiving(port = PORT, datagrams = 0))
    private val linkRepository: LinkRepository = mockk()

    private lateinit var viewModel: HudViewModel

    @BeforeEach
    fun setUp() {
        every { linkRepository.traffic } returns traffic
        viewModel = HudViewModel(linkRepository)
    }

    @Test
    fun `GIVEN datagrams received on the link WHEN observing the state THEN shows the count and the port`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Receiving(port = PORT, datagrams = DATAGRAMS)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.Receiving(port = PORT, datagramCount = DATAGRAMS)
        }
    }

    @Test
    fun `GIVEN the port is in use WHEN observing the state THEN shows PortUnavailable`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Unavailable(port = PORT)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.PortUnavailable(port = PORT)
        }
    }

    @Test
    fun `GIVEN the link failed WHEN observing the state THEN shows LinkError`() = runTest {
        turbineScope {
            traffic.value = LinkTraffic.Failed

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo HudUiState.LinkError
        }
    }

    private companion object {
        const val PORT = 14550
        const val DATAGRAMS = 42L
    }
}
