package com.owindev.gcs.data.vehicle.command

import com.owindev.gcs.core.domain.alert.AlertRepository
import com.owindev.gcs.core.domain.alert.Alerts
import com.owindev.gcs.core.domain.alert.GcsEnergyThresholds
import com.owindev.gcs.core.domain.background.BackgroundLinkRepository
import com.owindev.gcs.core.domain.background.BackgroundLinkRule
import com.owindev.gcs.core.domain.background.BackgroundLinkStatus
import com.owindev.gcs.core.domain.battery.FailsafeStatus
import com.owindev.gcs.core.domain.link.DataOrigin
import com.owindev.gcs.core.domain.link.UplinkRule
import com.owindev.gcs.core.domain.message.MessageSeverity
import com.owindev.gcs.core.domain.message.PreArmRule
import com.owindev.gcs.core.domain.message.VehicleMessage
import com.owindev.gcs.core.domain.preflight.PreflightAcknowledgements
import com.owindev.gcs.core.domain.preflight.PreflightColour
import com.owindev.gcs.core.domain.preflight.PreflightItem
import com.owindev.gcs.core.domain.preflight.PreflightKind
import com.owindev.gcs.core.domain.preflight.PreflightReason
import com.owindev.gcs.core.domain.preflight.PreflightRule
import com.owindev.gcs.core.domain.recording.RecordingRule
import com.owindev.gcs.core.domain.telemetry.Attitude
import com.owindev.gcs.core.domain.telemetry.Battery
import com.owindev.gcs.core.domain.telemetry.GlobalPosition
import com.owindev.gcs.core.domain.telemetry.GpsFixType
import com.owindev.gcs.core.domain.telemetry.GpsStatus
import com.owindev.gcs.core.domain.telemetry.Home
import com.owindev.gcs.core.domain.telemetry.PreArmStatus
import com.owindev.gcs.core.domain.telemetry.StalenessRule
import com.owindev.gcs.core.domain.telemetry.Telemetry
import com.owindev.gcs.core.domain.telemetry.Timed
import com.owindev.gcs.core.domain.vehicle.FlightMode
import com.owindev.gcs.core.domain.vehicle.LinkLossRule
import com.owindev.gcs.core.domain.vehicle.SystemId
import com.owindev.gcs.core.domain.vehicle.Vehicle
import com.owindev.gcs.core.domain.vehicle.VehicleLink
import com.owindev.gcs.core.domain.vehicle.VehicleState
import com.owindev.gcs.core.domain.vehicle.VehicleType
import com.owindev.gcs.core.domain.vehicle.command.NotSentReason
import com.owindev.gcs.data.vehicle.link.DefaultUplinkRepository
import com.owindev.gcs.data.vehicle.link.FIRST_SESSION
import com.owindev.gcs.data.vehicle.link.LinkSession
import com.owindev.gcs.data.vehicle.link.SessionId
import com.owindev.gcs.data.vehicle.message.FollowedMessages
import com.owindev.gcs.data.vehicle.message.MessageStore
import com.owindev.gcs.data.vehicle.parameter.BatteryParameter
import com.owindev.gcs.data.vehicle.parameter.FollowedParameters
import com.owindev.gcs.data.vehicle.parameter.ParameterStore
import com.owindev.gcs.data.vehicle.state.ActiveVehicle
import com.owindev.gcs.data.vehicle.state.DefaultVehicleRepository
import com.owindev.gcs.data.vehicle.telemetry.TelemetryStore
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The gate reads the stores as they are now, for the active vehicle and session only (REQ-048). */
@Tag("REQ-048")
class PreflightGateTest {

    private val clock = TestTimeSource()
    private val vehicleRepository: DefaultVehicleRepository = mockk()
    private val session: LinkSession = mockk()
    private val telemetryStore: TelemetryStore = mockk()
    private val parameterStore: ParameterStore = mockk()
    private val messageStore: MessageStore = mockk()
    private val alertRepository: AlertRepository = mockk()
    private val activeVehicle = ActiveVehicle()
    private val uplink = DefaultUplinkRepository(clock)
    private val telemetry = MutableStateFlow<Map<SystemId, Telemetry>>(emptyMap())
    private val parameters = MutableStateFlow(FollowedParameters.empty(FIRST_SESSION))
    private val messages = MutableStateFlow(FollowedMessages.empty(FIRST_SESSION))

    private lateinit var gate: PreflightGate

