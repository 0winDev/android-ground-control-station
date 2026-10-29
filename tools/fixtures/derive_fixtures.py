#!/usr/bin/env python3
"""Derive MAVLink test fixtures for the codec from the real SITL captures.

Offline tool for the codec tests in :core:mavlink, like extract_fixtures.py. pymavlink is only the
reference implementation that builds and checks these frames; our own codec is never used here.

Writes:

  <out>/derived/heartbeat-signed.bin            heartbeat/disarmed-stabilize.bin re-packed and signed by
                                                pymavlink (incompat flag 0x01 + 13-byte signature) with a
                                                fixed test key, link ID and timestamp
  <out>/derived/heartbeat-unknown-incompat.bin  the same frame with incompat flags 0x02 and the checksum
                                                recomputed by pymavlink
  <out>/derived/heartbeat-unknown-compat.bin    the same frame with compat flags 0x80 and the checksum
                                                recomputed by pymavlink
  <out>/derived/heartbeat-empty-payload.bin     the same header with LEN 0 and no payload, checksum
                                                recomputed by pymavlink
  <out>/derived/heartbeat-extra-byte.bin        the same frame with one unknown extension byte (LEN 10),
                                                checksum recomputed by pymavlink
  <out>/derived/heartbeat-max-custom-mode.bin   the HEARTBEAT re-packed by pymavlink with custom_mode
                                                0xFFFFFFFF
  <out>/derived/heartbeat-sysid-2.bin           heartbeat/disarmed-loiter.bin re-packed by pymavlink with
                                                system ID 2 (same fields, seq and component): a second
                                                vehicle on the link
  <out>/derived/heartbeat-gimbal-component.bin  heartbeat/disarmed-stabilize.bin re-packed by pymavlink from
                                                component 154 (MAV_COMP_ID_GIMBAL), same system, seq and
                                                fields: another component of the vehicle
  <out>/derived/heartbeat-asymmetric-custom-mode.bin
                                                heartbeat/disarmed-stabilize.bin re-packed by pymavlink with
                                                custom_mode 0x12345678: every byte of the uint32 differs, so a
                                                swapped byte order shows
  <out>/derived/heartbeat-trailing-zeros.bin   heartbeat/disarmed-stabilize.bin re-packed by pymavlink with
                                                custom_mode 5 and base_mode, system_status and
                                                mavlink_version 0 (same seq, system, component, type and
                                                autopilot): pymavlink truncates the trailing zeros to LEN 6
  <out>/derived/heartbeat-all-zero.bin          the same header with every field 0: truncated to LEN 1,
                                                since the first payload byte is never truncated
  <out>/gcs/heartbeat-mission-planner.bin       a real GCS HEARTBEAT (not derived) from
                                                heartbeat/session.tlog: Mission Planner, system 255
  <out>/unknown/attitude-fd-in-payload.bin      a real ATTITUDE frame (not derived) from
                                                heartbeat/session.tlog whose payload contains 0xFD
  <out>/derived/attitude-signed.bin             that ATTITUDE re-packed and signed the same way (a signed
                                                frame whose message ID the parser tests treat as unknown)
  <out>/derived/vfr-hud-signed.bin              telemetry/vfr-hud.bin (truncated, LEN 18) signed the same way
  <out>/derived/scaled-pressure-signed.bin      unknown/scaled-pressure.bin signed the same way: a signed frame
                                                whose message ID the codec does not know
  <out>/derived/setup-signing.bin               a SETUP_SIGNING from the GCS (255/190) to 1/1 with the test key,
                                                built by pymavlink
  <out>/derived/setup-signing-signed.bin        the same SETUP_SIGNING, signed the same way
  <out>/derived/session-stream.bin              heartbeat/session.tlog as a plain byte stream: every
                                                frame (MAVLink 1 and 2) in order, without the 8-byte
                                                .tlog timestamps
  <out>/command/set-message-interval.bin        a real COMMAND_LONG (not derived) from heartbeat/session.tlog:
                                                Mission Planner's first MAV_CMD_SET_MESSAGE_INTERVAL
  <out>/command/set-message-interval-ack.bin    ArduCopter's real COMMAND_ACK to that command
  <out>/command/command-long-confirmation.bin   a real COMMAND_LONG with confirmation 1, so not truncated
  <out>/derived/command-long-all-params.bin     the SET_MESSAGE_INTERVAL re-packed by pymavlink with seven
                                                distinct params (NaN among them), command 0x1234 and
                                                confirmation 7: every slot and byte shows
  <out>/derived/command-ack-extensions.bin      the ACK re-packed by pymavlink with result IN_PROGRESS,
                                                progress 50 and a negative result_param2
  <out>/derived/command-ack-accepted-no-extensions.bin
                                                the ACK re-packed by pymavlink with every extension 0:
                                                result ACCEPTED (0) is a trailing zero too, so LEN 2
  <out>/derived/command-ack-failed-no-extensions.bin
                                                the same with result FAILED (4): LEN 3
  <out>/derived/command-ack-split-param2.bin    the ACK with result_param2 256 and targets 0: LEN 6 cuts the
                                                int32 after its second byte
  <out>/derived/command-ack-target-system-only.bin
                                                the ACK with target_component 0: LEN 9, only the last
                                                extension byte is missing
  <out>/derived/command-long-broadcast.bin      the SET_MESSAGE_INTERVAL re-packed by pymavlink to target
                                                0/0: the zero targets and confirmation are truncated, LEN 30
  <out>/telemetry/attitude.bin                  the first real ATTITUDE (not derived) of heartbeat/session.tlog
  <out>/telemetry/global-position-int.bin       the first real GLOBAL_POSITION_INT of the session with a
                                                negative vy, so a real int16 is negative
  <out>/telemetry/vfr-hud.bin                   the first real VFR_HUD of the session: throttle 0 is
                                                truncated, LEN 18
  <out>/unknown/scaled-pressure.bin             the first real SCALED_PRESSURE of the session: a message the
                                                codec does not decode
  <out>/derived/attitude-edges.bin              the ATTITUDE re-packed by pymavlink with time_boot_ms above
                                                Int.MAX_VALUE and distinct floats, yaw near -pi
  <out>/derived/global-position-int-edges.bin   the GLOBAL_POSITION_INT re-packed by pymavlink with
                                                time_boot_ms above Int.MAX_VALUE, negative alt,
                                                relative_alt, vx and vz, and hdg UINT16_MAX (unknown)
  <out>/derived/vfr-hud-edges.bin               the VFR_HUD re-packed by pymavlink with a negative climb and
                                                throttle 65: only its zero high byte is truncated (LEN 19)
  <out>/telemetry/gps-raw-int.bin               the first real GPS_RAW_INT (not derived) of the session: v_acc
                                                300 (2c 01 00 00) is cut inside, LEN 40
  <out>/telemetry/sys-status.bin                the first real SYS_STATUS of the session with a battery reading
                                                (12600 mV, 100 %); the zero extensions are truncated, LEN 31.
                                                truncated/sys_status.bin, read too, is an earlier one from boot
                                                with voltage_battery 0 and the other two battery fields -1
  <out>/derived/sys-status-edges.bin           truncated/sys_status.bin re-packed by pymavlink with a
                                                distinct value in every field, the extensions included (LEN 43)
  <out>/derived/sys-status-unknown-battery.bin  the same SYS_STATUS with voltage_battery UINT16_MAX and
                                                current_battery and battery_remaining -1 ("unknown"); the zero
                                                extensions are truncated, LEN 31
  <out>/derived/gps-raw-int-edges.bin           the GPS_RAW_INT re-packed by pymavlink with a distinct value in
                                                every field, time_usec and h_acc beyond the signed ranges,
                                                negative altitudes and yaw 36000 (north), LEN 52
  <out>/derived/gps-raw-int-unknown.bin         the GPS_RAW_INT with eph, epv, vel, cog UINT16_MAX,
                                                satellites_visible UINT8_MAX, fix_type NO_GPS and every
                                                extension 0: truncated to the 30 base bytes
  <out>/derived/sys-status-battery-remaining-min.bin
                                                telemetry/sys-status.bin re-packed by pymavlink with
                                                battery_remaining -128 (0x80), the lowest int8
  <out>/derived/gps-raw-int-time-2-63.bin       telemetry/gps-raw-int.bin re-packed by pymavlink with time_usec
                                                2^63 (0x8000000000000000): out of a Long's range
  <out>/derived/sys-status-battery-zero-volts.bin
                                                telemetry/sys-status.bin re-packed by pymavlink with
                                                voltage_battery 0: a healthy, enabled monitor reading 0 V
  <out>/derived/sys-status-no-battery-monitor.bin
                                                telemetry/sys-status.bin re-packed by pymavlink without the
                                                battery bit in onboard_control_sensors_enabled, voltage_battery
                                                0 and the other two battery fields -1: ArduPilot with no monitor
  <out>/derived/sys-status-battery-unhealthy.bin
                                                telemetry/sys-status.bin re-packed by pymavlink without the
                                                battery bit in onboard_control_sensors_health: an unhealthy
                                                monitor or a battery failsafe
  <out>/derived/global-position-int-hdg-36000.bin
                                                telemetry/global-position-int.bin re-packed by pymavlink with hdg
                                                36000: past 359.99 degrees, the documented range (#49)
  <out>/derived/global-position-int-lat-past-90.bin
                                                the same with lat 900000001 (90.0000001 degrees)
  <out>/derived/global-position-int-lon-past-180.bin
                                                the same with lon -1800000001 (-180.0000001 degrees)
  <out>/derived/global-position-int-lon-past-180-east.bin
                                                the same with lon 1800000001 (180.0000001 degrees)
  <out>/derived/global-position-int-lat-int32-min.bin
                                                the same with lat -2147483648 (INT32_MIN), whose absolute value
                                                does not fit an int32
  <out>/derived/global-position-int-ends.bin    the same with lat 900000000, lon -1800000000 and hdg 35999: the
                                                ends of every range, all valid
  <out>/derived/global-position-int-ends-south-east.bin
                                                the same with lat -900000000 and lon 1800000000: the other ends
  <out>/derived/gps-raw-int-out-of-range.bin    telemetry/gps-raw-int.bin re-packed by pymavlink with cog 40000
                                                and yaw 36001, the rest as captured
  <out>/derived/vfr-hud-out-of-range.bin        telemetry/vfr-hud.bin re-packed by pymavlink with airspeed NaN,
                                                climb +infinity and throttle 101, groundspeed as captured
  <out>/derived/vfr-hud-throttle-100.bin        the same VFR_HUD with throttle 100, the end of its range
  <out>/derived/attitude-out-of-range.bin       telemetry/attitude.bin re-packed by pymavlink with roll NaN,
                                                pitch -infinity and yaw 0.5
  <out>/derived/attitude-past-pi.bin            the same ATTITUDE with roll pi (the float32 closest to it, the
                                                end of the range), pitch -0.25 and yaw 3.1416, past it
  <out>/telemetry/home-position.bin             the only real HOME_POSITION (not derived) of the session: q NaN,
                                                approach 0, the zero high bytes of time_usec truncated, LEN 56
  <out>/derived/home-position-full.bin          that HOME_POSITION re-packed by pymavlink with a distinct value
                                                in every field and time_usec 0x0102030405060708: nothing is
                                                truncated, LEN 60
  <out>/derived/home-position-no-extension.bin  the same with time_usec 0 and approach_z -1.5: the extension is
                                                truncated whole, LEN 52
  <out>/derived/home-position-cut-after-q.bin   the real one with time_usec 0: the zero approach and extension
                                                are truncated after ArduPilot's NaN q, LEN 40
  <out>/derived/home-position-moved.bin         the real HOME_POSITION re-packed by pymavlink at another point
                                                (about 100 m north-east, 3 m higher): home set again on arming
  <out>/derived/home-position-lat-past-90.bin   the same with latitude 900000001 (90.0000001 degrees) (#69)
  <out>/derived/home-position-lat-int32-min.bin the same with latitude -2147483648 (INT32_MIN)
  <out>/derived/home-position-null-island.bin   the same with latitude and longitude 0: never a home (#69)
  <out>/statustext/arming-motors.bin            the first real STATUSTEXT (not derived) of the session:
                                                "Arming motors", INFO, truncated to LEN 14 without extensions
  <out>/derived/statustext-prearm.bin           that STATUSTEXT re-packed by pymavlink (same seq, system,
                                                component) as ArduCopter 4.7.2 reports a failed pre-arm check:
                                                "PreArm: RC not found", CRITICAL (#96)
  <out>/derived/statustext-arm.bin              the same as a refused arm: "Arm: RTL mode not armable", CRITICAL
  <out>/derived/statustext-full.bin             the same with 50 distinct text bytes and no NUL, severity WARNING,
                                                id 0x1234 and chunk_seq 7: nothing truncated, LEN 54
  <out>/derived/statustext-chunk-1.bin          a text longer than 50 bytes split as ArduPilot does
  <out>/derived/statustext-chunk-2.bin          (GCS_Common.cpp): id 1, chunk_seq 0 with the first 50 bytes,
                                                chunk_seq 1 with the rest, NUL-padded before the extensions
  <out>/derived/statustext-nul-inside.bin       the same with text bytes after a NUL: the text ends at the NUL
  <out>/derived/statustext-utf8.bin             the same with a two-byte UTF-8 character and an invalid byte
  <out>/derived/statustext-empty.bin            the same with no text and severity EMERGENCY: LEN 1, the first
                                                payload byte is never truncated
  <out>/derived/statustext-severity-8.bin       the same with severity 8, outside MAV_SEVERITY
  <out>/statustext/session.tsv                  every MAVLink 2 STATUSTEXT of heartbeat/session.tlog and of
  <out>/statustext/mission-planner.tsv          tlog/mission-planner.tlog as pymavlink decodes it, in order
  <out>/param/stat-runtime.bin                  the first real PARAM_VALUE (not derived) of the session: STAT_RUNTIME,
                                                an INT32 parameter sent as a float
  <out>/param/mot-thst-hover.bin                the first real REAL32 PARAM_VALUE of the session: MOT_THST_HOVER
  <out>/param/ins-gyr1-caltemp.bin              the first real PARAM_VALUE of tlog/mission-planner.tlog with a
                                                16-character param_id, so no NUL
  <out>/derived/param-value-batt-low-volt.bin   param/mot-thst-hover.bin re-packed by pymavlink as BATT_LOW_VOLT 10.5
  <out>/derived/param-value-fields.bin          the same with a distinct byte in every field and a 16-character id
  <out>/derived/param-value-nul-inside.bin      the same with id bytes after a NUL: the id ends at the NUL
  <out>/derived/param-value-type-0.bin          the same with param_type 0: it and the id's NUL padding are
                                                truncated
  <out>/derived/param-value-empty-id.bin        the same with no id and param_type 0: LEN 8, the id starts past the
                                                payload
  <out>/derived/param-request-read-batt-low-volt.bin
                                                a PARAM_REQUEST_READ from the GCS (255/190) to 1/1 for
                                                BATT_LOW_VOLT, param_index -1, built by pymavlink: LEN 17
  <out>/derived/param-request-read-16-chars.bin the same for a 16-character name: no NUL, LEN 20
  <out>/derived/param-request-read-1-char.bin   the same for a 1-character name: LEN 5
  <out>/landed/<state>.bin                      the first real EXTENDED_SYS_STATE (not derived) of each landed state in
                                                landed/session.tlog (ArduCopter 4.7.2 SITL, #112): on-ground,
                                                takeoff, in-air and landing, vtol_state MC, LEN 2
  <out>/derived/extended-sys-state-undefined.bin
                                                landed/on-ground.bin re-packed by pymavlink with landed_state 0
                                                (UNDEFINED): the zero is truncated, LEN 1
  <out>/derived/extended-sys-state-landed-5.bin the same with landed_state 5, the first value past MAV_LANDED_STATE
                                                in common.xml
  <out>/param/session.tsv                       every MAVLink 2 PARAM_VALUE of heartbeat/session.tlog and of
  <out>/param/mission-planner.tsv               tlog/mission-planner.tlog as pymavlink decodes it, in order
  <out>/derived/session-mavlink2-stream.bin     heartbeat/session.tlog's MAVLink 2 frames only, in order, as on
                                                the wire: what the parser hands out of session-stream.bin
  <out>/derived/heartbeat-corrupted-len-raised.bin
                                                heartbeat/disarmed-stabilize.bin with LEN set to 255, checksum
                                                not recomputed: a corrupted LEN that claims a maximum frame
  <out>/derived/heartbeat-corrupted-len-lowered.bin
                                                the same with LEN set to 1: a corrupted LEN that cuts the frame

checks each output with pymavlink before writing it, prints the decoded fields of the telemetry fixtures
(floats as the shortest decimal text that is the same float32), the session counts the tests expect and, for
the telemetry the app maps, how many of the vehicle's frames carry each value (not a documented "unknown").

Usage:
  py -m pip install --user pymavlink==2.4.50
  py tools/fixtures/derive_fixtures.py [--out core/mavlink/src/test/resources]
"""

