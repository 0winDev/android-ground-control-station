package com.owindev.gcs.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.SaverScope
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The back stack (ADR 0007): the flight screen always at its bottom, the recordings at most once over it. */
class DestinationTest {

    @Test
    fun `GIVEN the recordings open WHEN going back to flight THEN only the flight screen is left`() {
        val backStack = mutableListOf(Destination.FLIGHT, Destination.RECORDINGS)

        backStack.backToFlight()

        backStack shouldBeEqualTo listOf(Destination.FLIGHT)
    }

    @Test
    fun `GIVEN an empty back stack WHEN going back to flight THEN the flight screen is there`() {
        val backStack = mutableListOf<Destination>()

        backStack.backToFlight()

        backStack shouldBeEqualTo listOf(Destination.FLIGHT)
    }

    @Test
    fun `GIVEN the recordings open WHEN opening them again THEN they are on the stack once`() {
        val backStack = mutableListOf(Destination.FLIGHT, Destination.RECORDINGS)

        backStack.openRecordings(mayLeaveFlight = true)

        backStack shouldBeEqualTo listOf(Destination.FLIGHT, Destination.RECORDINGS)
    }

    @Tag("REQ-017")
    @Tag("REQ-037")
    @Test
    fun `GIVEN the flight screen WHEN a late tap opens the recordings after leaving became forbidden THEN it stays`() {
        val backStack = mutableListOf(Destination.FLIGHT)

        backStack.openRecordings(mayLeaveFlight = false)

        backStack shouldBeEqualTo listOf(Destination.FLIGHT)
    }

    @Test
    fun `GIVEN a back stack WHEN saved and restored THEN it is the same`() {
        val backStack = mutableStateListOf(Destination.FLIGHT, Destination.RECORDINGS)
        val scope = SaverScope { true }

        val result = with(BackStackSaver) { restore(scope.save(backStack)!!) }

        result?.toList() shouldBeEqualTo listOf(Destination.FLIGHT, Destination.RECORDINGS)
    }
}
