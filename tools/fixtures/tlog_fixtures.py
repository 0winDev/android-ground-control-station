#!/usr/bin/env python3
"""Telemetry log (.tlog) fixtures for the .tlog reader and writer in :core:mavlink (#68).

Offline tool, like extract_fixtures.py and derive_fixtures.py: pymavlink is only the reference reader that provides
the expected values; our own codec is never used here.

Writes:

  <out>/tlog/qgroundcontrol.tlog            a window of a tlog QGroundControl saved from SITL, record by record
  <out>/tlog/mission-planner.tlog           a tlog Mission Planner saved from SITL, every record but its signed ones
  <out>/tlog/<name>.frames.bin              for those two: every frame pymavlink reads, concatenated, as on the
                                            wire (for heartbeat/session.tlog that is derived/session-stream.bin,
                                            checked equal and not written again)
  <out>/tlog/<name>.records.csv             for those two and heartbeat/session.tlog: one line per record, the
                                            timestamp pymavlink reads in µs as stored (low bits included) and the
                                            frame's length
  <out>/derived/tlog-garbage-between-records.bin
                                            the first records of heartbeat/session.tlog with 7 bytes of 0x55
                                            between the first and the second
  <out>/derived/tlog-timestamp-without-frame.bin
                                            the same records with a lone 8-byte timestamp after the fifth
  <out>/derived/tlog-truncated-last-record.bin
                                            the same records, the last one cut halfway through its frame
  <out>/derived/tlog-timestamp-at-end.bin   the same records followed by a lone 8-byte timestamp
  <out>/derived/tlog-garbage-before-first-record.bin
                                            the same 7 garbage bytes before the first record
  <out>/derived/tlog-false-start-before-first-record.bin
                                            8 bytes of 0x55 and a MAVLink 1 start marker before the first record:
                                            a false record taken before any anchor
  <out>/derived/tlog-false-start-after-lone-timestamp.bin
                                            records around the first one of the session whose timestamp holds a
                                            start marker, with a lone timestamp before it
  <out>/derived/tlog-false-start-at-end.bin  the 10 records, a plausible timestamp and a header claiming LEN 255, then
                                            3 more real records too short to complete it

Fails if any output holds SETUP_SIGNING (msgid 256), which carries a signing key, or keeps a signature: logs must keep
neither (ADR 0006).

Usage:
  py -m pip install --user pymavlink==2.4.50
  py tools/fixtures/tlog_fixtures.py <qgroundcontrol.tlog> <mission-planner.tlog>
      [--qgc-from 25 --qgc-seconds 10] [--out core/mavlink/src/test/resources]
"""

import argparse
import struct
from pathlib import Path

import pymavlink
from pymavlink import mavutil

from extract_fixtures import write_excerpt

SESSION = "heartbeat/session.tlog"
SESSION_STREAM = "derived/session-stream.bin"
TIMESTAMP_LENGTH = 8
SETUP_SIGNING_ID = 256
START_MARKERS = (0xFD, 0xFE)
DAMAGED_RECORDS = 10
GARBAGE = bytes([0x55] * 7)
LONE_TIMESTAMP_AFTER = 5
SIGNED_FLAG = 0x01
# pymavlink's mavlogfile.scan_timestamp window: a resync timestamp must be within 3 days of the last good one.
RESYNC_WINDOW_US = 3 * 24 * 60 * 60 * 1_000_000
FALSE_START_RECORDS = 3
# Records after the lone timestamp: enough bytes for a false record started there to complete and swallow real ones.
FALSE_START_FOLLOWING = 20
# A plausible MAVLink 2 header (HEARTBEAT, no flags) claiming LEN 255: a reader waits for a maximum frame.
FALSE_HEADER = bytes([0xFD, 0xFF, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00])
CHECKSUM_LENGTH = 2
# 8 bytes that read as no plausible timestamp, then a MAVLink 1 start marker: a false record before any anchor.
FALSE_PREFIX = bytes([0x55] * 8 + [0xFE])


def records(tlog_bytes: bytes, tlog: Path) -> list:
    """(timestamp in µs as stored, frame) for every record pymavlink reads, in order; fails on BAD_DATA, so every
    record is a whole frame. The timestamp is pymavlink's float seconds back in µs: exact, since µs since the
    epoch stay far below 2^53."""
    log = mavutil.mavlink_connection(str(tlog), dialect="ardupilotmega", robust_parsing=True)
    out = []
    offset = 0
    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            assert offset == len(tlog_bytes), f"{tlog}: pymavlink stopped at byte {offset} of {len(tlog_bytes)}"
            return out
        assert msg.get_type() != "BAD_DATA", f"{tlog}: BAD_DATA at byte {offset}"
        frame = bytes(msg.get_msgbuf())
        stored = struct.unpack(">Q", tlog_bytes[offset:offset + TIMESTAMP_LENGTH])[0]
        assert stored == round(msg._timestamp * 1e6), f"{tlog}: timestamp at byte {offset}"
        assert tlog_bytes[offset + TIMESTAMP_LENGTH:offset + TIMESTAMP_LENGTH + len(frame)] == frame
        out.append((stored, frame))
        offset = log.f.tell()


