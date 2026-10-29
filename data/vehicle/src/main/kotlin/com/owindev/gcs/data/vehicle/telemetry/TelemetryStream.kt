package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.TelemetryKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One message this GCS asks the vehicle to stream, how often, and the part of the telemetry it carries: `null` [kind]
 * for one whose silence is normal, which is never requested again for being silent.
 */
internal data class TelemetryStream(val messageId: Int, val interval: Duration, val kind: TelemetryKind?)

/**
 * The telemetry the HUD and the map show, requested from the active vehicle with `MAV_CMD_SET_MESSAGE_INTERVAL`
 * (REQ-023, REQ-033). IDs from `common.xml`.
 *
 * Bandwidth budget, at each message's full MAVLink 2 size (payload + 12 bytes of header and checksum, as
 * pymavlink 2.4.50 gives them):
 *
 * | Message | Rate | Frame | Bytes/s |
 * |---|---|---|---|
 * | ATTITUDE (30) | 10 Hz | 40 B | 400 |
 * | GLOBAL_POSITION_INT (33) | 5 Hz | 40 B | 200 |
 * | VFR_HUD (74) | 4 Hz | 32 B | 128 |
 * | GPS_RAW_INT (24) | 2 Hz | 64 B | 128 |
 * | SYS_STATUS (1) | 1 Hz | 55 B | 55 |
 * | HOME_POSITION (242), once home is set | 1 Hz | 72 B | 72 |
 * | EXTENDED_SYS_STATE (245) | 4 Hz | 14 B | 56 |
 * | HEARTBEAT (0), sent by the vehicle anyway | 1 Hz | 21 B | 21 |
 *
 * About 1060 B/s in all: ≈18 % of a 57600-baud 8N1 radio link (about 5760 B/s), and ≈37 % of a SiK radio
 * with ECC on, which halves its throughput (about 2900 B/s). The rest is margin for everything else the
 * vehicle sends.
 *
 * ArduPilot sends no HOME_POSITION until home is set (`MSG_HOME` in `GCS_Common.cpp`), so its silence is not
 * watched: it is requested in every round, never for being silent.
 */
internal val TELEMETRY_STREAMS: List<TelemetryStream> = listOf(
    TelemetryStream(messageId = ATTITUDE_ID, interval = 100.milliseconds, kind = TelemetryKind.ATTITUDE),
    TelemetryStream(messageId = GLOBAL_POSITION_INT_ID, interval = 200.milliseconds, kind = TelemetryKind.POSITION),
    TelemetryStream(messageId = VFR_HUD_ID, interval = 250.milliseconds, kind = TelemetryKind.AIR_DATA),
    TelemetryStream(messageId = GPS_RAW_INT_ID, interval = 500.milliseconds, kind = TelemetryKind.GPS),
    TelemetryStream(messageId = SYS_STATUS_ID, interval = 1.seconds, kind = TelemetryKind.BATTERY),
    TelemetryStream(messageId = HOME_POSITION_ID, interval = 1.seconds, kind = null),
    // ArduCopter 4.7.2 sends it on a channel only once asked there (#112); 4 Hz keeps a take off's window short (#99).
    TelemetryStream(messageId = EXTENDED_SYS_STATE_ID, interval = 250.milliseconds, kind = TelemetryKind.LANDED),
)

private const val ATTITUDE_ID = 30
private const val GLOBAL_POSITION_INT_ID = 33
private const val VFR_HUD_ID = 74
private const val GPS_RAW_INT_ID = 24
private const val SYS_STATUS_ID = 1
private const val HOME_POSITION_ID = 242
private const val EXTENDED_SYS_STATE_ID = 245