import argparse
import io
import math
import struct
from pathlib import Path

import pymavlink
from pymavlink import mavutil
from pymavlink.dialects.v20 import common
from pymavlink.generator.mavcrc import x25crc

SOURCE = "heartbeat/disarmed-stabilize.bin"
LOITER = "heartbeat/disarmed-loiter.bin"
SECOND_SYSTEM_ID = 2
MAV_COMP_ID_GIMBAL = 154
SESSION = "heartbeat/session.tlog"
HEADER_LENGTH = 10
CHECKSUM_LENGTH = 2
INCOMPAT_INDEX = 2
COMPAT_INDEX = 3
UNKNOWN_INCOMPAT = 0x02
UNKNOWN_COMPAT = 0x80
START_MARKER = 0xFD
ATTITUDE_ID = common.MAVLINK_MSG_ID_ATTITUDE
SETUP_SIGNING_ID = 256  # common.xml; omitted from logs (signing guide, Logging)
GCS_SYSTEM_ID = 255
# Test-only signing material: never a real key.
TEST_KEY = bytes(range(32))
TEST_LINK_ID = 0
TEST_TIMESTAMP = 0x0000_1234_5678
MAX_SHOWN = 64
UNKNOWN_EXTENSION_BYTE = 0x2A
MAX_UINT32 = 0xFFFF_FFFF
ASYMMETRIC_CUSTOM_MODE = 0x1234_5678
LOITER_MODE = 5  # ArduCopter custom_mode for Loiter, as in heartbeat/disarmed-loiter.bin
TRAILING_ZEROS_LENGTH = 6  # custom_mode (4 bytes), type, autopilot; the three zero fields after them go
TABLE_MESSAGES = {
    common.MAVLINK_MSG_ID_HEARTBEAT: "heartbeat",
    common.MAVLINK_MSG_ID_ATTITUDE: "attitude",
    common.MAVLINK_MSG_ID_GLOBAL_POSITION_INT: "global_position_int",
    common.MAVLINK_MSG_ID_VFR_HUD: "vfr_hud",
    common.MAVLINK_MSG_ID_SYS_STATUS: "sys_status",
    common.MAVLINK_MSG_ID_GPS_RAW_INT: "gps_raw_int",
    common.MAVLINK_MSG_ID_SERVO_OUTPUT_RAW: "servo_output_raw",
    common.MAVLINK_MSG_ID_POWER_STATUS: "power_status",
    common.MAVLINK_MSG_ID_COMMAND_LONG: "command_long",
    common.MAVLINK_MSG_ID_COMMAND_ACK: "command_ack",
    common.MAVLINK_MSG_ID_HOME_POSITION: "home_position",
    common.MAVLINK_MSG_ID_STATUSTEXT: "statustext",
    common.MAVLINK_MSG_ID_PARAM_REQUEST_READ: "param_request_read",
    common.MAVLINK_MSG_ID_PARAM_VALUE: "param_value",
}
SET_MESSAGE_INTERVAL = common.MAV_CMD_SET_MESSAGE_INTERVAL
MAV_COMP_ID_MISSIONPLANNER = 190
# Seven distinct params, exactly representable as float32 (NaN as pymavlink packs it, 00 00 c0 7f), and a
# command and confirmation whose bytes all differ: a swapped slot or byte order shows. Not a real command.
ALL_PARAMS = (1.5, -2.25, float("nan"), 100000.0, -1.0, 0.5, 65535.0)
ARBITRARY_COMMAND = 0x1234
ARBITRARY_CONFIRMATION = 7
NAN_PARAM_INDEX = 2
IN_PROGRESS_PROGRESS = 50
NEGATIVE_RESULT_PARAM2 = -123456
FULL_COMMAND_LONG_LENGTH = 33
FULL_COMMAND_ACK_LENGTH = 10
ACCEPTED_NO_EXTENSIONS_LENGTH = 2  # command (uint16); result 0 is truncated with the zero extensions
FAILED_NO_EXTENSIONS_LENGTH = 3  # command (uint16), result
SPLIT_RESULT_PARAM2 = 256  # 00 01 00 00: the two high zero bytes are truncated with the zero targets
SPLIT_PARAM2_LENGTH = 6  # command, result, progress, the low two bytes of result_param2
TARGET_SYSTEM_ONLY_LENGTH = 9  # everything but target_component
BROADCAST_LENGTH = 30  # the seven params and command
# time_boot_ms above Int.MAX_VALUE (0xEE6B2800): a uint32 read as a signed Int would come out negative.
LARGE_TIME_BOOT_MS = 4_000_000_000
# Distinct floats; all but yaw are exact in float32. yaw is packed as the float32 closest to -pi.
ATTITUDE_EDGES = {
    "roll": 0.5,
    "pitch": -0.25,
    "yaw": -3.141592653589793,
    "rollspeed": 1.5,
    "pitchspeed": -0.75,
    "yawspeed": 0.125,
}
# Negative int32 and int16 fields, and hdg UINT16_MAX ("If unknown, set to: UINT16_MAX", common.xml).
POSITION_EDGES = {
    "alt": -12345,
    "relative_alt": -500,
    "vx": -300,
    "vy": 1234,
    "vz": -150,
    "hdg": 0xFFFF,
}
VFR_HUD_CLIMB = -2.5
VFR_HUD_THROTTLE = 65
FULL_ATTITUDE_LENGTH = 28
FULL_GLOBAL_POSITION_INT_LENGTH = 28
VFR_HUD_EDGES_LENGTH = 19  # throttle 65 is 41 00: its zero high byte, last on the wire, is truncated
TRUNCATED_VFR_HUD_LENGTH = 18  # throttle (uint16) 0, last on the wire, is truncated
FLOAT32_DIGITS = 9  # enough significant digits to round-trip any float32
SYS_STATUS = "truncated/sys_status.bin"
# A distinct value in every field (a swapped offset shows), health_extended above Int.MAX_VALUE so a uint32
# read as a signed Int would come out negative. Not a real sensor set.
SYS_STATUS_EDGES = {
    "load": 500,
    "voltage_battery": 11100,
    "current_battery": 1550,
    "battery_remaining": 42,
    "drop_rate_comm": 250,
    "errors_comm": 1,
    "errors_count1": 2,
    "errors_count2": 3,
    "errors_count3": 4,
    "errors_count4": 5,
    "onboard_control_sensors_present_extended": 0x0102_0304,
    "onboard_control_sensors_enabled_extended": 0x0506_0708,
    "onboard_control_sensors_health_extended": 0x89AB_CDEF,
}
# "If unknown, set to" values of common.xml: UINT16_MAX and -1.
SYS_STATUS_UNKNOWN_BATTERY = {"voltage_battery": 0xFFFF, "current_battery": -1, "battery_remaining": -1}
FULL_SYS_STATUS_LENGTH = 43
TRUNCATED_SYS_STATUS_LENGTH = 31  # the three zero uint32 extensions are truncated
# A distinct value in every field; time_usec has 8 distinct bytes and h_acc is above Int.MAX_VALUE.
GPS_RAW_INT_EDGES = {
    "time_usec": 0x0123_4567_89AB_CDEF,
    "fix_type": 3,  # GPS_FIX_TYPE_3D_FIX
    "alt": -12345,
    "eph": 150,
    "epv": 250,
    "vel": 1234,
    "cog": 35999,
    "satellites_visible": 12,
    "alt_ellipsoid": -23456,
    "h_acc": 0x89AB_CDEF,
    "v_acc": 400,
    "vel_acc": 50,
    "hdg_acc": 12345,
    "yaw": 36000,  # "use 36000 for north" (common.xml), since 0 means unknown
}
# The "invalid" values of common.xml for every field that has one; yaw 0 is its own "unknown".
GPS_RAW_INT_UNKNOWN = {
    "fix_type": 0,  # GPS_FIX_TYPE_NO_GPS
    "eph": 0xFFFF,
    "epv": 0xFFFF,
    "vel": 0xFFFF,
    "cog": 0xFFFF,
    "satellites_visible": 0xFF,
    "alt_ellipsoid": 0,
    "h_acc": 0,
    "v_acc": 0,
    "vel_acc": 0,
    "hdg_acc": 0,
    "yaw": 0,
}
FULL_GPS_RAW_INT_LENGTH = 52
TRUNCATED_GPS_RAW_INT_LENGTH = 40  # v_acc 300 (2c 01 00 00) loses its two zero high bytes with what follows
GPS_RAW_INT_BASE_LENGTH = 30
INT8_MIN = -128  # 0x80: only a sign-extending read gives -128, not 128
TIME_USEC_2_63 = 2**63  # the smallest uint64 a Kotlin Long cannot hold
# MAV_SYS_STATUS_SENSOR_BATTERY (common.xml). ArduPilot always sets it in _present; _enabled only with a
# battery monitor, _health only while the monitor is healthy and not in failsafe (GCS.cpp, AP_BATTERY_ENABLED).
MAV_SYS_STATUS_SENSOR_BATTERY = 0x0200_0000
# Out of the documented ranges (#49): hdg and cog 0.0..359.99 degrees (standard.xml, common.xml), GPS yaw up to
# 36000 (north), throttle 0 to 100, ATTITUDE angles -pi..+pi; latitude and longitude by their WGS84 definition.
POSITION_HDG_PAST_RANGE = {"hdg": 36000}
POSITION_LAT_PAST_90 = {"lat": 900_000_001}
POSITION_LON_PAST_180 = {"lon": -1_800_000_001}
POSITION_LAT_INT32_MIN = {"lat": -(2**31)}
POSITION_LON_PAST_180_EAST = {"lon": 1_800_000_001}
POSITION_ENDS_SOUTH_EAST = {"lat": -900_000_000, "lon": 1_800_000_000}
POSITION_ENDS = {"lat": 900_000_000, "lon": -1_800_000_000, "hdg": 35999}
GPS_RAW_INT_OUT_OF_RANGE = {"cog": 40000, "yaw": 36001}
VFR_HUD_OUT_OF_RANGE = {"airspeed": float("nan"), "climb": float("inf"), "throttle": 101}
VFR_HUD_THROTTLE_MAX = {"throttle": 100}
ATTITUDE_OUT_OF_RANGE = {"roll": float("nan"), "pitch": float("-inf"), "yaw": 0.5}
ATTITUDE_PAST_PI = {"roll": math.pi, "pitch": -0.25, "yaw": 3.1416}
FLOAT32_PI = struct.unpack("<f", struct.pack("<f", math.pi))[0]
VEHICLE_SYSTEM_ID = 1
MAV_COMP_ID_AUTOPILOT1 = 1
# A distinct value in every field, exact in float32, negative latitude, longitude and altitude; time_usec has 8
# distinct bytes and a non-zero top one, the only way the extension keeps all 8 (any value below 2^56 is cut).
HOME_POSITION_FULL = {
    "latitude": -353633514,
    "longitude": -1491652411,
    "altitude": -12345,
    "x": 1.5,
    "y": -2.25,
    "z": 0.75,
    "q": [0.5, -0.25, 0.125, -0.0625],
    "approach_x": 3.5,
    "approach_y": -4.5,
    "approach_z": 5.25,
    "time_usec": 0x0102_0304_0506_0708,
}
# approach_z -1.5 is 00 00 c0 bf: its top byte keeps the base fields whole while the zero extension goes.
HOME_POSITION_NO_EXTENSION = {"time_usec": 0, "approach_z": -1.5}
HOME_POSITION_CUT_AFTER_Q = {"time_usec": 0}
FULL_HOME_POSITION_LENGTH = 60
HOME_POSITION_BASE_LENGTH = 52
TRUNCATED_HOME_POSITION_LENGTH = 56  # time_usec 443979837 fits 4 bytes: its 4 zero high bytes are truncated
HOME_POSITION_AFTER_Q_LENGTH = 40  # ArduPilot's NaN q ends in 7f; the zero approach and time_usec go
# Home set again elsewhere, past the WGS84 ranges, and at 0°, 0°, which ArduPilot never sets as home (#69).
HOME_POSITION_MOVED = {"latitude": -353624514, "longitude": 1491663411, "altitude": 590130}
HOME_POSITION_LAT_PAST_90 = {"latitude": 900_000_001}
HOME_POSITION_LAT_INT32_MIN = {"latitude": -(2**31)}
HOME_POSITION_NULL_ISLAND = {"latitude": 0, "longitude": 0}
# ArduCopter 4.7.2 (tag Copter-4.7.2): AP_Arming::check_failed sends "PreArm: %s" or, while arming, "Arm: %s" at
# CRITICAL; AP_Arming_Copter.cpp has "RC not found" and "%s mode not armable", ModeRTL::name() is "RTL".
ARMING_MOTORS = "Arming motors"
PREARM_TEXT = b"PreArm: RC not found"
ARM_TEXT = b"Arm: RTL mode not armable"
FULL_TEXT = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwx"
CHUNKED_TEXT = b"A STATUSTEXT longer than fifty bytes goes out in chunks of fifty"
NUL_INSIDE_TEXT = b"ab\x00cd"
UTF8_TEXT = b"caf\xc3\xa9 \xff ok"  # U+00E9 as two bytes, then 0xFF, never valid in UTF-8
TEXT_LENGTH = 50
CHUNK_ID = 1
FULL_TEXT_ID = 0x1234
FULL_TEXT_CHUNK_SEQ = 7
FULL_STATUSTEXT_LENGTH = 54
UTF8_DECODED = "caf\u00e9 \ufffd ok"
OUTSIDE_SEVERITY = 8
FIRST_CHUNK_LENGTH = 52  # id 1 is 01 00: its zero high byte goes with the zero chunk_seq
# A corrupted LEN, the checksum left as it was: the largest LEN makes a receiver wait for a maximum frame, the
# smallest valid one cuts the frame short.
CORRUPTED_LEN_RAISED = 255
CORRUPTED_LEN_LOWERED = 1
SESSION_MAVLINK2_FRAMES = 5522
MISSION_PLANNER_LOG = "tlog/mission-planner.tlog"
# A take off to 10 m and a LAND in ArduCopter 4.7.2 SITL, recorded by pymavlink on SITL's TCP 5763 (#112).
LANDED_SESSION = "landed/session.tlog"
LANDED_STATES = {
    common.MAV_LANDED_STATE_ON_GROUND: "on-ground",
    common.MAV_LANDED_STATE_TAKEOFF: "takeoff",
    common.MAV_LANDED_STATE_IN_AIR: "in-air",
    common.MAV_LANDED_STATE_LANDING: "landing",
}
OUTSIDE_LANDED_STATE = common.MAV_LANDED_STATE_ENUM_END
PARAM_ID_LENGTH = 16
FULL_PARAM_VALUE_LENGTH = 25
FULL_PARAM_REQUEST_READ_LENGTH = 20
PARAM_REQUEST_READ_ID_OFFSET = 4  # param_index (int16), target_system, target_component, then param_id
PARAM_VALUE_ID_OFFSET = 8  # param_value (float), param_count, param_index (uint16), then param_id
PARAM_INDEX_BY_NAME = -1  # "-1 to use the param ID field as identifier (else the param id will be ignored)"
# ArduCopter 4.7.2's BATT_LOW_VOLT default, as in tlog/mission-planner.tlog's parameter download (MAVLink 1 there).
BATT_LOW_VOLT = {"param_id": b"BATT_LOW_VOLT", "param_value": 10.5, "param_type": common.MAV_PARAM_TYPE_REAL32}
# A distinct non-zero byte in every field (79 e9 f6 c2, 34 12, 78 56): a swapped offset or byte order shows.
PARAM_VALUE_FIELDS = {
    "param_id": b"ABCDEFGHIJKLMNOP",
    "param_value": -123.456,
    "param_type": common.MAV_PARAM_TYPE_UINT32,
    "param_count": 0x1234,
    "param_index": 0x5678,
}
PARAM_VALUE_NUL_INSIDE = {"param_id": b"AB\x00CD"}
PARAM_VALUE_TYPE_0 = {"param_id": b"BATT_LOW_VOLT", "param_type": 0}  # 0 is outside MAV_PARAM_TYPE
PARAM_VALUE_EMPTY_ID = {"param_id": b"", "param_type": 0}
PARAM_REQUEST_SEQ = 42
SIXTEEN_CHAR_NAME = b"INS_GYR1_CALTEMP"
ONE_CHAR_NAME = b"A"


