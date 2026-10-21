package com.owindev.gcs.data.vehicle.telemetry

import com.owindev.gcs.core.domain.telemetry.TrailPoint
import com.owindev.gcs.core.mavlink.MavlinkMessage
import com.owindev.gcs.data.vehicle.link.ReceivedMessage
import com.owindev.gcs.data.vehicle.link.SessionId
import java.net.InetAddress
import java.net.InetSocketAddress

/** `GPS_FIX_TYPE` in `common.xml`. */
internal const val TRAIL_FIX_2D = 2
internal const val TRAIL_FIX_3D = 3

private const val MAV_COMP_ID_AUTOPILOT1 = 1
private val SENDER = InetSocketAddress(InetAddress.getLoopbackAddress(), 14_560)

/** Canberra, the SITL home, in degE7 as GLOBAL_POSITION_INT sends it. */
private const val CANBERRA_LAT_DEGE7 = -353_633_514
private const val CANBERRA_LON_DEGE7 = 1_491_652_411

/** 2.1038 m east at that latitude (GeodSolve, `GreatCircleTest`): clear of the trail's 2 m spacing. */
private const val EAST_STEP_DEGE7 = 232

private const val DEGE7 = 1e7

internal fun trailMessage(systemId: Int, message: MavlinkMessage, session: SessionId) =
    ReceivedMessage(systemId, MAV_COMP_ID_AUTOPILOT1, message, SENDER, session)

/** The trail point of [trailPosition] at [step]. */
internal fun trailPoint(step: Int) =
    TrailPoint(CANBERRA_LAT_DEGE7 / DEGE7, (CANBERRA_LON_DEGE7 + step * EAST_STEP_DEGE7) / DEGE7)

/** A GLOBAL_POSITION_INT [step] times 2.1 m east of Canberra, 20 m above home. */
internal fun trailPosition(step: Int, timeBootMs: Long = 397_289) = MavlinkMessage.GlobalPositionInt(
    timeBootMs = timeBootMs,
    lat = CANBERRA_LAT_DEGE7,
    lon = CANBERRA_LON_DEGE7 + step * EAST_STEP_DEGE7,
    alt = 587_150,
    relativeAlt = 20_000,
    vx = 0,
    vy = 0,
    vz = 0,
    hdg = 0,
)

internal fun trailGps(fixType: Int) = MavlinkMessage.GpsRawInt(
    timeUsec = 396_763_000,
    fixType = fixType,
    lat = CANBERRA_LAT_DEGE7,
    lon = CANBERRA_LON_DEGE7,
    alt = 587_150,
    eph = 121,
    epv = 200,
    vel = 0,
    cog = 0,
    satellitesVisible = 10,
    altEllipsoid = 587_150,
    hAcc = 300,
    vAcc = 300,
    velAcc = 0,
    hdgAcc = 0,
    yaw = 0,
)
