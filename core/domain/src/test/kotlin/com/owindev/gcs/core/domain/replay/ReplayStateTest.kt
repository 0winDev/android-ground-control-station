package com.owindev.gcs.core.domain.replay

import com.owindev.gcs.core.domain.link.DataOrigin
import java.io.File
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("REQ-037")
class ReplayStateTest {

    @Test
    fun `GIVEN every state with the live link WHEN reading its origin THEN is live`() {
        val states = listOf(
            ReplayState.Off,
            ReplayState.Refused(ReplayRefusal.VEHICLE_ARMED),
            ReplayState.Failed(RECORDING, ReplayFailure.NOT_A_RECORDING),
        )

        val result = states.map(ReplayState::origin)

        result shouldBeEqualTo List(states.size) { DataOrigin.LIVE }
    }

    @Test
    fun `GIVEN every state with a replay on WHEN reading its origin THEN is the replay`() {
        val states = listOf(
            ReplayState.Playing(RECORDING),
            ReplayState.Paused(RECORDING),
            ReplayState.Ended(RECORDING),
            ReplayState.Failed(RECORDING, ReplayFailure.UNREADABLE),
        )

        val result = states.map(ReplayState::origin)

        result shouldBeEqualTo List(states.size) { DataOrigin.REPLAY }
    }
}

private val RECORDING = File("2026-10-16_17-09-58.tlog")