def decode(frame: bytes, key: bytes = None):
    """Decodes one frame with pymavlink; returns the message or raises if pymavlink rejects it."""
    mav = common.MAVLink(io.BytesIO())
    if key is not None:
        mav.signing.secret_key = key
    msgs = mav.parse_buffer(frame)
    assert msgs and len(msgs) == 1, f"pymavlink did not decode exactly one message: {msgs}"
    return msgs[0]


def with_flags(frame: bytes, index: int, value: int) -> bytes:
    """Sets one flags byte and recomputes the checksum with pymavlink's x25crc and CRC_EXTRA."""
    out = bytearray(frame)
    out[index] = value
    length = out[1]
    msgid = int.from_bytes(out[7:10], "little")
    crc = x25crc(bytes(out[1:HEADER_LENGTH + length]))
    crc.accumulate(bytes([common.mavlink_map[msgid].crc_extra]))
    end = HEADER_LENGTH + length
    out[end:end + CHECKSUM_LENGTH] = crc.crc.to_bytes(CHECKSUM_LENGTH, "little")
    return bytes(out)


def empty_payload(frame: bytes) -> bytes:
    """The same header with LEN 0 and no payload, checksum recomputed by pymavlink (an invalid frame:
    the first payload byte is never truncated)."""
    header = bytearray(frame[:HEADER_LENGTH])
    header[1] = 0
    crc = x25crc(bytes(header[1:]))
    crc.accumulate(bytes([common.mavlink_map[int.from_bytes(header[7:10], "little")].crc_extra]))
    return bytes(header) + crc.crc.to_bytes(CHECKSUM_LENGTH, "little")


