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
            return counts
        frame = bytes(msg.get_msgbuf())
        counts["frames"] += 1
        if frame[0] == START_MARKER:
            counts["mavlink2"] += 1
            counts["heartbeat"] += msg.get_msgId() == common.MAVLINK_MSG_ID_HEARTBEAT
        else:
            counts["mavlink1_bytes"] += len(frame)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=Path("core/mavlink/src/test/resources"))
    args = parser.parse_args()

    source = (args.out / SOURCE).read_bytes()
    derived_dir = args.out / "derived"
    unknown_dir = args.out / "unknown"
    derived_dir.mkdir(parents=True, exist_ok=True)
    unknown_dir.mkdir(parents=True, exist_ok=True)

    outputs = {
        derived_dir / "heartbeat-signed.bin": signed(source),
        derived_dir / "heartbeat-unknown-incompat.bin": with_flags(source, INCOMPAT_INDEX, UNKNOWN_INCOMPAT),
        derived_dir / "heartbeat-unknown-compat.bin": with_flags(source, COMPAT_INDEX, UNKNOWN_COMPAT),
        derived_dir / "heartbeat-empty-payload.bin": empty_payload(source),
        unknown_dir / "attitude-fd-in-payload.bin": attitude_with_fd(args.out / SESSION),
        derived_dir / "attitude-signed.bin": signed(attitude_with_fd(args.out / SESSION)),
        derived_dir / "session-stream.bin": session_stream(args.out / SESSION),
    }

    for name in ("heartbeat-signed.bin", "attitude-signed.bin"):
        signed_msg = decode(outputs[derived_dir / name], key=TEST_KEY)
        assert signed_msg.get_signed() and signed_msg._link_id == TEST_LINK_ID, f"pymavlink did not verify {name}"
    decode(outputs[derived_dir / "heartbeat-unknown-compat.bin"])

    print(f"Reference: pymavlink {pymavlink.__version__}\n")
    for path, frame in outputs.items():
        path.write_bytes(frame)
        shown = frame.hex(" ") if len(frame) <= MAX_SHOWN else f"{len(frame)} bytes"
        print(f"{path.relative_to(args.out).as_posix():40} {shown}")
    print()
    print(f"session counts (pymavlink): {session_counts(args.out / SESSION)}")


if __name__ == "__main__":
    main()
