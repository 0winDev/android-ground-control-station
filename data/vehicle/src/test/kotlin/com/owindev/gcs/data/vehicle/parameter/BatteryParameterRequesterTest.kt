package com.owindev.gcs.data.vehicle.parameter

import android.util.Log
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamRequestRead
import com.owindev.gcs.core.mavlink.MavlinkMessage.ParamValue
import com.owindev.gcs.core.testing.verifyOnce
import com.owindev.gcs.core.transport.SendResult
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.GcsMessageSender
import com.owindev.gcs.data.vehicle.link.VehicleTarget
import com.owindev.gcs.data.vehicle.message.MESSAGE_SYSTEM_1
import com.owindev.gcs.data.vehicle.message.MESSAGE_SYSTEM_2
import com.owindev.gcs.data.vehicle.message.received
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The requester in virtual time, with a mocked sender and a real [ParameterStore]: the "vehicle" answers each
 * PARAM_REQUEST_READ 50 ms later with the PARAM_VALUE ArduCopter 4.7.2 sent for it, recorded into the store as the
 * vehicle repository's listener does.
 */
@Tag("REQ-028")
class BatteryParameterRequesterTest {

    private val sender: GcsMessageSender = mockk()
    private val activeVehicle = ActiveVehicle()
    private val store = ParameterStore(activeVehicle)
    private val sent = mutableListOf<String>()
    private val testScope = TestScope()

    private lateinit var requester: BatteryParameterRequester

    @BeforeEach
    fun setUp() {
        requester = BatteryParameterRequester(sender, store)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `GIVEN every request answered WHEN connected THEN each parameter is asked for once, MONITOR first`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { id -> SITL_ANSWERS[id] }

            runOn(flowOf(TARGET))
            val result = sent.toList()

            result shouldBeEqualTo ALL_IDS
            store.parameters.value.values shouldBeEqualTo SITL_VALUES
        }

    @Test
    fun `GIVEN no battery monitor WHEN connected THEN nothing but BATT_MONITOR is asked for`() = testScope.runTest {
        mockLog()
        vehicleAnswers { id -> if (id == MONITOR) paramValue(MONITOR, 0f, MAV_PARAM_TYPE_INT8) else SITL_ANSWERS[id] }

        runOn(flowOf(TARGET))
        val result = sent.toList()

        result shouldBeEqualTo listOf(MONITOR)
    }

    @Test
    fun `GIVEN BATT_MONITOR never answered WHEN connected THEN it is asked for three times and nothing else`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { id -> if (id == MONITOR) null else SITL_ANSWERS[id] }

            runOn(flowOf(TARGET))
            val result = sent.toList()