def extra_byte(frame: bytes) -> bytes:
    """The same frame with one unknown extension byte appended to the payload (LEN + 1), checksum
    recomputed by pymavlink; a receiver without that extension ignores it."""
    length = frame[1]
    out = bytearray(frame[:HEADER_LENGTH + length]) + bytes([UNKNOWN_EXTENSION_BYTE])
    out[1] = length + 1
    msgid = int.from_bytes(out[7:10], "little")
    crc = x25crc(bytes(out[1:]))
    crc.accumulate(bytes([common.mavlink_map[msgid].crc_extra]))
    return bytes(out) + crc.crc.to_bytes(CHECKSUM_LENGTH, "little")


def max_custom_mode(frame: bytes) -> bytes:
    """The HEARTBEAT re-packed by pymavlink with custom_mode = 0xFFFFFFFF (largest uint32)."""
    msg = decode(frame)
    mav = common.MAVLink(io.BytesIO(), srcSystem=msg.get_srcSystem(), srcComponent=msg.get_srcComponent())
    mav.seq = msg.get_seq()
    copy = common.MAVLink_heartbeat_message(
        msg.type, msg.autopilot, msg.base_mode, MAX_UINT32, msg.system_status, msg.mavlink_version
    )
    return bytes(copy.pack(mav))


def with_fields(frame: bytes, **fields) -> bytes:
    """The HEARTBEAT re-packed by pymavlink with some fields replaced; seq, system and component unchanged.
    pymavlink truncates the trailing zero bytes of a MAVLink 2 payload, as the specification requires."""
    msg = decode(frame)
    mav = common.MAVLink(io.BytesIO(), srcSystem=msg.get_srcSystem(), srcComponent=msg.get_srcComponent())
    mav.seq = msg.get_seq()
    values = {name: getattr(msg, name) for name in msg.get_fieldnames()}
    values.update(fields)
    copy = common.MAVLink_heartbeat_message(
        values["type"],
        values["autopilot"],
        values["base_mode"],
        values["custom_mode"],
        values["system_status"],
        values["mavlink_version"],
    )
    return bytes(copy.pack(mav))


def with_system_id(frame: bytes, system_id: int) -> bytes:
    """The HEARTBEAT re-packed by pymavlink with another system ID; fields, seq and component unchanged."""
    return repacked(frame, system_id=system_id, component_id=decode(frame).get_srcComponent())


def with_component_id(frame: bytes, component_id: int) -> bytes:
    """The HEARTBEAT re-packed by pymavlink from another component; fields, seq and system unchanged."""
    return repacked(frame, system_id=decode(frame).get_srcSystem(), component_id=component_id)


def repacked(frame: bytes, system_id: int, component_id: int) -> bytes:
    """The HEARTBEAT re-packed by pymavlink with the given sender; fields and seq unchanged."""
    msg = decode(frame)
    mav = common.MAVLink(io.BytesIO(), srcSystem=system_id, srcComponent=component_id)
    mav.seq = msg.get_seq()
    copy = common.MAVLink_heartbeat_message(
        msg.type, msg.autopilot, msg.base_mode, msg.custom_mode, msg.system_status, msg.mavlink_version
    )
    return bytes(copy.pack(mav))


def with_message_fields(frame: bytes, **fields) -> bytes:
    """Any message re-packed by pymavlink with some fields replaced (extensions included); seq, system and
    component unchanged. pymavlink truncates the trailing zero bytes of a MAVLink 2 payload."""
    msg = decode(frame)
    mav = common.MAVLink(io.BytesIO(), srcSystem=msg.get_srcSystem(), srcComponent=msg.get_srcComponent())
    mav.seq = msg.get_seq()
    values = {name: getattr(msg, name) for name in msg.get_fieldnames()}
    values.update(fields)
    copy = type(msg)(*[values[name] for name in msg.get_fieldnames()])
    return bytes(copy.pack(mav))


def statustext_with(frame: bytes, text: bytes, severity: int = None, text_id: int = 0, chunk_seq: int = 0) -> bytes:
    """The STATUSTEXT re-packed by pymavlink with another text, given as bytes since pymavlink packs a char array
    from bytes; seq, system and component unchanged, the severity too unless given."""
    msg = decode(frame)
    mav = common.MAVLink(io.BytesIO(), srcSystem=msg.get_srcSystem(), srcComponent=msg.get_srcComponent())
    mav.seq = msg.get_seq()
    copy = common.MAVLink_statustext_message(
        msg.severity if severity is None else severity, text, text_id, chunk_seq
    )
    return bytes(copy.pack(mav))


def statustext_table(log_path: Path) -> str:
    """Every MAVLink 2 STATUSTEXT of the log as pymavlink decodes it, one per line (tab-separated, header first).
    ends_in_field comes from the wire bytes: a NUL in the text field, or a payload cut before its end."""
    log = mavutil.mavlink_connection(str(log_path), dialect="common", robust_parsing=True)
    lines = ["severity\ttext\tid\tchunk_seq\tends_in_field"]
    while True:
        msg = log.recv_match(type="STATUSTEXT", blocking=False)
        if msg is None:
            return "\n".join(lines) + "\n"
        frame = bytes(msg.get_msgbuf())
        if frame[0] != START_MARKER:
            continue
        field = frame[HEADER_LENGTH + 1:HEADER_LENGTH + min(frame[1], 1 + TEXT_LENGTH)]
        ends_in_field = len(field) < TEXT_LENGTH or 0 in field
        assert "\t" not in msg.text and msg.text.isascii(), "the table holds plain ASCII texts only"
        lines.append(f"{msg.severity}\t{msg.text}\t{msg.id}\t{msg.chunk_seq}\t{str(ends_in_field).lower()}")


def param_request_read(name: bytes) -> bytes:
    """A PARAM_REQUEST_READ by name from the GCS to the vehicle's autopilot, built by pymavlink."""
    mav = common.MAVLink(io.BytesIO(), srcSystem=GCS_SYSTEM_ID, srcComponent=MAV_COMP_ID_MISSIONPLANNER)
    mav.seq = PARAM_REQUEST_SEQ
    msg = mav.param_request_read_encode(VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, name, PARAM_INDEX_BY_NAME)
    return bytes(msg.pack(mav))


def param_value_table(log_path: Path) -> str:
    """Every MAVLink 2 PARAM_VALUE of the log as pymavlink decodes it, one per line (tab-separated, header first),
    param_value as float32 text."""
    log = mavutil.mavlink_connection(str(log_path), dialect="common", robust_parsing=True)
    lines = ["param_id\tparam_value\tparam_type\tparam_count\tparam_index"]
    while True:
        msg = log.recv_match(type="PARAM_VALUE", blocking=False)
        if msg is None:
            return "\n".join(lines) + "\n"
        if msg.get_msgbuf()[0] != START_MARKER:
            continue
        assert "\t" not in msg.param_id and msg.param_id.isascii(), "the table holds plain ASCII ids only"
        value = float32_text(msg.param_value)
        lines.append(f"{msg.param_id}\t{value}\t{msg.param_type}\t{msg.param_count}\t{msg.param_index}")


def session_frame(session: Path, message_type: str, matches) -> bytes:
    """First real MAVLink 2 frame of the session of [message_type] for which [matches] is true."""
    log = mavutil.mavlink_connection(str(session), dialect="common", robust_parsing=True)
    while True:
        msg = log.recv_match(type=message_type, blocking=False)
        if msg is None:
            raise SystemExit(f"no matching {message_type} in the session")
        frame = bytes(msg.get_msgbuf())
        if frame[0] == START_MARKER and matches(msg):
            return frame


def gcs_heartbeat(session: Path) -> bytes:
    """First real MAVLink 2 HEARTBEAT of the session sent by a ground station (MAV_TYPE_GCS)."""
    log = mavutil.mavlink_connection(str(session), dialect="common", robust_parsing=True)
    while True:
        msg = log.recv_match(type="HEARTBEAT", blocking=False)
        if msg is None:
            raise SystemExit("no GCS HEARTBEAT in the session")
        frame = bytes(msg.get_msgbuf())
        if frame[0] == START_MARKER and msg.type == mavutil.mavlink.MAV_TYPE_GCS:
            return frame


def signed(frame: bytes) -> bytes:
    """Re-packs the decoded message with the same header fields and fields, signed by pymavlink."""
    msg = decode(frame)
    mav = common.MAVLink(io.BytesIO(), srcSystem=msg.get_srcSystem(), srcComponent=msg.get_srcComponent())
    mav.seq = msg.get_seq()
    mav.signing.secret_key = TEST_KEY
    mav.signing.link_id = TEST_LINK_ID
    mav.signing.timestamp = TEST_TIMESTAMP
    mav.signing.sign_outgoing = True
    return bytes(msg.pack(mav))


def unsigned(frame: bytes) -> bytes:
    """Re-packs a signed frame's message with the same header fields and fields, without signing it."""
    msg = decode(frame, key=TEST_KEY)
    mav = common.MAVLink(io.BytesIO(), srcSystem=msg.get_srcSystem(), srcComponent=msg.get_srcComponent())
    mav.seq = msg.get_seq()
    return bytes(msg.pack(mav))


def setup_signing() -> bytes:
    """A SETUP_SIGNING from the GCS to the vehicle carrying the test key, built by pymavlink."""
    mav = common.MAVLink(io.BytesIO(), srcSystem=GCS_SYSTEM_ID, srcComponent=MAV_COMP_ID_MISSIONPLANNER)
    msg = mav.setup_signing_encode(VEHICLE_SYSTEM_ID, common.MAV_COMP_ID_AUTOPILOT1, TEST_KEY, TEST_TIMESTAMP)
    return bytes(msg.pack(mav))