    @BeforeEach
    fun setUp() {
        every { vehicleRepository.vehicles } returns MutableStateFlow(mapOf(ACTIVE to vehicle(ACTIVE)))
        every { vehicleRepository.activeConnection } returns CONNECTION
        every { session.session } returns MutableStateFlow(FIRST_SESSION)
        every { telemetryStore.telemetry } returns telemetry
        every { telemetryStore.session } returns FIRST_SESSION
        every { parameterStore.parameters } returns parameters
        every { messageStore.messages } returns messages
        every { alertRepository.activeAlerts } returns MutableStateFlow(Alerts.NotEvaluated)
        activeVehicle.pinFirst(ACTIVE, FIRST_SESSION)
        gate = PreflightGate(
            vehicles = vehicleRepository,
            activeVehicle = activeVehicle,
            session = session,
            telemetry = telemetryStore,
            parameters = parameterStore,
            messages = messageStore,
            uplink = uplink,
            background = object : BackgroundLinkRepository {
                override val status = MutableStateFlow(BackgroundLinkStatus(running = true))
            },
            alerts = alertRepository,
            rule = PreflightRule(
                linkLoss = LinkLossRule(),
                staleness = StalenessRule(),
                preArm = PreArmRule(),
                uplinkRule = UplinkRule(),
                backgroundRule = BackgroundLinkRule(LinkLossRule(), RecordingRule()),
                energyDefaults = GcsEnergyThresholds(),
            ),
        )
    }

    @Test
    fun `GIVEN the active vehicle's GPS without a 3D fix WHEN taking off THEN it is refused with that red item`() {
        telemetry.value = mapOf(ACTIVE to telemetryWith(GpsFixType.NoFix))

        val result = gate.refusalToTakeOff(ACTIVE)

        (result as NotSentReason.PreflightRed).items shouldContain
            PreflightItem(PreflightKind.GPS, PreflightColour.RED, PreflightReason.NoFix3d)
    }

    @Test
    fun `GIVEN a system other than the active one WHEN checking THEN its telemetry is not used and the link is red`() {
        telemetry.value = mapOf(OTHER to telemetryWith(GpsFixType.Fix3d))

        val result = gate.checkFor(OTHER)

        result.items.filter { it.kind in setOf(PreflightKind.LINK, PreflightKind.GPS) } shouldBeEqualTo listOf(
            PreflightItem(PreflightKind.LINK, PreflightColour.RED, PreflightReason.NoActiveVehicle),
            PreflightItem(PreflightKind.GPS, PreflightColour.RED, PreflightReason.NotReceived),
        )
    }

    @Test
    fun `GIVEN the stores not yet moved to the new session WHEN checking THEN nothing they hold is used`() {
        telemetry.value = mapOf(ACTIVE to telemetryWith(GpsFixType.Fix3d))
        every { telemetryStore.session } returns ENDED_SESSION

        val result = gate.checkFor(ACTIVE)

        result.items.filter { it.kind in setOf(PreflightKind.LINK, PreflightKind.GPS) } shouldBeEqualTo listOf(
            PreflightItem(PreflightKind.LINK, PreflightColour.RED, PreflightReason.NoActiveVehicle),
            PreflightItem(PreflightKind.GPS, PreflightColour.RED, PreflightReason.NotReceived),
        )
    }

    @Test
    fun `GIVEN every battery setting read in the session before WHEN checking THEN the limits are not read`() {
        parameters.value = FollowedParameters(ENDED_SESSION, ACTIVE, ALL_PARAMETERS)

        val result = gate.checkFor(ACTIVE)

        result.items.single { it.kind == PreflightKind.BATTERY_LIMITS } shouldBeEqualTo
            PreflightItem(PreflightKind.BATTERY_LIMITS, PreflightColour.AMBER, PreflightReason.LimitsNotRead)
    }

    @Test
    fun `GIVEN every battery setting read in this session WHEN checking THEN they are judged`() {
        parameters.value = FollowedParameters(FIRST_SESSION, ACTIVE, ALL_PARAMETERS)

        val result = gate.checkFor(ACTIVE)

        result.items.single { it.kind == PreflightKind.BATTERY_LIMITS }.colour shouldBeEqualTo PreflightColour.GREEN
    }

    @Test
    fun `GIVEN a pre-arm reason held for another system WHEN the checks fail THEN no reason is given`() {
        telemetry.value = mapOf(ACTIVE to telemetryWith(GpsFixType.Fix3d, preArmHealthy = false))
        messages.value = FollowedMessages(FIRST_SESSION, OTHER, emptyList(), preArmReason(clock))

        val result = gate.checkFor(ACTIVE)

        result.items.single { it.kind == PreflightKind.VEHICLE_PREARM }.reason shouldBeEqualTo
            PreflightReason.PreArmFailing(text = null, current = false)
    }

    @Test
    fun `GIVEN the active vehicle's pre-arm reason WHEN the checks fail THEN it is given as current`() {
        telemetry.value = mapOf(ACTIVE to telemetryWith(GpsFixType.Fix3d, preArmHealthy = false))
        messages.value = FollowedMessages(FIRST_SESSION, ACTIVE, emptyList(), preArmReason(clock))

        val result = gate.checkFor(ACTIVE)

        result.items.single { it.kind == PreflightKind.VEHICLE_PREARM }.reason shouldBeEqualTo
            PreflightReason.PreArmFailing(text = PRE_ARM_TEXT, current = true)
    }

    @Test
    fun `GIVEN only an amber item WHEN arming without acknowledging it THEN it is refused with that item`() {
        allGreenBut(critical = WARN_ONLY_ACTION)

        val result = gate.refusalToArm(ACTIVE, PreflightAcknowledgements())

        result shouldBeEqualTo NotSentReason.PreflightUnacknowledged(listOf(CRITICAL_WARN_ONLY))
    }

