package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.RouteOffer
import com.owindev.gcs.data.vehicle.link.DefaultUplinkRepository
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.TestTimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("REQ-051")
class ActiveRouteTest {

    private val clock = TestTimeSource()
    private val activeVehicle = ActiveVehicle()
    private val activeLinkChanges = ActiveLinkChanges()
    private val uplink = DefaultUplinkRepository(clock)

    private lateinit var route: ActiveRoute

    @BeforeEach
    fun setUp() {
        route = ActiveRoute(LinkLossRule(), activeVehicle, activeLinkChanges, uplink)
    }

    @Test
    fun `GIVEN a changed route WHEN accepting it THEN commands are still held when the key moves`() = runTest {
        activeVehicle.pinFirst(SYSTEM_1, FIRST_SESSION)
        route.heard(SYSTEM_1, DataOrigin.LIVE, SYSTEM_1_ADDRESS, clock.markNow())
        clock += LinkLossRule.DEFAULT_TIMEOUT
        route.heard(SYSTEM_1, DataOrigin.LIVE, MOVED_ADDRESS, clock.markNow())
        val key = CommandKey(SYSTEM_1, activeLinkChanges.connection)
        val offer = RouteOffer(key, from = SYSTEM_1_ADDRESS.toRoute(), to = MOVED_ADDRESS.toRoute())
        val seenWhenKeyMoved = mutableListOf<RouteCheck>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            activeLinkChanges.connections.drop(1).collect { seenWhenKeyMoved += route.check() }
        }

        route.accept(offer, current = key, lastSender = MOVED_ADDRESS)

        seenWhenKeyMoved shouldBeEqualTo listOf(RouteCheck.Held)
    }
}

private val MOVED_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_580)
