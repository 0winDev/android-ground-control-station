package com.owindev.gcs.data.vehicle.telemetry

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One message this GCS asks the vehicle to stream, and how often. */
internal data class TelemetryStream(val messageId: Int, val interval: Duration)

/**
 * The telemetry the HUD shows, requested from the active vehicle with `MAV_CMD_SET_MESSAGE_INTERVAL`
 * (REQ-023). IDs from `common.xml`.
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
 * | HEARTBEAT (0), sent by the vehicle anyway | 1 Hz | 21 B | 21 |
 *
 * About 932 B/s in all: ≈16 % of a 57600-baud 8N1 radio link (about 5760 B/s), and ≈32 % of a SiK radio
 * with ECC on, which halves its throughput (about 2900 B/s). The rest is margin for everything else the
 * vehicle sends.
 */
internal val TELEMETRY_STREAMS: List<TelemetryStream> = listOf(
    TelemetryStream(messageId = ATTITUDE_ID, interval = 100.milliseconds),
    TelemetryStream(messageId = GLOBAL_POSITION_INT_ID, interval = 200.milliseconds),
    TelemetryStream(messageId = VFR_HUD_ID, interval = 250.milliseconds),
    TelemetryStream(messageId = GPS_RAW_INT_ID, interval = 500.milliseconds),
    TelemetryStream(messageId = SYS_STATUS_ID, interval = 1.seconds),
)

private const val ATTITUDE_ID = 30
private const val GLOBAL_POSITION_INT_ID = 33
private const val VFR_HUD_ID = 74
private const val GPS_RAW_INT_ID = 24
private const val SYS_STATUS_ID = 1
