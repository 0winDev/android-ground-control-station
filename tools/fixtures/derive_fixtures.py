#!/usr/bin/env python3
"""Derive MAVLink test fixtures for the frame parser from the real SITL captures.

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
  <out>/gcs/heartbeat-mission-planner.bin       a real GCS HEARTBEAT (not derived) from
                                                heartbeat/session.tlog: Mission Planner, system 255
  <out>/unknown/attitude-fd-in-payload.bin      a real ATTITUDE frame (not derived) from
                                                heartbeat/session.tlog whose payload contains 0xFD
  <out>/derived/attitude-signed.bin             that ATTITUDE re-packed and signed the same way (a signed
                                                frame whose message ID the parser tests treat as unknown)
  <out>/derived/session-stream.bin              heartbeat/session.tlog as a plain byte stream: every
                                                frame (MAVLink 1 and 2) in order, without the 8-byte
                                                .tlog timestamps

checks each output with pymavlink before writing it, and prints the session counts the parser tests
expect.

Usage:
  py -m pip install --user pymavlink==2.4.50
  py tools/fixtures/derive_fixtures.py [--out core/mavlink/src/test/resources]
"""

import argparse
import io
from pathlib import Path

import pymavlink
from pymavlink import mavutil
from pymavlink.dialects.v20 import common
from pymavlink.generator.mavcrc import x25crc

SOURCE = "heartbeat/disarmed-stabilize.bin"
LOITER = "heartbeat/disarmed-loiter.bin"
SECOND_SYSTEM_ID = 2
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
TABLE_MESSAGES = {
    common.MAVLINK_MSG_ID_HEARTBEAT: "heartbeat",
    common.MAVLINK_MSG_ID_SYS_STATUS: "sys_status",
    common.MAVLINK_MSG_ID_SERVO_OUTPUT_RAW: "servo_output_raw",
    common.MAVLINK_MSG_ID_POWER_STATUS: "power_status",
}


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


def with_system_id(frame: bytes, system_id: int) -> bytes:
    """The HEARTBEAT re-packed by pymavlink with another system ID; fields, seq and component unchanged."""
    msg = decode(frame)
    mav = common.MAVLink(io.BytesIO(), srcSystem=system_id, srcComponent=msg.get_srcComponent())
    mav.seq = msg.get_seq()
    copy = common.MAVLink_heartbeat_message(
        msg.type, msg.autopilot, msg.base_mode, msg.custom_mode, msg.system_status, msg.mavlink_version
    )
    return bytes(copy.pack(mav))


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

    outputs = {
        derived_dir / "heartbeat-signed.bin": signed(source),
        derived_dir / "heartbeat-unknown-incompat.bin": with_flags(source, INCOMPAT_INDEX, UNKNOWN_INCOMPAT),
        derived_dir / "heartbeat-unknown-compat.bin": with_flags(source, COMPAT_INDEX, UNKNOWN_COMPAT),
        derived_dir / "heartbeat-empty-payload.bin": empty_payload(source),
        derived_dir / "heartbeat-extra-byte.bin": extra_byte(source),
        derived_dir / "heartbeat-max-custom-mode.bin": max_custom_mode(source),
        derived_dir / "heartbeat-sysid-2.bin": with_system_id((args.out / LOITER).read_bytes(), SECOND_SYSTEM_ID),
        gcs_dir / "heartbeat-mission-planner.bin": gcs_heartbeat(args.out / SESSION),
        unknown_dir / "attitude-fd-in-payload.bin": attitude_with_fd(args.out / SESSION),
        derived_dir / "attitude-signed.bin": signed(attitude_with_fd(args.out / SESSION)),
        derived_dir / "session-stream.bin": session_stream(args.out / SESSION),
    }

    for name in ("heartbeat-signed.bin", "attitude-signed.bin"):
        signed_msg = decode(outputs[derived_dir / name], key=TEST_KEY)
        assert signed_msg.get_signed() and signed_msg._link_id == TEST_LINK_ID, f"pymavlink did not verify {name}"
    decode(outputs[derived_dir / "heartbeat-unknown-compat.bin"])
    assert decode(outputs[derived_dir / "heartbeat-max-custom-mode.bin"]).custom_mode == MAX_UINT32
    loiter = decode((args.out / LOITER).read_bytes())
    second = decode(outputs[derived_dir / "heartbeat-sysid-2.bin"])
    assert second.get_srcSystem() == SECOND_SYSTEM_ID and second.to_dict() == loiter.to_dict()
    assert decode(outputs[gcs_dir / "heartbeat-mission-planner.bin"]).type == mavutil.mavlink.MAV_TYPE_GCS
    extra = decode(outputs[derived_dir / "heartbeat-extra-byte.bin"]).to_dict()
    assert extra == decode(source).to_dict(), "pymavlink must decode the extra-byte frame like the original"

    print(f"Reference: pymavlink {pymavlink.__version__}\n")
    for path, frame in outputs.items():
        path.write_bytes(frame)
        shown = frame.hex(" ") if len(frame) <= MAX_SHOWN else f"{len(frame)} bytes"
        print(f"{path.relative_to(args.out).as_posix():40} {shown}")
    print()
    print(f"session counts (pymavlink): {session_counts(args.out / SESSION)}")


if __name__ == "__main__":
    main()
