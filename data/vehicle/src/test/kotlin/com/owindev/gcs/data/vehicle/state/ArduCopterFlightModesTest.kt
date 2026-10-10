package com.owindev.gcs.data.vehicle.state

import com.owindev.gcs.core.domain.vehicle.FlightMode
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Expected names come from the ArduCopter `FLTMODE1` documentation,
 * https://ardupilot.org/copter/docs/parameters.html#fltmode1-flight-mode-1, never from the table under test.
 */
@Tag("REQ-018")
class ArduCopterFlightModesTest {

    private lateinit var flightModes: ArduCopterFlightModes

    @BeforeEach
    fun setUp() {
        flightModes = ArduCopterFlightModes
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(
        "0, Stabilize",
        "1, Acro",
        "2, AltHold",
        "3, Auto",
        "4, Guided",
        "5, Loiter",
        "6, RTL",
        "7, Circle",
        "9, Land",
        "11, Drift",
        "13, Sport",
        "14, Flip",
        "15, AutoTune",
        "16, PosHold",
        "17, Brake",
        "18, Throw",
        "19, Avoid_ADSB",
        "20, Guided_NoGPS",
        "21, Smart_RTL",
        "22, FlowHold",
        "23, Follow",
        "24, ZigZag",
        "25, SystemID",
        "26, Heli_Autorotate",
        "27, Auto RTL",
        "28, Turtle",
    )
    fun `GIVEN a documented custom_mode WHEN mapping it THEN has the documented name`(customMode: Long, name: String) {
        val result = flightModes.flightModeOf(customMode)

        result shouldBeEqualTo FlightMode.Named(name)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(longs = [8, 10, 12, 29, 4_294_967_295])
    fun `GIVEN an undocumented custom_mode WHEN mapping it THEN is Unknown with that number`(customMode: Long) {
        val result = flightModes.flightModeOf(customMode)

        result shouldBeEqualTo FlightMode.Unknown(customMode)
    }
}