def message_ids(frame: bytes) -> int:
    """The message ID of a MAVLink 1 or 2 frame."""
    return frame[5] if frame[0] == 0xFE else int.from_bytes(frame[7:10], "little")


def unsigned_only(tlog: Path, target: Path) -> int:
    """Copies [tlog] to [target] without its signed MAVLink 2 records: logs must not keep signatures (ADR 0006).
    Returns how many were left out."""
    data = tlog.read_bytes()
    kept = [
        record_bytes(stamp, frame)
        for stamp, frame in records(data, tlog)
        if not (frame[0] == 0xFD and frame[2] & SIGNED_FLAG)
    ]
    out = b"".join(kept)
    target.write_bytes(out)
    return len(records(data, tlog)) - len(kept)


def check_no_setup_signing(name: str, recs: list) -> None:
    assert all(message_ids(frame) != SETUP_SIGNING_ID for _, frame in recs), f"{name} holds SETUP_SIGNING"


def write_expected(out: Path, name: str, recs: list) -> None:
    (out / f"{name}.frames.bin").write_bytes(b"".join(frame for _, frame in recs))
    lines = [f"{stamp},{len(frame)}" for stamp, frame in recs]
    (out / f"{name}.records.csv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def record_bytes(stamp: int, frame: bytes) -> bytes:
    return struct.pack(">Q", stamp) + frame


def check_no_false_start(name: str, data: bytes, gap_start: int, gap_length: int) -> None:
    """A reader that slides one byte at a time over a gap of [gap_length] bytes looks for a start marker 8 bytes
    ahead: none of those bytes may be one, or the expected recovery would depend on the reader."""
    window = data[gap_start + TIMESTAMP_LENGTH:gap_start + gap_length + TIMESTAMP_LENGTH]
    assert not any(byte in START_MARKERS for byte in window), f"{name}: a start marker inside the slide window"


def summary(name: str, recs: list) -> str:
    v1 = sum(1 for _, frame in recs if frame[0] == 0xFE)
    return (
        f"{name}: {len(recs)} records ({len(recs) - v1} MAVLink 2, {v1} MAVLink 1), "
        f"timestamps {recs[0][0]} to {recs[-1][0]}"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("qgroundcontrol", type=Path)
    parser.add_argument("mission_planner", type=Path)
    parser.add_argument("--qgc-from", type=float, default=25.0, help="seconds from the start of the QGC log")
    parser.add_argument("--qgc-seconds", type=float, default=10.0)
    parser.add_argument("--out", type=Path, default=Path("core/mavlink/src/test/resources"))
    args = parser.parse_args()

    tlog_dir = args.out / "tlog"
    tlog_dir.mkdir(parents=True, exist_ok=True)
    qgc = tlog_dir / "qgroundcontrol.tlog"
    write_excerpt(args.qgroundcontrol, qgc, args.qgc_from, args.qgc_seconds)
    mission_planner = tlog_dir / "mission-planner.tlog"
    signed_left_out = unsigned_only(args.mission_planner, mission_planner)
    session = args.out / SESSION

    print(f"Reference: pymavlink {pymavlink.__version__}\n")
    print(f"mission-planner: {signed_left_out} signed record(s) left out of the copy")
    for name, tlog in (("qgroundcontrol", qgc), ("mission-planner", mission_planner), ("session", session)):
        recs = records(tlog.read_bytes(), tlog)
        check_no_setup_signing(name, recs)
        assert not any(frame[0] == 0xFD and frame[2] & SIGNED_FLAG for _, frame in recs), f"{name} holds a signature"
        write_expected(tlog_dir, name, recs)
        print(summary(name, recs))
    # The session's frames are already a fixture (derive_fixtures.py): keep one copy.
    session_frames = tlog_dir / "session.frames.bin"
    assert session_frames.read_bytes() == (args.out / SESSION_STREAM).read_bytes(), "session frames differ"
    session_frames.unlink()

    all_session_recs = records(session.read_bytes(), session)
    session_recs = all_session_recs[:DAMAGED_RECORDS]
    parts = [record_bytes(stamp, frame) for stamp, frame in session_recs]
    garbage = parts[0] + GARBAGE + b"".join(parts[1:])
    check_no_false_start("garbage", garbage, len(parts[0]), len(GARBAGE))
    lone = struct.pack(">Q", session_recs[LONE_TIMESTAMP_AFTER - 1][0])
    without_frame = b"".join(parts[:LONE_TIMESTAMP_AFTER]) + lone + b"".join(parts[LONE_TIMESTAMP_AFTER:])
    check_no_false_start("lone timestamp", without_frame, len(b"".join(parts[:LONE_TIMESTAMP_AFTER])), len(lone))
    last = parts[-1]
    truncated = b"".join(parts[:-1]) + last[:TIMESTAMP_LENGTH + (len(last) - TIMESTAMP_LENGTH) // 2]
    at_end = b"".join(parts) + lone
    before_first = GARBAGE + b"".join(parts)
    check_no_false_start("garbage before the first record", before_first, 0, len(GARBAGE))

    # The first record after the session's start whose timestamp holds a start marker: behind a lone timestamp, a
    # one-byte resync inspects exactly those bytes. Found in the session, not made up.
    k = next(
        index for index, (stamp, _) in enumerate(all_session_recs)
        if index > FALSE_START_RECORDS and any(b in START_MARKERS for b in struct.pack(">Q", stamp))
    )
    around = all_session_recs[k - FALSE_START_RECORDS:k + FALSE_START_FOLLOWING]
    lone_before = struct.pack(">Q", all_session_recs[k - 1][0])
    head = b"".join(record_bytes(s, f) for s, f in around[:FALSE_START_RECORDS])
    false_start = head + lone_before + b"".join(record_bytes(s, f) for s, f in around[FALSE_START_RECORDS:])
    last_good = all_session_recs[k - 1][0]
    starts = [
        start for start in range(len(head), len(head) + TIMESTAMP_LENGTH)
        if false_start[start + TIMESTAMP_LENGTH] in START_MARKERS
    ]
    assert starts, "a one-byte resync must meet a start marker here, or the fixture tests nothing"
    for start in starts:
        candidate = struct.unpack(">Q", false_start[start:start + TIMESTAMP_LENGTH])[0]
        assert abs(candidate - last_good) > RESYNC_WINDOW_US, "a false start must fail the 3-day window"
        frame = false_start[start + TIMESTAMP_LENGTH:]
        length = (6 if frame[0] == 0xFE else 10 + (13 if frame[2] & SIGNED_FLAG else 0)) + frame[1] + 2
        assert len(frame) >= length, "without the window the false record must complete, or the fixture tests nothing"

    hidden = all_session_recs[DAMAGED_RECORDS:DAMAGED_RECORDS + FALSE_START_RECORDS]
    hidden_bytes = b"".join(record_bytes(s, f) for s, f in hidden)
    # Unsigned LEN 255: the false frame is a 10-byte header, 255 bytes of payload and a 2-byte checksum.
    false_frame_length = len(FALSE_HEADER) + FALSE_HEADER[1] + CHECKSUM_LENGTH
    assert len(FALSE_HEADER) + len(hidden_bytes) < false_frame_length, "the false record must not complete"
    at_end_false = b"".join(parts) + struct.pack(">Q", session_recs[-1][0]) + FALSE_HEADER + hidden_bytes

    # Before any anchor the false record is taken: MAVLink 1, LEN the first byte of record 0's timestamp, so it ends
    # 7 bytes into that timestamp. The resync after it must meet no start marker before record 1.
    poisoned = FALSE_PREFIX + b"".join(parts)
    assert poisoned[len(FALSE_PREFIX)] == 0, "the false MAVLink 1 frame takes LEN 0 from the timestamp's top byte"
    false_end = TIMESTAMP_LENGTH + 6 + 0 + CHECKSUM_LENGTH
    first_gap = len(FALSE_PREFIX) + len(parts[0]) - false_end
    check_no_false_start("false start before the first record", poisoned, false_end, first_gap)

    derived = args.out / "derived"
    outputs = {
        "tlog-garbage-between-records.bin": garbage,
        "tlog-garbage-before-first-record.bin": before_first,
        "tlog-false-start-before-first-record.bin": poisoned,
        "tlog-timestamp-without-frame.bin": without_frame,
        "tlog-false-start-after-lone-timestamp.bin": false_start,
        "tlog-truncated-last-record.bin": truncated,
        "tlog-timestamp-at-end.bin": at_end,
        "tlog-false-start-at-end.bin": at_end_false,
    }
    for name, data in outputs.items():
        (derived / name).write_bytes(data)
        print(f"derived/{name}: {len(data)} bytes")
    cut = len(truncated) - len(b"".join(parts[:-1]))
    print(
        f"\ndamaged files: the first {DAMAGED_RECORDS} records of {SESSION}, {len(GARBAGE)} garbage bytes, the lone "
        f"timestamp after record {LONE_TIMESTAMP_AFTER}, the last record cut to {cut} of its {len(last)} bytes; "
        f"false start after a lone timestamp: records {k - FALSE_START_RECORDS} to {k + FALSE_START_FOLLOWING - 1}, "
        f"lone timestamp before record {k}, start markers met at offsets {[s - len(head) for s in starts]}; "
        f"false start at the end hides records {DAMAGED_RECORDS} to {DAMAGED_RECORDS + FALSE_START_RECORDS - 1}"
    )


if __name__ == "__main__":
    main()
