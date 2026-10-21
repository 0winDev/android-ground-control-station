package com.owindev.gcs.feature.hud

import com.owindev.gcs.core.domain.vehicle.FlightMode
import kotlin.time.Duration.Companion.seconds
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class LicencesAvailableTest {

    @Test
    fun `GIVEN no vehicle WHEN asking for the licences THEN they can be opened`() {
        val states = listOf(HudUiState.Starting, HudUiState.WaitingForVehicle(port = PORT, datagramCount = 0))

        val result = states.map { it.licencesAvailable }

        result shouldBeEqualTo listOf(true, true)
    }

    @Test
    fun `GIVEN a disarmed vehicle WHEN asking for the licences THEN they can be opened`() {
        val state = connected(armed = false)

        val result = state.licencesAvailable

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN an armed vehicle WHEN asking for the licences THEN they cannot be opened`() {
        val state = connected(armed = true)

        val result = state.licencesAvailable

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a vehicle lost while armed WHEN asking for the licences THEN they cannot be opened`() {
        val state = lost(armed = true)

        val result = state.licencesAvailable

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN a vehicle lost while disarmed WHEN asking for the licences THEN they can be opened`() {
        val state = lost(armed = false)

        val result = state.licencesAvailable

        result shouldBeEqualTo true
    }
}

private const val PORT = 14550

private fun status(armed: Boolean) = VehicleStatus(systemId = 1, flightMode = FlightMode.Named("Guided"), armed = armed)

private fun connected(armed: Boolean) =
    HudUiState.Connected(vehicle = status(armed), port = PORT, datagramCount = 0, otherVehicles = emptyList())

private fun lost(armed: Boolean) =
    HudUiState.LinkLost(lastKnown = status(armed), sinceLastHeartbeat = 4.seconds, otherVehicles = emptyList())