    @Test
    fun `GIVEN only an amber item WHEN arming with it acknowledged THEN nothing refuses it`() {
        allGreenBut(critical = WARN_ONLY_ACTION)
        val check = gate.checkFor(ACTIVE)
        val acknowledged = PreflightAcknowledgements().acknowledge(CRITICAL_WARN_ONLY, check, clock.markNow())

        val result = gate.refusalToArm(ACTIVE, acknowledged)

        result shouldBeEqualTo null
    }

    @Test
    fun `GIVEN the vehicle repository's connection WHEN checking THEN the check is judged on it`() {
        val systemId = ACTIVE

        val result = gate.checkFor(systemId)

        result.connection shouldBeEqualTo CONNECTION
    }

    /** Every item green, the critical failsafe action set to [critical]. */
    private fun allGreenBut(critical: Float) {
        uplink.answersFrom(VEHICLE_ROUTE)
        uplink.commandAnswered(VEHICLE_ROUTE)
        parameters.value = FollowedParameters(
            FIRST_SESSION,
            ACTIVE,
            ALL_PARAMETERS + (BatteryParameter.FS_CRT_ACT to critical),
        )
        val now = clock.markNow()
        telemetry.value = mapOf(
            ACTIVE to telemetryWith(GpsFixType.Fix3d).copy(
                position = Timed(POSITION, now),
                attitude = Timed(Attitude(roll = 0.0, pitch = 0.0, yaw = 0.0), now),
                battery = Timed(Battery(12.6, 0.0, 100, monitored = true, healthy = true), now),
                home = Timed(Home(latitude = -35.3633514, longitude = 149.1652411, altitudeMsl = 587.13), now),
            ),
        )
    }

    private fun vehicle(systemId: SystemId) = Vehicle(
        state = VehicleState(systemId, VehicleType.COPTER, FlightMode.Named("Loiter"), armed = false),
        link = VehicleLink.CONNECTED,
        lastHeartbeat = clock.markNow(),
        origin = DataOrigin.LIVE,
    )

    private fun telemetryWith(fixType: GpsFixType, preArmHealthy: Boolean = true) = Telemetry(
        origin = DataOrigin.LIVE,
        gps = Timed(gpsStatus(fixType), clock.markNow()),
        preArm = Timed(PreArmStatus(present = true, enabled = true, healthy = preArmHealthy), clock.markNow()),
    )
}

private val ACTIVE = SystemId(1)
private val VEHICLE_ROUTE = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)
private val OTHER = SystemId(2)
private const val CONNECTION = 7L
private val ENDED_SESSION = SessionId(DataOrigin.LIVE, epoch = FIRST_SESSION.epoch - 1)
private const val PRE_ARM_TEXT = "PreArm: GPS 1: Bad fix"

/** `BATT_FS_CRT_ACT` 0: the vehicle only warns. */
private const val WARN_ONLY_ACTION = 0f

private val CRITICAL_WARN_ONLY = PreflightItem(
    PreflightKind.BATTERY_LIMITS,
    PreflightColour.AMBER,
    PreflightReason.FailsafeNotArmed(low = FailsafeStatus.ARMED, critical = FailsafeStatus.WARN_ONLY),
)

private val POSITION = GlobalPosition(
    latitude = -35.3633514,
    longitude = 149.1652411,
    altitudeMsl = 587.13,
    altitudeAboveHome = 0.0,
    velocityNorth = 0.0,
    velocityEast = 0.0,
    velocityDown = 0.0,
    heading = 0.0,
)

/**
 * ArduCopter's defaults but both levels acting: 10.5 V and 9.9 V, `BATT_LOW_TIMER` 10 s, a 5000 mAh capacity with
 * 1000 mAh low, RTL (2) on low and Land (1) on critical, the raw voltage sent and compared.
 */
private val ALL_PARAMETERS: Map<BatteryParameter, Float> = mapOf(
    BatteryParameter.MONITOR to 4f,
    BatteryParameter.LOW_VOLT to 10.5f,
    BatteryParameter.CRT_VOLT to 9.9f,
    BatteryParameter.LOW_MAH to 1_000f,
    BatteryParameter.CRT_MAH to 0f,
    BatteryParameter.LOW_TIMER to 10f,
    BatteryParameter.CAPACITY to 5_000f,
    BatteryParameter.FS_LOW_ACT to 2f,
    BatteryParameter.FS_CRT_ACT to 1f,
    BatteryParameter.OPTIONS to 0f,
    BatteryParameter.FS_VOLTSRC to 0f,
)

private fun gpsStatus(fixType: GpsFixType) = GpsStatus(
    fixType = fixType,
    satellitesVisible = 10,
    hdop = 1.21,
    vdop = 2.0,
    groundSpeed = 0.0,
    courseOverGround = 0.0,
    horizontalAccuracy = 0.3,
    verticalAccuracy = 0.3,
    yaw = null,
)

private fun preArmReason(clock: TestTimeSource) =
    VehicleMessage(MessageSeverity.CRITICAL, PRE_ARM_TEXT, clock.markNow(), DataOrigin.LIVE)
