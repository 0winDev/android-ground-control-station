package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.data.vehicle.link.VehicleTarget
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-023")
class ActiveLinkChangesTest {

    private val target = VehicleTarget(SystemId(1), InetSocketAddress(InetAddress.getLoopbackAddress(), PORT))

    private lateinit var changes: ActiveLinkChanges

    @BeforeEach
    fun setUp() {
        changes = ActiveLinkChanges()
    }

    @Tag("REQ-048")
    @Test
    fun `GIVEN a CONNECTED, a LOST and a reboot announced WHEN reading the connection THEN it moved on each`() {
        val before = changes.connection

        listOf(target, null, target).forEach(changes::announce)

        changes.connection shouldBeEqualTo before + 3
    }

    @Tag("REQ-048")
    @Test
    fun `GIVEN a connection WHEN the session restarts THEN it moves on`() {
        changes.announce(target)
        val before = changes.connection

        changes.restart()

        (changes.connection > before) shouldBeEqualTo true
    }

    @Test
    fun `GIVEN changes announced to two consumers WHEN consuming THEN each gets every change in order`() = runTest {
        val first = changes.newConsumer()
        val second = changes.newConsumer()
        changes.announce(target)
        changes.announce(null)
        changes.announce(target)
        changes.close()

        val result = listOf(first.toList(), second.toList())

        result shouldBeEqualTo listOf(
            listOf(target, null, target, null),
            listOf(target, null, target, null),
        )
    }

    @Test
    fun `GIVEN a restart after queued changes WHEN consuming THEN each only gets that there is no vehicle`() = runTest {
        val first = changes.newConsumer()
        val second = changes.newConsumer()
        changes.announce(target)
        changes.announce(target)
        changes.restart()
        changes.close()

        val result = listOf(first.toList(), second.toList())

        result shouldBeEqualTo listOf(listOf(null, null), listOf(null, null))
    }

    private companion object {
        const val PORT = 14_550
    }
}