            result shouldBeEqualTo listOf(MONITOR, MONITOR, MONITOR)
        }

    @Test
    fun `GIVEN one parameter never answered WHEN connected THEN it is asked for three times and the rest once`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { id -> if (id == CRT_MAH) null else SITL_ANSWERS[id] }

            runOn(flowOf(TARGET))
            val result = sent.toList()

            result shouldBeEqualTo ALL_IDS.flatMap { id -> if (id == CRT_MAH) List(3) { id } else listOf(id) }
            store.parameters.value.values shouldBeEqualTo SITL_VALUES - BatteryParameter.CRT_MAH
        }

    @Test
    fun `GIVEN one answer lost WHEN connected THEN it is asked for again after one second`() = testScope.runTest {
        mockLog()
        val start = testScheduler.timeSource.markNow()
        val lowVoltSentAt = mutableListOf<Long>()
        vehicleAnswers { id ->
            if (id == LOW_VOLT) lowVoltSentAt += start.elapsedNow().inWholeMilliseconds
            if (id == LOW_VOLT && lowVoltSentAt.size == 1) null else SITL_ANSWERS[id]
        }

        runOn(flowOf(TARGET))
        val result = sent.toList()

        result shouldBeEqualTo listOf(MONITOR, LOW_VOLT, LOW_VOLT) + ALL_IDS.drop(2)
        lowVoltSentAt shouldBeEqualTo listOf(ANSWER_DELAY, ANSWER_DELAY + ANSWER_TIMEOUT_MILLIS)
    }

    @Test
    fun `GIVEN a value the vehicle sends before its turn WHEN connected THEN it is not asked for`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { id ->
                if (id == MONITOR) store.record(received(SITL_ANSWERS.getValue(LOW_VOLT), FIRST_SESSION))
                SITL_ANSWERS[id]
            }

            runOn(flowOf(TARGET))
            val result = sent.toList()

            result shouldBeEqualTo ALL_IDS - LOW_VOLT
        }

    @Test
    fun `GIVEN values kept from a connection before WHEN connected again THEN every parameter is asked for`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { id -> SITL_ANSWERS[id] }
            SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, FIRST_SESSION)) }

            runOn(flowOf(TARGET))
            val result = sent.toList()

            result shouldBeEqualTo ALL_IDS
        }

    @Test
    fun `GIVEN the vehicle not heard WHEN connected THEN nothing is asked for`() = testScope.runTest {
        mockLog()
        vehicleAnswers { id -> SITL_ANSWERS[id] }

        runOn(flowOf(TARGET), isHeard = { false })
        val result = sent.toList()

        result shouldBeEqualTo emptyList()
    }

    @Test
    fun `GIVEN BATT_MONITOR that cannot be sent WHEN connected THEN nothing else is asked for`() = testScope.runTest {
        mockLog()
        vehicleSendsFail(after = 0)

        runOn(flowOf(TARGET))
        val result = sent.toList()

        result shouldBeEqualTo listOf(MONITOR)
    }

    @Test
    fun `GIVEN a later request that cannot be sent WHEN connected THEN the round stops there`() = testScope.runTest {
        mockLog()
        vehicleSendsFail(after = 1)

        runOn(flowOf(TARGET))
        val result = sent.toList()

        result shouldBeEqualTo listOf(MONITOR, LOW_VOLT)
    }

    @Test
    fun `GIVEN values kept for another vehicle WHEN this one is connected THEN they are not taken as its answers`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { null }
            SITL_BATTERY_PARAMETERS.forEach { store.record(received(it, FIRST_SESSION)) }

            runOn(flowOf(OTHER_TARGET))
            val result = sent.toList()

            result shouldBeEqualTo listOf(MONITOR, MONITOR, MONITOR)
        }

    @Test
    fun `GIVEN the vehicle lost mid-round WHEN nothing answers THEN nothing more is asked for`() = testScope.runTest {
        mockLog()
        vehicleAnswers { null }

        runOn(
            flow {
                emit(TARGET)
                delay(ONE_AND_A_HALF_TIMEOUTS)
                emit(null)
            },
        )
        val result = sent.toList()

        result shouldBeEqualTo listOf(MONITOR, MONITOR)
    }

    @Test
    fun `GIVEN a reboot after a round WHEN connected again THEN every parameter is asked for again`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { id -> SITL_ANSWERS[id] }

            runOn(
                flow {
                    emit(TARGET)
                    delay(AFTER_A_ROUND)
                    store.rebooted(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
                    emit(TARGET)
                },
            )
            val result = sent.toList()

            result shouldBeEqualTo ALL_IDS + ALL_IDS
        }

    @Test
    fun `GIVEN a round read WHEN the vehicle announces a change THEN the values are logged before and after it`() =
        testScope.runTest {
            mockLog()
            vehicleAnswers { id -> SITL_ANSWERS[id] }
            runOn(flowOf(TARGET))

            store.record(received(paramValue(LOW_VOLT, CHANGED_VOLTS, MAV_PARAM_TYPE_REAL32), FIRST_SESSION))
            delay(ANSWER_DELAY)

            verifyOnce {
                Log.i(TAG, "Battery parameters: $SITL_LINE")
                Log.i(TAG, "Battery parameters: ${SITL_LINE.replace("BATT_LOW_VOLT=10.5", "BATT_LOW_VOLT=10.8")}")
            }
        }

    private suspend fun TestScope.runOn(targets: Flow<VehicleTarget?>, isHeard: () -> Boolean = { true }) {
        backgroundScope.launch { requester.run(targets, isHeard) }
        delay(LONG_AFTER_EVERY_ROUND)
    }

    /** The vehicle answers each request [ANSWER_DELAY] later with what [answerTo] gives for its name, if anything. */
    private fun TestScope.vehicleAnswers(answerTo: (String) -> ParamValue?) {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        coEvery { sender.send(any<ParamRequestRead>(), VEHICLE_ADDRESS) } coAnswers {
            val id = firstArg<ParamRequestRead>().paramId
            sent += id
            answerTo(id)?.let { answer ->
                backgroundScope.launch {
                    delay(ANSWER_DELAY)
                    store.record(received(answer, FIRST_SESSION))
                }
            }
            SendResult.Sent
        }
    }

    /** The vehicle answers the first [after] requests; every later one fails to leave, as on a closed link. */
    private fun TestScope.vehicleSendsFail(after: Int) {
        activeVehicle.pinFirst(SystemId(MESSAGE_SYSTEM_1), FIRST_SESSION)
        coEvery { sender.send(any<ParamRequestRead>(), VEHICLE_ADDRESS) } coAnswers {
            val id = firstArg<ParamRequestRead>().paramId
            sent += id
            if (sent.size > after) {
                SendResult.NotOpen
            } else {
                backgroundScope.launch {
                    delay(ANSWER_DELAY)
                    store.record(received(SITL_ANSWERS.getValue(id), FIRST_SESSION))
                }
                SendResult.Sent
            }
        }
    }

    private fun mockLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
    }

    private companion object {
        const val TAG = "BatteryParameters"
        const val MONITOR = "BATT_MONITOR"
        const val LOW_VOLT = "BATT_LOW_VOLT"
        const val CRT_MAH = "BATT_CRT_MAH"
        const val CHANGED_VOLTS = 10.8f
        const val ANSWER_DELAY = 50L
        const val ANSWER_TIMEOUT_MILLIS = 1_000L

        // The first request times out at 1 s, so the second one is in flight when the vehicle is lost.
        const val ONE_AND_A_HALF_TIMEOUTS = 1_500L

        // Eight answers of 50 ms each take 400 ms.
        const val AFTER_A_ROUND = 1_000L

        // Past the longest round here, a reboot's second one after eight lost requests of 1 s each.
        const val LONG_AFTER_EVERY_ROUND = 60_000L

        const val SITL_LINE = "BATT_MONITOR=4.0 BATT_LOW_VOLT=10.5 BATT_CRT_VOLT=0.0 BATT_LOW_MAH=0.0 " +
            "BATT_CRT_MAH=0.0 BATT_CAPACITY=3300.0 BATT_FS_LOW_ACT=0.0 BATT_FS_CRT_ACT=0.0"

        val VEHICLE_ADDRESS = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
        val TARGET = VehicleTarget(SystemId(MESSAGE_SYSTEM_1), VEHICLE_ADDRESS)
        val OTHER_TARGET = VehicleTarget(SystemId(MESSAGE_SYSTEM_2), VEHICLE_ADDRESS)
        val SITL_ANSWERS = SITL_BATTERY_PARAMETERS.associateBy(ParamValue::paramId)
        val ALL_IDS = SITL_BATTERY_PARAMETERS.map(ParamValue::paramId)
        val SITL_VALUES = BatteryParameter.entries.associateWith { SITL_ANSWERS.getValue(it.id).value }
    }
}