def attitude_with_fd(session: Path) -> bytes:
    """First real MAVLink 2 ATTITUDE frame of the session whose payload contains 0xFD."""
    log = mavutil.mavlink_connection(str(session), dialect="common", robust_parsing=True)
    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            raise SystemExit("no ATTITUDE frame with 0xFD in its payload")
        if msg.get_type() == "BAD_DATA" or msg.get_msgId() != ATTITUDE_ID:
            continue
        frame = bytes(msg.get_msgbuf())
        if frame[0] == START_MARKER and START_MARKER in frame[HEADER_LENGTH:HEADER_LENGTH + frame[1]]:
            return frame


def float32_text(value: float) -> str:
    """The shortest decimal text that parses back to the same float32 as [value]."""
    bits = struct.pack("<f", value)
    for digits in range(1, FLOAT32_DIGITS + 1):
        text = f"{value:.{digits}g}"
        if struct.pack("<f", float(text)) == bits:
            return text
    raise AssertionError(f"no float32 text for {value}")


def fields_text(frame: bytes) -> str:
    """The fields of [frame] as pymavlink decodes them, floats as float32 text (each element of a float array)."""
    msg = decode(frame)
    values = []
    for name, kind in zip(msg.get_fieldnames(), msg.fieldtypes):
        value = getattr(msg, name)
        if kind == "float" and isinstance(value, list):
            text = "[" + ", ".join(float32_text(element) for element in value) + "]"
        else:
            text = float32_text(value) if kind == "float" else value
        values.append(f"{name}={text}")
    return ", ".join(values)


def session_stream(session: Path) -> bytes:
    """Every frame of the session in order, as on the wire, without the .tlog timestamps."""
    log = mavutil.mavlink_connection(str(session), dialect="ardupilotmega", robust_parsing=True)
    frames = []
    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            return b"".join(frames)
        assert msg.get_type() != "BAD_DATA", "the session must decode cleanly"
        frames.append(bytes(msg.get_msgbuf()))


def session_mavlink2_stream(session: Path) -> bytes:
    """The session's MAVLink 2 frames only, in order, as on the wire, without the .tlog timestamps."""
    log = mavutil.mavlink_connection(str(session), dialect="ardupilotmega", robust_parsing=True)
    frames = []
    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            return b"".join(frames)
        frame = bytes(msg.get_msgbuf())
        if frame[0] == START_MARKER:
            frames.append(frame)


def with_length(frame: bytes, length: int) -> bytes:
    """[frame] with its LEN byte replaced and nothing else changed: the checksum no longer matches."""
    out = bytearray(frame)
    out[1] = length
    return bytes(out)


def rejected_then_decoded(corrupted: bytes, following: bytes) -> list:
    """What pymavlink makes of [corrupted] followed by [following], with robust parsing. It only proves the
    corrupted frame is invalid (it comes out as BAD_DATA, never as a message): pymavlink drops every byte the
    corrupted LEN claims, so the real frames it recovers are not a reference for our parser's rescan."""
    mav = common.MAVLink(io.BytesIO())
    mav.robust_parsing = True
    return mav.parse_buffer(corrupted + following) or []


def session_counts(session: Path) -> dict:
    """The counts the parser tests expect from the session, computed by pymavlink."""
    log = mavutil.mavlink_connection(str(session), dialect="ardupilotmega", robust_parsing=True)
    counts = {"frames": 0, "mavlink2": 0, "heartbeat": 0, "mavlink1_bytes": 0}
    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            counts["unknown_with_heartbeat_only_lookup"] = counts["mavlink2"] - counts["heartbeat"]
            known = sum(v for k, v in counts.items() if k.startswith("table_"))
            counts["unknown_with_full_table"] = counts["mavlink2"] - known
            return counts
        frame = bytes(msg.get_msgbuf())
        counts["frames"] += 1
        if frame[0] == START_MARKER:
            counts["mavlink2"] += 1
            counts["heartbeat"] += msg.get_msgId() == common.MAVLINK_MSG_ID_HEARTBEAT
            if msg.get_msgId() in TABLE_MESSAGES:
                key = f"table_{TABLE_MESSAGES[msg.get_msgId()]}"
                counts[key] = counts.get(key, 0) + 1
        else:
            counts["mavlink1_bytes"] += len(frame)


def same(decoded, expected) -> bool:
    """[decoded] equals [expected] as pymavlink packs it: NaN for NaN, float32 for floats, element by element
    for arrays."""
    if isinstance(expected, list):
        return len(decoded) == len(expected) and all(same(d, e) for d, e in zip(decoded, expected))
    if isinstance(expected, float):
        if math.isnan(expected):
            return math.isnan(decoded)
        return decoded == struct.unpack("<f", struct.pack("<f", expected))[0]
    return decoded == expected


