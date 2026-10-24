package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.vehicle.FlightMode
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class LinkChipStateTest {

    @Test
    @Tag("REQ-037")
    fun `GIVEN a recording playing with its vehicle heard WHEN choosing the link chip THEN never says CONNECTED`() {
        val state = connected(port = null)

        val result = linkChipStateOf(state)

        result shouldBeEqualTo LinkChipState.REPLAY
    }

    @Test
    @Tag("REQ-037")
    fun `GIVEN a recording playing before its vehicle is heard WHEN choosing the link chip THEN says REPLAY`() {
        val state = HudUiState.WaitingForVehicle(port = null, datagramCount = 0)

        val result = linkChipStateOf(state)

        result shouldBeEqualTo LinkChipState.REPLAY
    }

    @Test
    fun `GIVEN a live vehicle heard WHEN choosing the link chip THEN says CONNECTED`() {
        val state = connected(port = PORT)

        val result = linkChipStateOf(state)

        result shouldBeEqualTo LinkChipState.CONNECTED
    }

    @Test
    fun `GIVEN a live link waiting for a vehicle WHEN choosing the link chip THEN says WAITING`() {
        val state = HudUiState.WaitingForVehicle(port = PORT, datagramCount = 0)

        val result = linkChipStateOf(state)

        result shouldBeEqualTo LinkChipState.WAITING
    }

    @Test
    fun `GIVEN a recording that cannot be read WHEN choosing the link chip THEN says it failed`() {
        val state = HudUiState.ReplayUnreadable

        val result = linkChipStateOf(state)

        result shouldBeEqualTo LinkChipState.FAILED
    }

    @Test
    fun `GIVEN a recorded link loss WHEN choosing the link chip THEN says LOST`() {
        val state = HudUiState.LinkLost(
            lastKnown = VEHICLE,
            sinceLastHeartbeat = 7.seconds,
            otherVehicles = emptyList(),
            recorded = true,
        )

        val result = linkChipStateOf(state)

        result shouldBeEqualTo LinkChipState.LOST
    }
}

private fun connected(port: Int?) =
    HudUiState.Connected(vehicle = VEHICLE, port = port, datagramCount = 1, otherVehicles = emptyList())

private const val PORT = 14550
private val VEHICLE = VehicleStatus(systemId = 1, flightMode = FlightMode.Named("Guided"), armed = true)
