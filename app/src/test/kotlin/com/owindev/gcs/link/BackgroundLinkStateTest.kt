package com.owindev.gcs.link

import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** When reading the device again asks for the notification to be posted again (REQ-046). */
@Tag("REQ-046")
class BackgroundLinkStateTest {

    private lateinit var state: BackgroundLinkState

    @BeforeEach
    fun setUp() {
        state = BackgroundLinkState()
    }

    @Test
    fun `GIVEN the service running with its notification hidden WHEN notifications are allowed THEN post it again`() {
        state.setRunning(true)
        read(notificationHidden = true)

        val result = read(notificationHidden = false)

        result shouldBeEqualTo true
    }

    @Test
    fun `GIVEN no service and the notification hidden WHEN notifications are allowed THEN nothing is posted`() {
        read(notificationHidden = true)

        val result = read(notificationHidden = false)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN the service running with its notification shown WHEN the device is read again THEN nothing is posted`() {
        state.setRunning(true)
        read(notificationHidden = false)

        val result = read(notificationHidden = false)

        result shouldBeEqualTo false
    }

    @Test
    fun `GIVEN the service running with its notification hidden WHEN still hidden THEN nothing is posted`() {
        state.setRunning(true)
        read(notificationHidden = true)

        val result = read(notificationHidden = true)

        result shouldBeEqualTo false
    }

    private fun read(notificationHidden: Boolean): Boolean =
        state.refresh(notificationHidden, batteryOptimized = false, backgroundRestricted = false)
}
