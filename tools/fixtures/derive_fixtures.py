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

checks each output with pymavlink before writing it, prints the decoded fields of the telemetry fixtures
(floats as the shortest decimal text that is the same float32) and the session counts the tests expect.

Usage:
  py -m pip install --user pymavlink==2.4.50
  py tools/fixtures/derive_fixtures.py [--out core/mavlink/src/test/resources]
"""

import argparse
import io
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
    common.MAVLINK_MSG_ID_SERVO_OUTPUT_RAW: "servo_output_raw",
    common.MAVLINK_MSG_ID_POWER_STATUS: "power_status",
    common.MAVLINK_MSG_ID_COMMAND_LONG: "command_long",
    common.MAVLINK_MSG_ID_COMMAND_ACK: "command_ack",
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
    """The fields of [frame] as pymavlink decodes them, floats as float32 text."""
    msg = decode(frame)
    values = []
    for name, kind in zip(msg.get_fieldnames(), msg.fieldtypes):
        value = getattr(msg, name)
        values.append(f"{name}={float32_text(value) if kind == 'float' else value}")
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
    session = args.out / SESSION
    interval = session_frame(session, "COMMAND_LONG", lambda m: m.command == SET_MESSAGE_INTERVAL)
    interval_ack = session_frame(session, "COMMAND_ACK", lambda m: m.command == SET_MESSAGE_INTERVAL)
    no_extensions = {"progress": 0, "result_param2": 0, "target_system": 0, "target_component": 0}
    attitude = session_frame(session, "ATTITUDE", lambda m: True)
    position = session_frame(session, "GLOBAL_POSITION_INT", lambda m: m.vy < 0)
    vfr_hud = session_frame(session, "VFR_HUD", lambda m: True)

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
        unknown_dir / "scaled-pressure.bin": session_frame(session, "SCALED_PRESSURE", lambda m: True),
        derived_dir / "attitude-edges.bin": with_message_fields(
            attitude, time_boot_ms=LARGE_TIME_BOOT_MS, **ATTITUDE_EDGES
        ),
        derived_dir / "global-position-int-edges.bin": with_message_fields(
            position, time_boot_ms=LARGE_TIME_BOOT_MS, **POSITION_EDGES
        ),
        derived_dir / "vfr-hud-edges.bin": with_message_fields(vfr_hud, climb=VFR_HUD_CLIMB, throttle=VFR_HUD_THROTTLE),
    }

    for name in ("heartbeat-signed.bin", "attitude-signed.bin"):
        signed_msg = decode(outputs[derived_dir / name], key=TEST_KEY)
        assert signed_msg.get_signed() and signed_msg._link_id == TEST_LINK_ID, f"pymavlink did not verify {name}"
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
    ):
        print(f"{path.relative_to(args.out).as_posix()}: seq {outputs[path][4]}, {fields_text(outputs[path])}")
    print()
    print(f"session counts (pymavlink): {session_counts(args.out / SESSION)}")


if __name__ == "__main__":
    main()
