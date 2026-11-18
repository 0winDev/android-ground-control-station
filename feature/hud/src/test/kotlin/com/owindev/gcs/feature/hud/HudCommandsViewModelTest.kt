package com.owindev.gcs.feature.hud

import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightCheck
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightKind
import com.owindev.gcs.core.domain.preflight.PreflightReason
import com.owindev.gcs.core.domain.preflight.PreflightRepository
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GroundPositionRule
import com.owindev.gcs.core.domain.telemetry.LandedRule
import com.owindev.gcs.core.domain.telemetry.LandedState
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.TelemetryRepository
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleRepository
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.domain.vehicle.command.CommandKey
import com.owindev.gcs.core.domain.vehicle.command.CommandRepository
import com.owindev.gcs.core.domain.vehicle.command.CommandResult
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.core.domain.vehicle.command.PendingCommand
import com.owindev.gcs.core.domain.vehicle.command.PendingState
import com.owindev.gcs.core.domain.vehicle.command.Rejection
import com.owindev.gcs.core.domain.vehicle.command.TakeOffAltitudeRule
import com.owindev.gcs.core.domain.vehicle.command.VehicleCommand
import com.owindev.gcs.core.testing.MainDispatcherExtension
import com.owindev.gcs.core.testing.coVerifyNever
import com.owindev.gcs.core.testing.coVerifyOnce
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.stream.Stream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Ages come from time marks on the test scheduler's clock, so the ViewModel's rechecks and the ages move together. */
@Tag("REQ-050")
class HudCommandsViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val commandKey = MutableStateFlow<CommandKey?>(KEY)
    private val pending = MutableStateFlow<PendingCommand?>(null)
    private val vehicles = MutableStateFlow<Map<SystemId, Vehicle>>(emptyMap())
    private val activeTelemetry = MutableStateFlow<Telemetry?>(null)
    private var check = GREEN_CHECK
    private val commandRepository: CommandRepository = mockk()
    private val vehicleRepository: VehicleRepository = mockk()
    private val telemetryRepository: TelemetryRepository = mockk()
    private val preflightRepository: PreflightRepository = mockk()

    private lateinit var viewModel: HudCommandsViewModel

    @BeforeEach
    fun setUp() {
        every { commandRepository.commandKey } returns commandKey
        every { commandRepository.pending } returns pending
        every { vehicleRepository.vehicles } returns vehicles
        every { telemetryRepository.activeTelemetry } returns activeTelemetry
        every { preflightRepository.checkFor(SYSTEM) } answers { check }
        viewModel = HudCommandsViewModel(
            commands = commandRepository,
            vehicles = vehicleRepository,
            telemetry = telemetryRepository,
            preflight = preflightRepository,
            altitudeRule = TakeOffAltitudeRule(),
            linkLoss = LinkLossRule(),
            landed = LandedRule(),
            groundPosition = GroundPositionRule(),
        )
    }

    @Test
    fun `GIVEN disarmed on the ground and all green WHEN observing THEN arm is offered, RTL and land not yet`() =
        runTest {
            turbineScope {
                onTheGround(armed = false)

                val result = viewModel.state.testIn(backgroundScope)

                result.expectMostRecentItem().bar shouldBeEqualTo CommandBar(
                    ground = OfferedCommand(VehicleCommand.ARM),
                    returnToLaunch = OfferedCommand(VehicleCommand.RETURN_TO_LAUNCH, CommandUnavailable.NotArmed),
                    land = OfferedCommand(VehicleCommand.LAND, CommandUnavailable.NotArmed),
                )
            }
        }

    @Tag("REQ-048")
    @ParameterizedTest
    @MethodSource("armRefusals")
    fun `GIVEN a disarmed vehicle WHEN a rule refuses arming THEN arm is disabled with that reason`(
        refusal: ArmRefusal,
    ) = runTest {
        turbineScope {
            onTheGround(armed = false, landed = refusal.landed)
            check = refusal.check

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().bar?.ground shouldBeEqualTo OfferedCommand(VehicleCommand.ARM, refusal.reason)
        }
    }

    @Tag("REQ-044")
    @Test
    fun `GIVEN an armed vehicle on the ground WHEN observing THEN take off, RTL and land are offered`() = runTest {
        turbineScope {
            onTheGround(armed = true)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().bar shouldBeEqualTo CommandBar(
                ground = OfferedCommand(VehicleCommand.TAKE_OFF),
                returnToLaunch = OfferedCommand(VehicleCommand.RETURN_TO_LAUNCH),
                land = OfferedCommand(VehicleCommand.LAND),
            )
        }
    }

    @Tag("REQ-044")
    @Test
    fun `GIVEN an armed vehicle 5 m above home WHEN observing THEN take off is only on the ground`() = runTest {
        turbineScope {
            onTheGround(armed = true, altitudeAboveHome = FLYING_ALTITUDE)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().bar?.ground shouldBeEqualTo
                OfferedCommand(VehicleCommand.TAKE_OFF, CommandUnavailable.NotOnGround)
        }
    }

    @Tag("REQ-044")
    @Test
    fun `GIVEN an armed vehicle whose landed state stops WHEN 0,5 s pass THEN take off is no longer offered`() =
        runTest {
            turbineScope {
                onTheGround(armed = true)
                val states = viewModel.state.testIn(backgroundScope)

                mainDispatcher.testDispatcher.scheduler.advanceTimeBy(LANDED_EXPIRED)
                val result = states.expectMostRecentItem()

                result.bar?.ground shouldBeEqualTo
                    OfferedCommand(VehicleCommand.TAKE_OFF, CommandUnavailable.NotOnGround)
            }
        }

    @Tag("REQ-048")
    @Test
    fun `GIVEN an armed vehicle with a red pre-flight item WHEN observing THEN RTL and land stay offered`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            check = RED_CHECK

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem().bar shouldBeEqualTo CommandBar(
                ground = OfferedCommand(VehicleCommand.TAKE_OFF, CommandUnavailable.PreflightRed(items = 1)),
                returnToLaunch = OfferedCommand(VehicleCommand.RETURN_TO_LAUNCH),
                land = OfferedCommand(VehicleCommand.LAND),
            )
        }
    }

    @Tag("REQ-037")
    @Test
    fun `GIVEN a replay with no command key WHEN observing THEN no command is shown`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            commandKey.value = null

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo CommandsUiState()
        }
    }

    @Tag("REQ-017")
    @Test
    fun `GIVEN the vehicle's link is lost WHEN observing THEN no command is shown`() = runTest {
        turbineScope {
            onTheGround(armed = true, link = VehicleLink.LOST)

            val result = viewModel.state.testIn(backgroundScope)

            result.expectMostRecentItem() shouldBeEqualTo CommandsUiState()
        }
    }

    @Test
    fun `GIVEN RTL is offered WHEN requested THEN its sheet asks for a slide on the current key`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCommandRequested(VehicleCommand.RETURN_TO_LAUNCH)
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo CommandSheet(
                command = VehicleCommand.RETURN_TO_LAUNCH,
                systemId = SYSTEM.value,
                altitudeMetres = null,
                key = ConfirmKey(KEY, altitudeMetres = null),
                unavailable = null,
            )
        }
    }

    @Test
    fun `GIVEN arm is not offered WHEN a tap drawn earlier requests it THEN no sheet opens`() = runTest {
        turbineScope {
            onTheGround(armed = false)
            check = RED_CHECK
            val states = viewModel.state.testIn(backgroundScope)

            viewModel.onCommandRequested(VehicleCommand.ARM)
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN an RTL sheet WHEN the slide confirms THEN RTL is sent once on its key and the sheet closes`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            coEvery { commandRepository.returnToLaunch(KEY) } returns CommandResult.Accepted
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.RETURN_TO_LAUNCH)

            viewModel.onCommandConfirmed(ConfirmKey(KEY, altitudeMetres = null))
            val result = states.expectMostRecentItem()

            coVerifyOnce {
                commandRepository.returnToLaunch(KEY)
            }
            result.sheet shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN an arm sheet WHEN a red item appears before the slide ends THEN the confirm sends nothing`() = runTest {
        onTheGround(armed = false)
        viewModel.onCommandRequested(VehicleCommand.ARM)
        check = RED_CHECK

        viewModel.onCommandConfirmed(ConfirmKey(KEY, altitudeMetres = null))

        coVerifyNever {
            commandRepository.arm(any(), any())
        }
    }

    @Test
    fun `GIVEN an arm sheet WHEN the slide confirms THEN arm is sent with nothing acknowledged`() = runTest {
        onTheGround(armed = false)
        coEvery { commandRepository.arm(KEY, PreflightAcknowledgements()) } returns CommandResult.Accepted
        viewModel.onCommandRequested(VehicleCommand.ARM)

        viewModel.onCommandConfirmed(ConfirmKey(KEY, altitudeMetres = null))

        coVerifyOnce {
            commandRepository.arm(KEY, PreflightAcknowledgements())
        }
    }

    @Tag("REQ-044")
    @Test
    fun `GIVEN a take off sheet WHEN the altitude changes THEN the slide's key carries the new altitude`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.TAKE_OFF)

            viewModel.onTakeOffAltitudeChanged(CHOSEN_ALTITUDE)
            val result = states.expectMostRecentItem().sheet?.key

            result shouldBeEqualTo ConfirmKey(KEY, CHOSEN_ALTITUDE)
        }
    }

    @Tag("REQ-044")
    @Test
    fun `GIVEN a take off sheet WHEN the altitude goes past the range THEN it stops at the range's end`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.TAKE_OFF)

            viewModel.onTakeOffAltitudeChanged(TOO_HIGH)
            val result = states.expectMostRecentItem().sheet?.altitudeMetres

            result shouldBeEqualTo TakeOffAltitudeRule.DEFAULT_MAX_METRES
        }
    }

    @Tag("REQ-044")
    @Test
    fun `GIVEN the altitude changed in a slide WHEN the old key confirms THEN nothing is sent, slide again`() =
        runTest {
            turbineScope {
                onTheGround(armed = true)
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onCommandRequested(VehicleCommand.TAKE_OFF)
                viewModel.onTakeOffAltitudeChanged(CHOSEN_ALTITUDE)

                viewModel.onCommandConfirmed(ConfirmKey(KEY, TakeOffAltitudeRule.DEFAULT_METRES))
                val result = states.expectMostRecentItem().sheet?.slideAgain

                coVerifyNever {
                    commandRepository.takeOff(any(), any())
                }
                result shouldBeEqualTo true
            }
        }

    @Tag("REQ-044")
    @Test
    fun `GIVEN a take off sheet at a chosen altitude WHEN the slide confirms THEN its key's altitude is sent`() =
        runTest {
            onTheGround(armed = true)
            coEvery { commandRepository.takeOff(KEY, CHOSEN_ALTITUDE) } returns CommandResult.Accepted
            viewModel.onCommandRequested(VehicleCommand.TAKE_OFF)
            viewModel.onTakeOffAltitudeChanged(CHOSEN_ALTITUDE)

            viewModel.onCommandConfirmed(ConfirmKey(KEY, CHOSEN_ALTITUDE))

            coVerifyOnce {
                commandRepository.takeOff(KEY, CHOSEN_ALTITUDE)
            }
        }

    @Test
    fun `GIVEN an RTL sheet WHEN the vehicle's connection changes THEN the sheet closes`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.RETURN_TO_LAUNCH)

            commandKey.value = NEXT_KEY
            val result = states.expectMostRecentItem().sheet

            result shouldBeEqualTo null
        }
    }

    @Test
    fun `GIVEN the service finds the key changed WHEN an RTL is confirmed THEN it asks to slide again, no failure`() =
        runTest {
            turbineScope {
                onTheGround(armed = true)
                coEvery { commandRepository.returnToLaunch(KEY) } answers {
                    commandKey.value = NEXT_KEY
                    CommandResult.NotSent(NotSentReason.KeyChanged)
                }
                val states = viewModel.state.testIn(backgroundScope)
                viewModel.onCommandRequested(VehicleCommand.RETURN_TO_LAUNCH)

                viewModel.onCommandConfirmed(ConfirmKey(KEY, altitudeMetres = null))
                val result = states.expectMostRecentItem()

                result.feedback shouldBeEqualTo null
                result.sheet shouldBeEqualTo CommandSheet(
                    command = VehicleCommand.RETURN_TO_LAUNCH,
                    systemId = SYSTEM.value,
                    altitudeMetres = null,
                    key = ConfirmKey(NEXT_KEY, altitudeMetres = null),
                    unavailable = null,
                    slideAgain = true,
                )
            }
        }

    @Tag("REQ-042")
    @Test
    fun `GIVEN an RTL waiting for its second attempt WHEN observing THEN it says attempt 2 of 3`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            coEvery { commandRepository.returnToLaunch(KEY) } coAnswers { CompletableDeferred<CommandResult>().await() }
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.RETURN_TO_LAUNCH)
            viewModel.onCommandConfirmed(ConfirmKey(KEY, altitudeMetres = null))

            pending.value = PendingCommand(SYSTEM, VehicleCommand.RETURN_TO_LAUNCH, PendingState.Waiting(attempt = 2))
            val result = states.expectMostRecentItem().feedback

            result shouldBeEqualTo CommandFeedback(
                VehicleCommand.RETURN_TO_LAUNCH,
                CommandStatus.Sending(attempt = 2, attempts = 3),
            )
        }
    }

    @Tag("REQ-042")
    @Tag("REQ-044")
    @ParameterizedTest
    @MethodSource("rtlResultsInWords", "takeOffResultsInWords")
    fun `GIVEN a command ends WHEN observing THEN its result is in words at its level`(case: ResultInWords) = runTest {
        turbineScope {
            onTheGround(armed = true)
            coEvery { commandRepository.returnToLaunch(KEY) } returns case.result
            coEvery { commandRepository.takeOff(KEY, TakeOffAltitudeRule.DEFAULT_METRES) } returns case.result
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(case.command)
            val altitude = TakeOffAltitudeRule.DEFAULT_METRES.takeIf { case.command == VehicleCommand.TAKE_OFF }

            viewModel.onCommandConfirmed(ConfirmKey(KEY, altitude))
            val result = states.expectMostRecentItem().feedback

            result shouldBeEqualTo CommandFeedback(case.command, CommandStatus.Ended(case.message))
        }
    }

    @Test
    fun `GIVEN an RTL ended WHEN dismissed THEN its result is hidden`() = runTest {
        turbineScope {
            onTheGround(armed = true)
            coEvery { commandRepository.returnToLaunch(KEY) } returns CommandResult.Accepted
            val states = viewModel.state.testIn(backgroundScope)
            viewModel.onCommandRequested(VehicleCommand.RETURN_TO_LAUNCH)
            viewModel.onCommandConfirmed(ConfirmKey(KEY, altitudeMetres = null))

            viewModel.onFeedbackDismissed()
            val result = states.expectMostRecentItem().feedback

            result shouldBeEqualTo null
        }
    }

    private fun TestScope.onTheGround(
        armed: Boolean,
        landed: LandedState = LandedState.ON_GROUND,
        altitudeAboveHome: Double = 0.0,
        link: VehicleLink = VehicleLink.CONNECTED,
    ) {
        val now = testScheduler.timeSource.markNow()
        vehicles.value = mapOf(
            SYSTEM to Vehicle(
                state = VehicleState(SYSTEM, VehicleType.COPTER, LOITER, armed),
                link = link,
                lastHeartbeat = now,
                origin = DataOrigin.LIVE,
            ),
        )
        activeTelemetry.value = Telemetry(
            origin = DataOrigin.LIVE,
            position = Timed(position(altitudeAboveHome), now),
            landed = Timed(landed, now),
        )
    }

    /** One way the rules refuse arming. */
    data class ArmRefusal(val landed: LandedState, val check: PreflightCheck, val reason: CommandUnavailable)

    /** A command's result and the words it shows. */
    data class ResultInWords(val command: VehicleCommand, val result: CommandResult, val message: CommandMessage)

    private companion object {
        val SYSTEM = SystemId(1)
        val KEY = CommandKey(SYSTEM, connection = 4)
        val NEXT_KEY = KEY.copy(connection = 5)
        val LOITER = FlightMode.Named("Loiter")
        const val FLYING_ALTITUDE = 5.0
        const val CHOSEN_ALTITUDE = 12f
        const val TOO_HIGH = 31f
        val LANDED_EXPIRED: Duration = 0.6.seconds
        val GREEN_CHECK = PreflightCheck(items = emptyList(), connection = 4)
        val RED_CHECK = PreflightCheck(
            items = listOf(PreflightItem(PreflightKind.GPS, PreflightColour.RED, PreflightReason.NoFix3d)),
            connection = 4,
        )
        val AMBER_CHECK = PreflightCheck(
            items = listOf(
                PreflightItem(PreflightKind.BATTERY_LIMITS, PreflightColour.AMBER, PreflightReason.LimitsNotRead),
            ),
            connection = 4,
        )

        fun position(altitudeAboveHome: Double) = GlobalPosition(
            latitude = 40.0,
            longitude = -3.0,
            altitudeMsl = 600.0 + altitudeAboveHome,
            altitudeAboveHome = altitudeAboveHome,
            velocityNorth = 0.0,
            velocityEast = 0.0,
            velocityDown = 0.0,
            heading = null,
        )

        @JvmStatic
        fun armRefusals(): Stream<Arguments> = Stream.of(
            Arguments.of(ArmRefusal(LandedState.IN_AIR, GREEN_CHECK, CommandUnavailable.NotOnGround)),
            Arguments.of(ArmRefusal(LandedState.ON_GROUND, RED_CHECK, CommandUnavailable.PreflightRed(items = 1))),
            Arguments.of(ArmRefusal(LandedState.ON_GROUND, AMBER_CHECK, CommandUnavailable.CautionsToAcknowledge)),
        )

        @JvmStatic
        fun rtlResultsInWords(): Stream<Arguments> {
            val rtl = TextArg(R.string.hud_command_rtl)
            return Stream.of(
                ResultInWords(
                    VehicleCommand.RETURN_TO_LAUNCH,
                    CommandResult.Accepted,
                    CommandMessage(R.string.hud_command_accepted, listOf(rtl), CommandMessage.Level.NOMINAL),
                ),
                ResultInWords(
                    VehicleCommand.RETURN_TO_LAUNCH,
                    CommandResult.Busy,
                    CommandMessage(R.string.hud_command_busy, listOf(rtl), CommandMessage.Level.CAUTION),
                ),
                ResultInWords(
                    VehicleCommand.RETURN_TO_LAUNCH,
                    CommandResult.Rejected(Rejection.DENIED, reason = "Not in a mode that allows it"),
                    CommandMessage(
                        R.string.hud_command_rejected,
                        listOf(rtl, "Not in a mode that allows it"),
                        CommandMessage.Level.EMERGENCY,
                    ),
                ),
                ResultInWords(
                    VehicleCommand.RETURN_TO_LAUNCH,
                    CommandResult.Rejected(Rejection.FAILED, reason = null),
                    CommandMessage(
                        R.string.hud_command_rejected,
                        listOf(rtl, TextArg(R.string.hud_rejection_failed)),
                        CommandMessage.Level.EMERGENCY,
                    ),
                ),
                ResultInWords(
                    VehicleCommand.RETURN_TO_LAUNCH,
                    CommandResult.NoAnswer(attempts = 3, startedByVehicle = false, retriesStopped = null),
                    CommandMessage(R.string.hud_command_no_answer, listOf(rtl, 3), CommandMessage.Level.EMERGENCY),
                ),
                ResultInWords(
                    VehicleCommand.RETURN_TO_LAUNCH,
                    CommandResult.NoAnswer(attempts = 1, startedByVehicle = true, retriesStopped = null),
                    CommandMessage(R.string.hud_command_started_no_end, listOf(rtl), CommandMessage.Level.EMERGENCY),
                ),
                ResultInWords(
                    VehicleCommand.RETURN_TO_LAUNCH,
                    CommandResult.NotSent(NotSentReason.LinkLost),
                    CommandMessage(
                        R.string.hud_command_not_sent,
                        listOf(rtl, TextArg(R.string.hud_not_sent_link_lost)),
                        CommandMessage.Level.CAUTION,
                    ),
                ),
            ).map { Arguments.of(it) }
        }

        @JvmStatic
        fun takeOffResultsInWords(): Stream<Arguments> {
            val takeOff = VehicleCommand.TAKE_OFF
            return Stream.of(
                ResultInWords(
                    takeOff,
                    CommandResult.NoAnswer(attempts = 1, startedByVehicle = false, retriesStopped = null),
                    CommandMessage(
                        R.string.hud_command_no_answer_not_resent,
                        listOf(TextArg(R.string.hud_command_take_off)),
                        CommandMessage.Level.EMERGENCY,
                    ),
                ),
                ResultInWords(
                    takeOff,
                    CommandResult.ModeNotChanged(modeChange = null),
                    CommandMessage(R.string.hud_command_mode_not_changed, level = CommandMessage.Level.EMERGENCY),
                ),
                ResultInWords(
                    takeOff,
                    CommandResult.TakeOffStopped(NotSentReason.NotLanded),
                    CommandMessage(
                        R.string.hud_command_take_off_stopped,
                        listOf(TextArg(R.string.hud_not_sent_not_landed)),
                        CommandMessage.Level.EMERGENCY,
                    ),
                ),
                ResultInWords(
                    takeOff,
                    CommandResult.Superseded,
                    CommandMessage(
                        R.string.hud_command_superseded,
                        listOf(TextArg(R.string.hud_command_take_off)),
                        CommandMessage.Level.ADVISORY,
                    ),
                ),
            ).map { Arguments.of(it) }
        }
    }
}