def session_telemetry_counts(session: Path) -> dict:
    """For every MAVLink 2 telemetry frame of the vehicle's autopilot in the session, how many carry each value
    the app maps, a documented "unknown" (common.xml, standard.xml) not counted. Our ranges are not applied:
    this is what the mapper must keep from a real flight (#49)."""
    log = mavutil.mavlink_connection(str(session), dialect="common", robust_parsing=True)
    counts = {}

    def count(key: str, has_value: bool = True) -> None:
        counts[key] = counts.get(key, 0) + has_value

    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            return counts
        frame = bytes(msg.get_msgbuf())
        if frame[0] != START_MARKER or msg.get_srcSystem() != VEHICLE_SYSTEM_ID:
            continue
        if msg.get_srcComponent() != MAV_COMP_ID_AUTOPILOT1:
            continue
        kind = msg.get_type()
        if kind == "GLOBAL_POSITION_INT":
            count("position")
            count("heading", msg.hdg != 0xFFFF)
        elif kind == "ATTITUDE":
            for name in ("roll", "pitch", "yaw"):
                count(name)
        elif kind == "VFR_HUD":
            for name in ("airspeed", "groundspeed", "climb", "throttle"):
                count(name)
        elif kind == "GPS_RAW_INT":
            count("gps")
            count("cog", msg.cog != 0xFFFF)
            count("gps_yaw", msg.yaw not in (0, 0xFFFF))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=Path("core/mavlink/src/test/resources"))
    args = parser.parse_args()

    source = (args.out / SOURCE).read_bytes()
    derived_dir = args.out / "derived"
    unknown_dir = args.out / "unknown"
    gcs_dir = args.out / "gcs"
    derived_dir.mkdir(parents=True, exist_ok=True)
    unknown_dir.mkdir(parents=True, exist_ok=True)
    gcs_dir.mkdir(parents=True, exist_ok=True)
    command_dir = args.out / "command"
    command_dir.mkdir(parents=True, exist_ok=True)
    telemetry_dir = args.out / "telemetry"
    telemetry_dir.mkdir(parents=True, exist_ok=True)
    statustext_dir = args.out / "statustext"
    statustext_dir.mkdir(parents=True, exist_ok=True)
    param_dir = args.out / "param"
    param_dir.mkdir(parents=True, exist_ok=True)
    landed_dir = args.out / "landed"
    landed = {
        state: session_frame(args.out / LANDED_SESSION, "EXTENDED_SYS_STATE", lambda m, s=state: m.landed_state == s)
        for state in LANDED_STATES
    }
    on_ground = landed[common.MAV_LANDED_STATE_ON_GROUND]
    session = args.out / SESSION
    mission_planner_log = args.out / MISSION_PLANNER_LOG
    stat_runtime = session_frame(session, "PARAM_VALUE", lambda m: m.param_id == "STAT_RUNTIME")
    hover = session_frame(session, "PARAM_VALUE", lambda m: m.param_type == common.MAV_PARAM_TYPE_REAL32)
    sixteen_chars = session_frame(
        mission_planner_log, "PARAM_VALUE", lambda m: len(m.param_id) == PARAM_ID_LENGTH
    )
    arming_motors = session_frame(session, "STATUSTEXT", lambda m: m.text == ARMING_MOTORS)
    interval = session_frame(session, "COMMAND_LONG", lambda m: m.command == SET_MESSAGE_INTERVAL)
    interval_ack = session_frame(session, "COMMAND_ACK", lambda m: m.command == SET_MESSAGE_INTERVAL)
    no_extensions = {"progress": 0, "result_param2": 0, "target_system": 0, "target_component": 0}
    attitude = session_frame(session, "ATTITUDE", lambda m: True)
    position = session_frame(session, "GLOBAL_POSITION_INT", lambda m: m.vy < 0)
    vfr_hud = session_frame(session, "VFR_HUD", lambda m: True)
    gps_raw_int = session_frame(session, "GPS_RAW_INT", lambda m: True)
    sys_status = (args.out / SYS_STATUS).read_bytes()
    battery = session_frame(session, "SYS_STATUS", lambda m: m.voltage_battery != 0)
    battery_msg = decode(battery)
    no_monitor = {
        "onboard_control_sensors_enabled": battery_msg.onboard_control_sensors_enabled & ~MAV_SYS_STATUS_SENSOR_BATTERY,
        "voltage_battery": 0,
        "current_battery": -1,
        "battery_remaining": -1,
    }
    unhealthy = {
        "onboard_control_sensors_health": battery_msg.onboard_control_sensors_health & ~MAV_SYS_STATUS_SENSOR_BATTERY,
    }
    home_position = session_frame(session, "HOME_POSITION", lambda m: True)
    scaled_pressure = session_frame(session, "SCALED_PRESSURE", lambda m: True)

    outputs = {
        derived_dir / "heartbeat-signed.bin": signed(source),
        derived_dir / "heartbeat-unknown-incompat.bin": with_flags(source, INCOMPAT_INDEX, UNKNOWN_INCOMPAT),
        derived_dir / "heartbeat-unknown-compat.bin": with_flags(source, COMPAT_INDEX, UNKNOWN_COMPAT),
        derived_dir / "heartbeat-empty-payload.bin": empty_payload(source),
        derived_dir / "heartbeat-extra-byte.bin": extra_byte(source),
        derived_dir / "heartbeat-max-custom-mode.bin": max_custom_mode(source),
        derived_dir / "heartbeat-asymmetric-custom-mode.bin": with_fields(source, custom_mode=ASYMMETRIC_CUSTOM_MODE),
        derived_dir / "heartbeat-trailing-zeros.bin": with_fields(
            source, custom_mode=LOITER_MODE, base_mode=0, system_status=0, mavlink_version=0
        ),
        derived_dir / "heartbeat-all-zero.bin": with_fields(
            source, type=0, autopilot=0, base_mode=0, custom_mode=0, system_status=0, mavlink_version=0
        ),
        derived_dir / "heartbeat-sysid-2.bin": with_system_id((args.out / LOITER).read_bytes(), SECOND_SYSTEM_ID),
        derived_dir / "heartbeat-gimbal-component.bin": with_component_id(source, MAV_COMP_ID_GIMBAL),
        gcs_dir / "heartbeat-mission-planner.bin": gcs_heartbeat(args.out / SESSION),
        unknown_dir / "attitude-fd-in-payload.bin": attitude_with_fd(args.out / SESSION),
        derived_dir / "attitude-signed.bin": signed(attitude_with_fd(args.out / SESSION)),
        derived_dir / "vfr-hud-signed.bin": signed(vfr_hud),
        derived_dir / "scaled-pressure-signed.bin": signed(scaled_pressure),
        derived_dir / "setup-signing.bin": setup_signing(),
        derived_dir / "setup-signing-signed.bin": signed(setup_signing()),
        derived_dir / "session-stream.bin": session_stream(args.out / SESSION),
        command_dir / "set-message-interval.bin": interval,
        command_dir / "set-message-interval-ack.bin": interval_ack,
        command_dir / "command-long-confirmation.bin": session_frame(
            session, "COMMAND_LONG", lambda m: m.confirmation != 0
        ),
        derived_dir / "command-long-all-params.bin": with_message_fields(
            interval,
            **{f"param{index + 1}": value for index, value in enumerate(ALL_PARAMS)},
            command=ARBITRARY_COMMAND,
            confirmation=ARBITRARY_CONFIRMATION,
        ),
        derived_dir / "command-ack-extensions.bin": with_message_fields(
            interval_ack,
            result=common.MAV_RESULT_IN_PROGRESS,
            progress=IN_PROGRESS_PROGRESS,
            result_param2=NEGATIVE_RESULT_PARAM2,
        ),
        derived_dir / "command-ack-accepted-no-extensions.bin": with_message_fields(
            interval_ack, result=common.MAV_RESULT_ACCEPTED, **no_extensions
        ),
        derived_dir / "command-ack-failed-no-extensions.bin": with_message_fields(
            interval_ack, result=common.MAV_RESULT_FAILED, **no_extensions
        ),
        derived_dir / "command-ack-split-param2.bin": with_message_fields(
            interval_ack, result_param2=SPLIT_RESULT_PARAM2, target_system=0, target_component=0
        ),
        derived_dir / "command-ack-target-system-only.bin": with_message_fields(interval_ack, target_component=0),
        derived_dir / "command-long-broadcast.bin": with_message_fields(interval, target_system=0, target_component=0),
        telemetry_dir / "attitude.bin": attitude,
        telemetry_dir / "global-position-int.bin": position,
        telemetry_dir / "vfr-hud.bin": vfr_hud,
        unknown_dir / "scaled-pressure.bin": scaled_pressure,
        derived_dir / "attitude-edges.bin": with_message_fields(
            attitude, time_boot_ms=LARGE_TIME_BOOT_MS, **ATTITUDE_EDGES
        ),
        derived_dir / "global-position-int-edges.bin": with_message_fields(
            position, time_boot_ms=LARGE_TIME_BOOT_MS, **POSITION_EDGES
        ),
        derived_dir / "vfr-hud-edges.bin": with_message_fields(vfr_hud, climb=VFR_HUD_CLIMB, throttle=VFR_HUD_THROTTLE),
        telemetry_dir / "gps-raw-int.bin": gps_raw_int,
        telemetry_dir / "sys-status.bin": battery,
        derived_dir / "sys-status-edges.bin": with_message_fields(sys_status, **SYS_STATUS_EDGES),
        derived_dir / "sys-status-unknown-battery.bin": with_message_fields(sys_status, **SYS_STATUS_UNKNOWN_BATTERY),
        derived_dir / "gps-raw-int-edges.bin": with_message_fields(gps_raw_int, **GPS_RAW_INT_EDGES),
        derived_dir / "gps-raw-int-unknown.bin": with_message_fields(gps_raw_int, **GPS_RAW_INT_UNKNOWN),
        derived_dir / "sys-status-battery-remaining-min.bin": with_message_fields(battery, battery_remaining=INT8_MIN),
        derived_dir / "gps-raw-int-time-2-63.bin": with_message_fields(gps_raw_int, time_usec=TIME_USEC_2_63),
        derived_dir / "sys-status-battery-zero-volts.bin": with_message_fields(battery, voltage_battery=0),
        derived_dir / "sys-status-no-battery-monitor.bin": with_message_fields(battery, **no_monitor),
        derived_dir / "sys-status-battery-unhealthy.bin": with_message_fields(battery, **unhealthy),
        derived_dir / "global-position-int-hdg-36000.bin": with_message_fields(position, **POSITION_HDG_PAST_RANGE),
        derived_dir / "global-position-int-lat-past-90.bin": with_message_fields(position, **POSITION_LAT_PAST_90),
        derived_dir / "global-position-int-lon-past-180.bin": with_message_fields(position, **POSITION_LON_PAST_180),
        derived_dir / "global-position-int-lat-int32-min.bin": with_message_fields(position, **POSITION_LAT_INT32_MIN),
        derived_dir / "global-position-int-lon-past-180-east.bin": with_message_fields(
            position, **POSITION_LON_PAST_180_EAST
        ),
        derived_dir / "global-position-int-ends-south-east.bin": with_message_fields(position, **POSITION_ENDS_SOUTH_EAST),
        derived_dir / "global-position-int-ends.bin": with_message_fields(position, **POSITION_ENDS),
        derived_dir / "gps-raw-int-out-of-range.bin": with_message_fields(gps_raw_int, **GPS_RAW_INT_OUT_OF_RANGE),
        derived_dir / "vfr-hud-out-of-range.bin": with_message_fields(vfr_hud, **VFR_HUD_OUT_OF_RANGE),
        derived_dir / "vfr-hud-throttle-100.bin": with_message_fields(vfr_hud, **VFR_HUD_THROTTLE_MAX),
        derived_dir / "attitude-out-of-range.bin": with_message_fields(attitude, **ATTITUDE_OUT_OF_RANGE),
        derived_dir / "attitude-past-pi.bin": with_message_fields(attitude, **ATTITUDE_PAST_PI),
        telemetry_dir / "home-position.bin": home_position,
        derived_dir / "home-position-full.bin": with_message_fields(home_position, **HOME_POSITION_FULL),
        derived_dir / "home-position-no-extension.bin": with_message_fields(
            home_position, **HOME_POSITION_NO_EXTENSION
        ),
        derived_dir / "home-position-cut-after-q.bin": with_message_fields(
            home_position, **HOME_POSITION_CUT_AFTER_Q
        ),
        derived_dir / "home-position-moved.bin": with_message_fields(home_position, **HOME_POSITION_MOVED),
        derived_dir / "home-position-lat-past-90.bin": with_message_fields(home_position, **HOME_POSITION_LAT_PAST_90),
        derived_dir / "home-position-lat-int32-min.bin": with_message_fields(
            home_position, **HOME_POSITION_LAT_INT32_MIN
        ),
        derived_dir / "home-position-null-island.bin": with_message_fields(home_position, **HOME_POSITION_NULL_ISLAND),
        statustext_dir / "arming-motors.bin": arming_motors,
        derived_dir / "statustext-prearm.bin": statustext_with(
            arming_motors, PREARM_TEXT, severity=common.MAV_SEVERITY_CRITICAL
        ),
        derived_dir / "statustext-arm.bin": statustext_with(arming_motors, ARM_TEXT, severity=common.MAV_SEVERITY_CRITICAL),
        derived_dir / "statustext-full.bin": statustext_with(
            arming_motors, FULL_TEXT, common.MAV_SEVERITY_WARNING, FULL_TEXT_ID, FULL_TEXT_CHUNK_SEQ
        ),
        derived_dir / "statustext-chunk-1.bin": statustext_with(
            arming_motors, CHUNKED_TEXT[:TEXT_LENGTH], text_id=CHUNK_ID, chunk_seq=0
        ),
        derived_dir / "statustext-chunk-2.bin": statustext_with(
            arming_motors, CHUNKED_TEXT[TEXT_LENGTH:], text_id=CHUNK_ID, chunk_seq=1
        ),
        derived_dir / "statustext-nul-inside.bin": statustext_with(arming_motors, NUL_INSIDE_TEXT),
        derived_dir / "statustext-utf8.bin": statustext_with(arming_motors, UTF8_TEXT),
        derived_dir / "statustext-empty.bin": statustext_with(arming_motors, b"", severity=common.MAV_SEVERITY_EMERGENCY),
        derived_dir / "statustext-severity-8.bin": statustext_with(arming_motors, ARMING_MOTORS.encode(), OUTSIDE_SEVERITY),
        statustext_dir / "session.tsv": statustext_table(session).encode(),
        statustext_dir / "mission-planner.tsv": statustext_table(args.out / "tlog" / "mission-planner.tlog").encode(),
        param_dir / "stat-runtime.bin": stat_runtime,
        param_dir / "mot-thst-hover.bin": hover,
        param_dir / "ins-gyr1-caltemp.bin": sixteen_chars,
        derived_dir / "param-value-batt-low-volt.bin": with_message_fields(hover, **BATT_LOW_VOLT),
        derived_dir / "param-value-fields.bin": with_message_fields(hover, **PARAM_VALUE_FIELDS),
        derived_dir / "param-value-nul-inside.bin": with_message_fields(hover, **PARAM_VALUE_NUL_INSIDE),
        derived_dir / "param-value-type-0.bin": with_message_fields(hover, **PARAM_VALUE_TYPE_0),
        derived_dir / "param-value-empty-id.bin": with_message_fields(hover, **PARAM_VALUE_EMPTY_ID),
        derived_dir / "param-request-read-batt-low-volt.bin": param_request_read(BATT_LOW_VOLT["param_id"]),
        derived_dir / "param-request-read-16-chars.bin": param_request_read(SIXTEEN_CHAR_NAME),
        derived_dir / "param-request-read-1-char.bin": param_request_read(ONE_CHAR_NAME),
        param_dir / "session.tsv": param_value_table(session).encode(),
        param_dir / "mission-planner.tsv": param_value_table(mission_planner_log).encode(),
        **{landed_dir / f"{name}.bin": landed[state] for state, name in LANDED_STATES.items()},
        derived_dir / "extended-sys-state-undefined.bin": with_message_fields(
            on_ground, landed_state=common.MAV_LANDED_STATE_UNDEFINED
        ),
        derived_dir / "extended-sys-state-landed-5.bin": with_message_fields(on_ground, landed_state=OUTSIDE_LANDED_STATE),
        derived_dir / "session-mavlink2-stream.bin": session_mavlink2_stream(session),
        derived_dir / "heartbeat-corrupted-len-raised.bin": with_length(source, CORRUPTED_LEN_RAISED),
        derived_dir / "heartbeat-corrupted-len-lowered.bin": with_length(source, CORRUPTED_LEN_LOWERED),
    }

    for name in (
        "heartbeat-signed.bin",
        "attitude-signed.bin",
        "vfr-hud-signed.bin",
        "scaled-pressure-signed.bin",
        "setup-signing-signed.bin",
    ):
        signed_msg = decode(outputs[derived_dir / name], key=TEST_KEY)
        assert signed_msg.get_signed() and signed_msg._link_id == TEST_LINK_ID, f"pymavlink did not verify {name}"
    # What the log must hold for each signed frame (ADR 0006): its message unsigned, the same bytes as the original.
    for name, original in (
        ("heartbeat-signed.bin", source),
        ("attitude-signed.bin", outputs[unknown_dir / "attitude-fd-in-payload.bin"]),
        ("vfr-hud-signed.bin", vfr_hud),
        ("scaled-pressure-signed.bin", scaled_pressure),
        ("setup-signing-signed.bin", outputs[derived_dir / "setup-signing.bin"]),
    ):
        assert unsigned(outputs[derived_dir / name]) == original, f"{name} unsigned is not its original"
    setup = decode(outputs[derived_dir / "setup-signing.bin"])
    assert setup.get_msgId() == SETUP_SIGNING_ID and bytes(setup.secret_key) == TEST_KEY
    decode(outputs[derived_dir / "heartbeat-unknown-compat.bin"])
    assert decode(outputs[derived_dir / "heartbeat-max-custom-mode.bin"]).custom_mode == MAX_UINT32
    loiter = decode((args.out / LOITER).read_bytes())
    second = decode(outputs[derived_dir / "heartbeat-sysid-2.bin"])
    assert second.get_srcSystem() == SECOND_SYSTEM_ID and second.to_dict() == loiter.to_dict()
    gimbal = decode(outputs[derived_dir / "heartbeat-gimbal-component.bin"])
    assert gimbal.get_srcComponent() == MAV_COMP_ID_GIMBAL and gimbal.to_dict() == decode(source).to_dict()
    assert decode(outputs[gcs_dir / "heartbeat-mission-planner.bin"]).type == mavutil.mavlink.MAV_TYPE_GCS
    asymmetric = decode(outputs[derived_dir / "heartbeat-asymmetric-custom-mode.bin"])
    assert asymmetric.custom_mode == ASYMMETRIC_CUSTOM_MODE and asymmetric.mavlink_version == 3
    trailing = outputs[derived_dir / "heartbeat-trailing-zeros.bin"]
    assert trailing[1] == TRAILING_ZEROS_LENGTH, f"expected LEN {TRAILING_ZEROS_LENGTH}, got {trailing[1]}"
    assert decode(trailing).custom_mode == LOITER_MODE and decode(trailing).mavlink_version == 0
    all_zero = outputs[derived_dir / "heartbeat-all-zero.bin"]
    assert all_zero[1] == 1 and all(value == 0 for value in decode(all_zero).to_dict().values() if value != "HEARTBEAT")
    extra = decode(outputs[derived_dir / "heartbeat-extra-byte.bin"]).to_dict()
    assert extra == decode(source).to_dict(), "pymavlink must decode the extra-byte frame like the original"

    real_interval = decode(interval)
    assert real_interval.get_srcComponent() == MAV_COMP_ID_MISSIONPLANNER and real_interval.target_system == 1
    real_ack = decode(interval_ack)
    assert real_ack.result == common.MAV_RESULT_ACCEPTED and interval_ack[1] == FULL_COMMAND_ACK_LENGTH
    assert outputs[command_dir / "command-long-confirmation.bin"][1] == FULL_COMMAND_LONG_LENGTH
    all_params = decode(outputs[derived_dir / "command-long-all-params.bin"])
    for index, value in enumerate(ALL_PARAMS):
        packed = getattr(all_params, f"param{index + 1}")
        assert packed != packed if index == NAN_PARAM_INDEX else packed == value, f"param{index + 1}"
    assert all_params.command == ARBITRARY_COMMAND and all_params.confirmation == ARBITRARY_CONFIRMATION
    extensions = decode(outputs[derived_dir / "command-ack-extensions.bin"])
    assert extensions.result_param2 == NEGATIVE_RESULT_PARAM2 and extensions.progress == IN_PROGRESS_PROGRESS
    accepted = outputs[derived_dir / "command-ack-accepted-no-extensions.bin"]
    assert accepted[1] == ACCEPTED_NO_EXTENSIONS_LENGTH and decode(accepted).command == SET_MESSAGE_INTERVAL
    failed = outputs[derived_dir / "command-ack-failed-no-extensions.bin"]
    assert failed[1] == FAILED_NO_EXTENSIONS_LENGTH and decode(failed).result == common.MAV_RESULT_FAILED

    split = outputs[derived_dir / "command-ack-split-param2.bin"]
    assert split[1] == SPLIT_PARAM2_LENGTH and decode(split).result_param2 == SPLIT_RESULT_PARAM2
    target_only = outputs[derived_dir / "command-ack-target-system-only.bin"]
    assert target_only[1] == TARGET_SYSTEM_ONLY_LENGTH and decode(target_only).target_system == real_ack.target_system
    broadcast = outputs[derived_dir / "command-long-broadcast.bin"]
    assert broadcast[1] == BROADCAST_LENGTH and decode(broadcast).param2 == real_interval.param2

    assert attitude[1] == FULL_ATTITUDE_LENGTH and position[1] == FULL_GLOBAL_POSITION_INT_LENGTH
    assert vfr_hud[1] == TRUNCATED_VFR_HUD_LENGTH and decode(vfr_hud).throttle == 0
    attitude_edges = decode(outputs[derived_dir / "attitude-edges.bin"])
    assert attitude_edges.time_boot_ms == LARGE_TIME_BOOT_MS and attitude_edges.pitch == ATTITUDE_EDGES["pitch"]
    position_edges = outputs[derived_dir / "global-position-int-edges.bin"]
    assert position_edges[1] == FULL_GLOBAL_POSITION_INT_LENGTH
    assert all(getattr(decode(position_edges), name) == value for name, value in POSITION_EDGES.items())
    assert decode(position_edges).lat == decode(position).lat
    vfr_hud_edges = outputs[derived_dir / "vfr-hud-edges.bin"]
    assert vfr_hud_edges[1] == VFR_HUD_EDGES_LENGTH and decode(vfr_hud_edges).throttle == VFR_HUD_THROTTLE
    assert decode(vfr_hud_edges).climb == VFR_HUD_CLIMB

    assert sys_status[1] == TRUNCATED_SYS_STATUS_LENGTH and battery[1] == TRUNCATED_SYS_STATUS_LENGTH
    assert decode(sys_status).current_battery == -1 and decode(sys_status).battery_remaining == -1
    for name, fields, length in (
        ("sys-status-edges.bin", SYS_STATUS_EDGES, FULL_SYS_STATUS_LENGTH),
        ("sys-status-unknown-battery.bin", SYS_STATUS_UNKNOWN_BATTERY, TRUNCATED_SYS_STATUS_LENGTH),
        ("gps-raw-int-edges.bin", GPS_RAW_INT_EDGES, FULL_GPS_RAW_INT_LENGTH),
        ("gps-raw-int-unknown.bin", GPS_RAW_INT_UNKNOWN, GPS_RAW_INT_BASE_LENGTH),
        ("sys-status-battery-remaining-min.bin", {"battery_remaining": INT8_MIN}, TRUNCATED_SYS_STATUS_LENGTH),
        ("gps-raw-int-time-2-63.bin", {"time_usec": TIME_USEC_2_63}, TRUNCATED_GPS_RAW_INT_LENGTH),
        ("sys-status-battery-zero-volts.bin", {"voltage_battery": 0}, TRUNCATED_SYS_STATUS_LENGTH),
        ("sys-status-no-battery-monitor.bin", no_monitor, TRUNCATED_SYS_STATUS_LENGTH),
        ("sys-status-battery-unhealthy.bin", unhealthy, TRUNCATED_SYS_STATUS_LENGTH),
    ):
        frame = outputs[derived_dir / name]
        assert frame[1] == length, f"{name}: expected LEN {length}, got {frame[1]}"
        assert all(getattr(decode(frame), field) == value for field, value in fields.items()), name
    assert gps_raw_int[1] == TRUNCATED_GPS_RAW_INT_LENGTH and decode(gps_raw_int).v_acc != 0
    assert decode(outputs[derived_dir / "gps-raw-int-unknown.bin"]).lat == decode(gps_raw_int).lat
    for flags in ("present", "enabled", "health"):
        assert getattr(battery_msg, f"onboard_control_sensors_{flags}") & MAV_SYS_STATUS_SENSOR_BATTERY, flags
    for name, source_frame, fields in (
        ("global-position-int-hdg-36000.bin", position, POSITION_HDG_PAST_RANGE),
        ("global-position-int-lat-past-90.bin", position, POSITION_LAT_PAST_90),
        ("global-position-int-lon-past-180.bin", position, POSITION_LON_PAST_180),
        ("global-position-int-lat-int32-min.bin", position, POSITION_LAT_INT32_MIN),
        ("global-position-int-lon-past-180-east.bin", position, POSITION_LON_PAST_180_EAST),
        ("global-position-int-ends-south-east.bin", position, POSITION_ENDS_SOUTH_EAST),
        ("global-position-int-ends.bin", position, POSITION_ENDS),
        ("gps-raw-int-out-of-range.bin", gps_raw_int, GPS_RAW_INT_OUT_OF_RANGE),
        ("vfr-hud-out-of-range.bin", vfr_hud, VFR_HUD_OUT_OF_RANGE),
        ("vfr-hud-throttle-100.bin", vfr_hud, VFR_HUD_THROTTLE_MAX),
        ("attitude-out-of-range.bin", attitude, ATTITUDE_OUT_OF_RANGE),
        ("attitude-past-pi.bin", attitude, ATTITUDE_PAST_PI),
    ):
        derived = decode(outputs[derived_dir / name])
        assert all(same(getattr(derived, field), value) for field, value in fields.items()), name
        original = decode(source_frame)
        unchanged = [field for field in original.get_fieldnames() if field not in fields]
        assert all(same(getattr(derived, field), getattr(original, field)) for field in unchanged), name
    assert decode(outputs[derived_dir / "attitude-past-pi.bin"]).roll == FLOAT32_PI
    assert decode(outputs[derived_dir / "attitude-past-pi.bin"]).yaw > FLOAT32_PI
    real_home = decode(home_position)
    assert home_position[1] == TRUNCATED_HOME_POSITION_LENGTH and real_home.time_usec != 0
    assert all(math.isnan(element) for element in real_home.q), "ArduPilot sends q as NaN"
    for name, fields, length in (
        ("home-position-full.bin", HOME_POSITION_FULL, FULL_HOME_POSITION_LENGTH),
        ("home-position-no-extension.bin", HOME_POSITION_NO_EXTENSION, HOME_POSITION_BASE_LENGTH),
        ("home-position-cut-after-q.bin", HOME_POSITION_CUT_AFTER_Q, HOME_POSITION_AFTER_Q_LENGTH),
        ("home-position-moved.bin", HOME_POSITION_MOVED, TRUNCATED_HOME_POSITION_LENGTH),
        ("home-position-lat-past-90.bin", HOME_POSITION_LAT_PAST_90, TRUNCATED_HOME_POSITION_LENGTH),
        ("home-position-lat-int32-min.bin", HOME_POSITION_LAT_INT32_MIN, TRUNCATED_HOME_POSITION_LENGTH),
        ("home-position-null-island.bin", HOME_POSITION_NULL_ISLAND, TRUNCATED_HOME_POSITION_LENGTH),
    ):
        frame = outputs[derived_dir / name]
        assert frame[1] == length, f"{name}: expected LEN {length}, got {frame[1]}"
        derived = decode(frame)
        assert all(same(getattr(derived, field), value) for field, value in fields.items()), name
        unchanged = [field for field in real_home.get_fieldnames() if field not in fields]
        assert all(same(getattr(derived, field), getattr(real_home, field)) for field in unchanged), name
    real_text = decode(arming_motors)
    assert arming_motors[1] == len(ARMING_MOTORS) + 1 and (real_text.id, real_text.chunk_seq) == (0, 0)
    for name, text, severity, text_id, chunk_seq, length in (
        ("statustext-prearm.bin", PREARM_TEXT, common.MAV_SEVERITY_CRITICAL, 0, 0, len(PREARM_TEXT) + 1),
        ("statustext-arm.bin", ARM_TEXT, common.MAV_SEVERITY_CRITICAL, 0, 0, len(ARM_TEXT) + 1),
        (
            "statustext-full.bin",
            FULL_TEXT,
            common.MAV_SEVERITY_WARNING,
            FULL_TEXT_ID,
            FULL_TEXT_CHUNK_SEQ,
            FULL_STATUSTEXT_LENGTH,
        ),
        ("statustext-chunk-1.bin", CHUNKED_TEXT[:TEXT_LENGTH], real_text.severity, CHUNK_ID, 0, FIRST_CHUNK_LENGTH),
        (
            "statustext-chunk-2.bin",
            CHUNKED_TEXT[TEXT_LENGTH:],
            real_text.severity,
            CHUNK_ID,
            1,
            FULL_STATUSTEXT_LENGTH,
        ),
        ("statustext-nul-inside.bin", NUL_INSIDE_TEXT[:2], real_text.severity, 0, 0, len(NUL_INSIDE_TEXT) + 1),
        ("statustext-empty.bin", b"", common.MAV_SEVERITY_EMERGENCY, 0, 0, 1),
        ("statustext-severity-8.bin", ARMING_MOTORS.encode(), OUTSIDE_SEVERITY, 0, 0, len(ARMING_MOTORS) + 1),
    ):
        frame = outputs[derived_dir / name]
        assert frame[1] == length, f"{name}: expected LEN {length}, got {frame[1]}"
        assert frame[2:7] == arming_motors[2:7], f"{name}: header fields must stay the real frame's"
        derived = decode(frame)
        assert (derived.severity, derived.text, derived.id, derived.chunk_seq) == (
            severity,
            text.decode("ascii"),
            text_id,
            chunk_seq,
        ), name
    # pymavlink does not decode the text as UTF-8 (U+00E9 comes out as two replacement characters), so the
    # payload is checked as bytes; the expected text follows common.xml ("UTF-8 encoded").
    utf8 = outputs[derived_dir / "statustext-utf8.bin"]
    assert utf8[HEADER_LENGTH + 1:HEADER_LENGTH + utf8[1]] == UTF8_TEXT
    assert UTF8_TEXT.decode("utf-8", "replace") == UTF8_DECODED, "CPython's UTF-8 codec is the reference"
    assert decode(stat_runtime).param_type == common.MAV_PARAM_TYPE_INT32
    for real in (stat_runtime, hover, sixteen_chars):
        assert real[1] == FULL_PARAM_VALUE_LENGTH, "ArduCopter's param_type is never 0, so nothing is truncated"
    id_start = HEADER_LENGTH + PARAM_VALUE_ID_OFFSET
    assert 0 not in sixteen_chars[id_start:id_start + PARAM_ID_LENGTH], "a 16-character param_id has no NUL"
    for name, fields, length in (
        ("param-value-batt-low-volt.bin", BATT_LOW_VOLT, FULL_PARAM_VALUE_LENGTH),
        ("param-value-fields.bin", PARAM_VALUE_FIELDS, FULL_PARAM_VALUE_LENGTH),
        ("param-value-nul-inside.bin", {"param_id": PARAM_VALUE_NUL_INSIDE["param_id"][:2]}, FULL_PARAM_VALUE_LENGTH),
        (
            "param-value-type-0.bin",
            PARAM_VALUE_TYPE_0,
            PARAM_VALUE_ID_OFFSET + len(PARAM_VALUE_TYPE_0["param_id"]),
        ),
        ("param-value-empty-id.bin", PARAM_VALUE_EMPTY_ID, PARAM_VALUE_ID_OFFSET),
    ):
        frame = outputs[derived_dir / name]
        assert frame[1] == length, f"{name}: expected LEN {length}, got {frame[1]}"
        assert frame[2:7] == hover[2:7], f"{name}: header fields must stay the real frame's"
        derived = decode(frame)
        expected = {k: v.decode("ascii") if isinstance(v, bytes) else v for k, v in fields.items()}
        assert all(same(getattr(derived, field), value) for field, value in expected.items()), name
    fields_payload = outputs[derived_dir / "param-value-fields.bin"][HEADER_LENGTH:HEADER_LENGTH + PARAM_VALUE_ID_OFFSET]
    assert len(set(fields_payload)) == PARAM_VALUE_ID_OFFSET and 0 not in fields_payload, "every byte must differ"
    for name, param_name in (
        ("param-request-read-batt-low-volt.bin", BATT_LOW_VOLT["param_id"]),
        ("param-request-read-16-chars.bin", SIXTEEN_CHAR_NAME),
        ("param-request-read-1-char.bin", ONE_CHAR_NAME),
    ):
        frame = outputs[derived_dir / name]
        length = PARAM_REQUEST_READ_ID_OFFSET + len(param_name)
        assert frame[1] == length <= FULL_PARAM_REQUEST_READ_LENGTH, f"{name}: expected LEN {length}, got {frame[1]}"
        request = decode(frame)
        assert (request.target_system, request.target_component) == (VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1)
        assert request.param_id == param_name.decode("ascii") and request.param_index == PARAM_INDEX_BY_NAME, name
    mavlink2 = outputs[derived_dir / "session-mavlink2-stream.bin"]
    mav = common.MAVLink(io.BytesIO())
    mav.robust_parsing = True
    mavlink2_msgs = [msg for msg in mav.parse_buffer(mavlink2) or [] if msg.get_type() != "BAD_DATA"]
    assert len(mavlink2_msgs) == SESSION_MAVLINK2_FRAMES, f"got {len(mavlink2_msgs)} MAVLink 2 frames"
    assert b"".join(bytes(msg.get_msgbuf()) for msg in mavlink2_msgs) == mavlink2, "frames must be whole and in order"
    # Enough real frames after the corrupted one to fill a maximum frame, so a receiver waiting for LEN 255 gets
    # its bytes and keeps going (lesson #9).
    following = [(args.out / name).read_bytes() for name in (SOURCE, LOITER) * 8]
    corrupted_len_recovered = {}
    for name in ("heartbeat-corrupted-len-raised.bin", "heartbeat-corrupted-len-lowered.bin"):
        msgs = rejected_then_decoded(outputs[derived_dir / name], b"".join(following))
        assert msgs and msgs[0].get_type() == "BAD_DATA", f"{name}: pymavlink must reject the corrupted frame"
        decoded = [bytes(msg.get_msgbuf()) for msg in msgs if msg.get_type() != "BAD_DATA"]
        assert decoded == following[len(following) - len(decoded):], f"{name}: only real frames may come out"
        corrupted_len_recovered[name] = f"{len(decoded)} of {len(following)}"

    print(f"Reference: pymavlink {pymavlink.__version__}\n")
    for path, frame in outputs.items():
        path.write_bytes(frame)
        shown = frame.hex(" ") if len(frame) <= MAX_SHOWN else f"{len(frame)} bytes"
        print(f"{path.relative_to(args.out).as_posix():48} {shown}")
    print()
    for path in (
        telemetry_dir / "attitude.bin",
        telemetry_dir / "global-position-int.bin",
        telemetry_dir / "vfr-hud.bin",
        derived_dir / "attitude-edges.bin",
        derived_dir / "global-position-int-edges.bin",
        derived_dir / "vfr-hud-edges.bin",
        args.out / SYS_STATUS,
        telemetry_dir / "sys-status.bin",
        telemetry_dir / "gps-raw-int.bin",
        derived_dir / "sys-status-edges.bin",
        derived_dir / "sys-status-unknown-battery.bin",
        derived_dir / "gps-raw-int-edges.bin",
        derived_dir / "gps-raw-int-unknown.bin",
        derived_dir / "sys-status-battery-remaining-min.bin",
        derived_dir / "gps-raw-int-time-2-63.bin",
        derived_dir / "sys-status-battery-zero-volts.bin",
        derived_dir / "sys-status-no-battery-monitor.bin",
        derived_dir / "sys-status-battery-unhealthy.bin",
        derived_dir / "global-position-int-hdg-36000.bin",
        derived_dir / "global-position-int-lat-past-90.bin",
        derived_dir / "global-position-int-lon-past-180.bin",
        derived_dir / "global-position-int-lat-int32-min.bin",
        derived_dir / "global-position-int-lon-past-180-east.bin",
        derived_dir / "global-position-int-ends.bin",
        derived_dir / "global-position-int-ends-south-east.bin",
        derived_dir / "gps-raw-int-out-of-range.bin",
        derived_dir / "vfr-hud-out-of-range.bin",
        derived_dir / "vfr-hud-throttle-100.bin",
        derived_dir / "attitude-out-of-range.bin",
        derived_dir / "attitude-past-pi.bin",
        telemetry_dir / "home-position.bin",
        derived_dir / "home-position-full.bin",
        derived_dir / "home-position-no-extension.bin",
        derived_dir / "home-position-cut-after-q.bin",
        derived_dir / "home-position-moved.bin",
        derived_dir / "home-position-lat-past-90.bin",
        derived_dir / "home-position-lat-int32-min.bin",
        derived_dir / "home-position-null-island.bin",
        statustext_dir / "arming-motors.bin",
        derived_dir / "statustext-prearm.bin",
        derived_dir / "statustext-arm.bin",
        derived_dir / "statustext-full.bin",
        derived_dir / "statustext-chunk-1.bin",
        derived_dir / "statustext-chunk-2.bin",
        derived_dir / "statustext-nul-inside.bin",
        derived_dir / "statustext-utf8.bin",
        derived_dir / "statustext-empty.bin",
        derived_dir / "statustext-severity-8.bin",
        param_dir / "stat-runtime.bin",
        param_dir / "mot-thst-hover.bin",
        param_dir / "ins-gyr1-caltemp.bin",
        derived_dir / "param-value-batt-low-volt.bin",
        derived_dir / "param-value-fields.bin",
        derived_dir / "param-value-nul-inside.bin",
        derived_dir / "param-value-type-0.bin",
        derived_dir / "param-value-empty-id.bin",
        derived_dir / "param-request-read-batt-low-volt.bin",
        derived_dir / "param-request-read-16-chars.bin",
        derived_dir / "param-request-read-1-char.bin",
        *[landed_dir / f"{name}.bin" for name in LANDED_STATES.values()],
        derived_dir / "extended-sys-state-undefined.bin",
        derived_dir / "extended-sys-state-landed-5.bin",
    ):
        frame = outputs.get(path) or path.read_bytes()
        print(f"{path.relative_to(args.out).as_posix()}: seq {frame[4]}, {fields_text(frame)}")
    print()
    print(f"session counts (pymavlink): {session_counts(args.out / SESSION)}")
    print(f"session telemetry values (pymavlink): {session_telemetry_counts(args.out / SESSION)}")
    print(f"real HEARTBEATs pymavlink recovers after a corrupted LEN: {corrupted_len_recovered}")


if __name__ == "__main__":
    main()
