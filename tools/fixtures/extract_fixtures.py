#!/usr/bin/env python3
"""Extract MAVLink test fixtures from a telemetry log (.tlog) recorded against ArduPilot SITL.

Offline tool for the codec tests in :core:mavlink. It is NOT part of the app build and pymavlink is
NOT a project dependency: pymavlink is only the reference decoder that provides the expected values,
and its bundled common.xml is the reference for message lengths.

Writes raw MAVLink 2 frames (exactly the bytes on the wire, without the 8-byte .tlog timestamp):

  <out>/heartbeat/<armed|disarmed>-<mode>.bin   first autopilot HEARTBEAT of each (armed, mode) state
  <out>/heartbeat/session.tlog                  a window of the original log, record by record
  <out>/truncated/<message>.bin                 first autopilot frame of each selected message whose LEN
                                                is shorter than its full length in common.xml
                                                (MAVLink 2 trailing-zero truncation; HEARTBEAT never
                                                truncates because its last wire byte is mavlink_version)

and prints Markdown tables with the header fields and decoded values for the fixtures README.

Usage:
  py -m pip install --user pymavlink
  py tools/fixtures/extract_fixtures.py <session.tlog> [--out core/mavlink/src/test/resources]
      [--truncated SYS_STATUS,POWER_STATUS,SERVO_OUTPUT_RAW] [--excerpt-from 395 --excerpt-seconds 60]
"""

import argparse
import os
from pathlib import Path

import pymavlink
from pymavlink import mavutil
from pymavlink.dialects.v20 import common
from pymavlink.generator import mavparse

MAVLINK2_START = 0xFD
AUTOPILOT_COMPONENT = 1
GCS_TYPE = common.MAV_TYPE_GCS
ARMED_FLAG = common.MAV_MODE_FLAG_SAFETY_ARMED
HEARTBEAT_ID = common.MAVLINK_MSG_ID_HEARTBEAT
DEFAULT_TRUNCATED = "SYS_STATUS,POWER_STATUS,SERVO_OUTPUT_RAW"
COMMON_XML = Path(os.path.dirname(pymavlink.__file__)) / "message_definitions" / "v1.0" / "common.xml"


def header(frame: bytes) -> dict:
    """MAVLink 2 header fields read straight from the raw frame (for documentation only)."""
    return {
        "len": frame[1],
        "incompat": frame[2],
        "compat": frame[3],
        "seq": frame[4],
        "sysid": frame[5],
        "compid": frame[6],
        "msgid": int.from_bytes(frame[7:10], "little"),
    }


def common_lengths() -> dict:
    """Message name -> (minimum length without extensions, full length) as defined in common.xml."""
    xml = mavparse.MAVXML(str(COMMON_XML), mavparse.PROTOCOL_2_0)
    return {m.name: (m.wire_min_length, m.wire_length) for m in xml.message}


def read_messages(tlog: Path):
    """Yields (seconds since start, msg, raw frame) for every MAVLink 2 message decoded with `common`."""
    log = mavutil.mavlink_connection(str(tlog), dialect="common", robust_parsing=True)
    start = None
    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            break
        if msg.get_type() == "BAD_DATA":
            continue
        start = msg._timestamp if start is None else start
        frame = bytes(msg.get_msgbuf())
        if frame and frame[0] == MAVLINK2_START:
            yield msg._timestamp - start, msg, frame


def write_excerpt(tlog: Path, target: Path, from_seconds: float, seconds: float) -> int:
    """Copies the .tlog records between from_seconds and from_seconds + seconds, byte for byte."""
    data = tlog.read_bytes()
    log = mavutil.mavlink_connection(str(tlog), dialect="ardupilotmega", robust_parsing=True)
    first = None
    begin = end = None
    offset = 0
    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            break
        first = msg._timestamp if first is None else first
        elapsed = msg._timestamp - first
        if begin is None and elapsed >= from_seconds:
            begin = offset
        if elapsed > from_seconds + seconds:
            break
        offset = log.f.tell()
        end = offset
    excerpt = data[begin or 0:end or 0]
    target.write_bytes(excerpt)
    return len(excerpt)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tlog", type=Path)
    parser.add_argument("--out", type=Path, default=Path("core/mavlink/src/test/resources"))
    parser.add_argument("--truncated", default=DEFAULT_TRUNCATED, help="comma-separated message names")
    parser.add_argument("--excerpt-from", type=float, default=0.0, help="seconds from the start of the log")
    parser.add_argument("--excerpt-seconds", type=float, default=60.0)
    args = parser.parse_args()

    heartbeat_dir = args.out / "heartbeat"
    truncated_dir = args.out / "truncated"
    heartbeat_dir.mkdir(parents=True, exist_ok=True)
    truncated_dir.mkdir(parents=True, exist_ok=True)

    lengths = common_lengths()
    wanted = [name.strip() for name in args.truncated.split(",") if name.strip()]
    heartbeats = {}
    truncated = {}
    for elapsed, msg, frame in read_messages(args.tlog):
        if msg.get_srcComponent() != AUTOPILOT_COMPONENT:
            continue
        if msg.get_msgId() == HEARTBEAT_ID:
            if msg.type == GCS_TYPE:
                continue
            armed = bool(msg.base_mode & ARMED_FLAG)
            mode = mavutil.mode_string_v10(msg).lower()
            heartbeats.setdefault((armed, mode), (elapsed, msg, frame))
        elif msg.get_type() in wanted and header(frame)["len"] < lengths[msg.get_type()][1]:
            truncated.setdefault(msg.get_type(), (elapsed, msg, frame))

    print(f"Reference: pymavlink {pymavlink.__version__}, {COMMON_XML}\n")
    print("## HEARTBEAT fixtures\n")
    print("| File | t (s) | LEN | seq | sysid | compid | type | autopilot | base_mode | custom_mode (mode) "
          "| system_status | mavlink_version |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for (armed, mode), (elapsed, msg, frame) in sorted(heartbeats.items(), key=lambda item: item[1][0]):
        name = f"{'armed' if armed else 'disarmed'}-{mode}.bin"
        (heartbeat_dir / name).write_bytes(frame)
        h = header(frame)
        print(f"| `heartbeat/{name}` | {elapsed:.1f} | {h['len']} | {h['seq']} | {h['sysid']} | {h['compid']} "
              f"| {msg.type} | {msg.autopilot} | {msg.base_mode} | {msg.custom_mode} ({mode.upper()}) "
              f"| {msg.system_status} | {msg.mavlink_version} |")

    print("\n## Truncated payload fixtures\n")
    print("| File | Message (id) | LEN on the wire | Min LEN (no extensions) | Full LEN | Decoded fields |")
    print("|---|---|---|---|---|---|")
    for name in wanted:
        if name not in truncated:
            print(f"| — | {name} | not found truncated in this log | | | |")
            continue
        _, msg, frame = truncated[name]
        file = f"{name.lower()}.bin"
        (truncated_dir / file).write_bytes(frame)
        h = header(frame)
        minimum, full = lengths[name]
        fields = ", ".join(f"{k}={v}" for k, v in msg.to_dict().items() if k != "mavpackettype")
        print(f"| `truncated/{file}` | {name} ({h['msgid']}) | {h['len']} | {minimum} | {full} | {fields} |")

    size = write_excerpt(args.tlog, heartbeat_dir / "session.tlog", args.excerpt_from, args.excerpt_seconds)
    print(f"\n`heartbeat/session.tlog`: {args.excerpt_seconds:g} s from t={args.excerpt_from:g} s, {size} bytes.")


if __name__ == "__main__":
    main()
