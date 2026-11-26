#!/usr/bin/env python3
"""Mission transfer fixtures for :core:mavlink (#148), from a QGroundControl upload and download through the SITL relay.

Offline tool, like derive_fixtures.py: pymavlink is the reference decoder that provides the expected values; our own
codec is never used here.

Input: the relay's two logs of one session (tools/sitl/sitl_relay.py --tlog <prefix>), <prefix>.up.tlog with what
QGroundControl sent and <prefix>.down.tlog with what the vehicle sent.

Writes:

  <out>/mission/session.tlog                every record of both logs holding a MISSION_COUNT, MISSION_REQUEST,
                                            MISSION_REQUEST_LIST, MISSION_ACK, MISSION_REQUEST_INT or
                                            MISSION_ITEM_INT between --from and --to, copied byte for byte, in
                                            timestamp order: one upload and one download
  <out>/mission/session.tsv                 those frames as pymavlink decodes them, in order
  <out>/mission/<name>.bin                  real frames of that session (not derived), see REAL below
  <out>/derived/mission-<name>.bin          real frames re-packed by pymavlink with some fields replaced, see main()

pymavlink 2.4.50 bundles a common.xml without MISSION_COUNT's and MISSION_ACK's `opaque_id` extension, so the
frames that carry it are packed with a dialect generated from common.xml on mavlink/mavlink master (--master-dialect,
a folder holding master_common.py). Its CRC_EXTRA for both is the same as 2.4.50's: extensions are not in it.

Each output is decoded with pymavlink before it is written, and its fields are printed.

Usage:
  py -m pip install --user pymavlink==2.4.50
  (in a folder with common.xml, standard.xml and minimal.xml from mavlink/mavlink master)
  py -m pymavlink.tools.mavgen --lang=Python3 --wire-protocol=2.0 --output=master_common.py common.xml
  py tools/fixtures/mission_fixtures.py <prefix> --from <unix s> --to <unix s> --master-dialect <folder>
      [--out core/mavlink/src/test/resources]
"""

import argparse
import importlib
import io
import struct
import sys
from pathlib import Path

from pymavlink import mavutil
from pymavlink.dialects.v20 import common

START_MARKER = 0xFD
GCS = (255, 190)
VEHICLE = (1, 1)
MISSION_TYPES = {
    "MISSION_COUNT",
    "MISSION_REQUEST",
    "MISSION_REQUEST_LIST",
    "MISSION_ACK",
    "MISSION_REQUEST_INT",
    "MISSION_ITEM_INT",
}
# At or above 2^31, so a signed read of the uint32 shows.
ARBITRARY_OPAQUE_ID = 0x87654321
# Past the last MAV_FRAME in common.xml: a frame the codec does not know.
UNKNOWN_FRAME = 255
# A target whose system and component differ, unlike every frame QGroundControl sent (1/1): a swap shows.
OTHER_TARGET = {"target_system": 2, "target_component": 3}

# name -> (sender, message type, predicate): the first frame of the session that matches.
REAL = {
    "count-upload": (GCS, "MISSION_COUNT", lambda m: m.mission_type == 0 and m.count > 0),
    "count-fence-empty": (GCS, "MISSION_COUNT", lambda m: m.mission_type == common.MAV_MISSION_TYPE_FENCE),
    "request-seq-0": (VEHICLE, "MISSION_REQUEST", lambda m: m.seq == 0),
    "request-seq-4": (VEHICLE, "MISSION_REQUEST", lambda m: m.seq == 4),
    "item-home": (GCS, "MISSION_ITEM_INT", lambda m: m.seq == 0),
    "item-takeoff": (GCS, "MISSION_ITEM_INT", lambda m: m.command == common.MAV_CMD_NAV_TAKEOFF),
    "item-waypoint-hold": (GCS, "MISSION_ITEM_INT", lambda m: m.seq > 1 and m.param1 > 0),
    "ack-accepted": (VEHICLE, "MISSION_ACK", lambda m: m.mission_type == 0),
    "ack-accepted-fence": (VEHICLE, "MISSION_ACK", lambda m: m.mission_type == common.MAV_MISSION_TYPE_FENCE),
    "request-list": (GCS, "MISSION_REQUEST_LIST", lambda m: m.mission_type == 0),
    "request-list-fence": (GCS, "MISSION_REQUEST_LIST", lambda m: m.mission_type == common.MAV_MISSION_TYPE_FENCE),
    "count-download": (VEHICLE, "MISSION_COUNT", lambda m: m.mission_type == 0),
    "request-int-seq-0": (GCS, "MISSION_REQUEST_INT", lambda m: m.seq == 0),
    "item-download-waypoint": (VEHICLE, "MISSION_ITEM_INT", lambda m: m.seq == 2),
    "ack-download": (GCS, "MISSION_ACK", lambda m: m.mission_type == 0),
}


def records(path: Path):
    """Each record of a .tlog: (timestamp in µs as stored, frame bytes), with the message pymavlink reads there."""
    data = path.read_bytes()
    log = mavutil.mavlink_connection(str(path), dialect="common", robust_parsing=True)
    end = 0
    while True:
        msg = log.recv_match(blocking=False)
        if msg is None:
            return
        frame = bytes(msg.get_msgbuf())
        # Search past the previous record, so a repeated identical frame gets its own timestamp.
        offset = data.find(frame, end + 8)
        end = offset + len(frame)
        stamp = struct.unpack(">Q", data[offset - 8:offset])[0]
        yield stamp, frame, msg


def decode(frame: bytes, dialect=common):
    mav = dialect.MAVLink(io.BytesIO())
    mav.robust_parsing = False
    msgs = mav.parse_buffer(frame)
    assert msgs and len(msgs) == 1, f"pymavlink did not decode {frame.hex(' ')}"
    return msgs[0]


def with_message_fields(source: bytes, dialect=common, **fields) -> bytes:
    """The message re-packed by pymavlink with some fields replaced (extensions included); seq, system and component
    unchanged. pymavlink truncates the trailing zero bytes of a MAVLink 2 payload."""
    msg = decode(source, dialect)
    mav = dialect.MAVLink(io.BytesIO(), srcSystem=msg.get_srcSystem(), srcComponent=msg.get_srcComponent())
    mav.seq = msg.get_seq()
    values = {name: getattr(msg, name) for name in msg.get_fieldnames()}
    values.update(fields)
    copy = type(msg)(*[values[name] for name in msg.get_fieldnames()])
    return bytes(copy.pack(mav))


def fields_text(frame: bytes, dialect=common) -> str:
    msg = decode(frame, dialect)
    values = ", ".join(f"{name} {getattr(msg, name)}" for name in msg.get_fieldnames())
    return f"{msg.get_type()} from {msg.get_srcSystem()}/{msg.get_srcComponent()}, LEN {frame[1]}: {values}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("prefix")
    parser.add_argument("--from", dest="start", type=float, required=True, help="unix seconds")
    parser.add_argument("--to", dest="end", type=float, required=True, help="unix seconds")
    parser.add_argument("--master-dialect", type=Path, required=True, help="folder holding master_common.py")
    parser.add_argument("--out", type=Path, default=Path("core/mavlink/src/test/resources"))
    args = parser.parse_args()
    sys.path.insert(0, str(args.master_dialect))
    master = importlib.import_module("master_common")

    session = []
    for suffix in ("up", "down"):
        for stamp, frame, msg in records(Path(f"{args.prefix}.{suffix}.tlog")):
            if frame[0] == START_MARKER and msg.get_type() in MISSION_TYPES and args.start <= stamp / 1e6 <= args.end:
                session.append((stamp, frame, msg))
    session.sort(key=lambda record: record[0])

    mission_dir = args.out / "mission"
    derived_dir = args.out / "derived"
    mission_dir.mkdir(parents=True, exist_ok=True)
    derived_dir.mkdir(parents=True, exist_ok=True)
    (mission_dir / "session.tlog").write_bytes(b"".join(struct.pack(">Q", s) + f for s, f, _ in session))
    with open(mission_dir / "session.tsv", "w", encoding="utf-8", newline="\n") as table:
        table.write("sender\tmessage\tfields\n")
        for _, frame, msg in session:
            values = ", ".join(f"{name}={getattr(msg, name)}" for name in msg.get_fieldnames())
            table.write(f"{msg.get_srcSystem()}/{msg.get_srcComponent()}\t{msg.get_type()}\t{values}\n")

    real = {}
    for name, (sender, message_type, matches) in REAL.items():
        real[name] = next(
            f for _, f, m in session
            if (m.get_srcSystem(), m.get_srcComponent()) == sender and m.get_type() == message_type and matches(m)
        )

    takeoff = real["item-takeoff"]
    outputs = {mission_dir / f"{name}.bin": frame for name, frame in real.items()}
    outputs.update({
        # RTL is not in the capture: the take off re-packed as the route's last item, frame and position unused.
        derived_dir / "mission-item-rtl.bin": with_message_fields(
            takeoff, seq=5, command=common.MAV_CMD_NAV_RETURN_TO_LAUNCH, frame=common.MAV_FRAME_GLOBAL, param4=0.0,
            x=0, y=0, z=0.0,
        ),
        # Every field non-zero and distinct, mission_type 2 (rally): full length, every offset shows.
        derived_dir / "mission-item-full.bin": with_message_fields(
            takeoff, **OTHER_TARGET, seq=0x0102, frame=UNKNOWN_FRAME, command=0x0304, current=1,
            autocontinue=0, param1=1.5, param2=-2.25, param3=100000.0, param4=-1.0, x=-353633515, y=1491652412,
            z=-0.5, mission_type=common.MAV_MISSION_TYPE_RALLY,
        ),
        derived_dir / "mission-count-opaque-id.bin": with_message_fields(
            real["count-download"], master, opaque_id=ARBITRARY_OPAQUE_ID,
        ),
        derived_dir / "mission-ack-opaque-id.bin": with_message_fields(
            real["ack-accepted"], master, mission_type=common.MAV_MISSION_TYPE_FENCE, opaque_id=ARBITRARY_OPAQUE_ID,
        ),
        derived_dir / "mission-ack-invalid-sequence.bin": with_message_fields(
            real["ack-accepted"], type=common.MAV_MISSION_INVALID_SEQUENCE,
        ),
        derived_dir / "mission-ack-type-past-enum.bin": with_message_fields(
            real["ack-accepted"], type=common.MAV_MISSION_RESULT_ENUM_END,
        ),
        derived_dir / "mission-request-seq-high.bin": with_message_fields(real["request-seq-4"], seq=0xFFFE),
        derived_dir / "mission-request-rally.bin": with_message_fields(
            real["request-seq-4"], mission_type=common.MAV_MISSION_TYPE_RALLY,
        ),
        derived_dir / "mission-request-int-seq-high.bin": with_message_fields(
            real["request-int-seq-0"], seq=0xFFFE, mission_type=common.MAV_MISSION_TYPE_FENCE,
        ),
        # QGroundControl's frames with every field distinct, for the encoder.
        derived_dir / "mission-request-int-fields.bin": with_message_fields(
            real["request-int-seq-0"], **OTHER_TARGET, seq=0x0102, mission_type=common.MAV_MISSION_TYPE_FENCE,
        ),
        derived_dir / "mission-request-list-fields.bin": with_message_fields(
            real["request-list"], **OTHER_TARGET, mission_type=common.MAV_MISSION_TYPE_RALLY,
        ),
        derived_dir / "mission-count-fields.bin": with_message_fields(
            real["count-upload"], master, **OTHER_TARGET, count=0x0102, mission_type=common.MAV_MISSION_TYPE_FENCE,
            opaque_id=ARBITRARY_OPAQUE_ID,
        ),
        derived_dir / "mission-ack-fields.bin": with_message_fields(
            real["ack-download"], master, **OTHER_TARGET, type=common.MAV_MISSION_INVALID_SEQUENCE,
            mission_type=common.MAV_MISSION_TYPE_FENCE, opaque_id=ARBITRARY_OPAQUE_ID,
        ),
    })

    for path, frame in outputs.items():
        decode(frame, master)
        path.write_bytes(frame)
        print(f"{path.relative_to(args.out).as_posix()}: seq {frame[4]}, {fields_text(frame, master)}")
        print(f"  {frame.hex(' ')}")
    print()
    counts = {}
    for _, _, msg in session:
        key = f"{msg.get_srcSystem()}/{msg.get_srcComponent()} {msg.get_type()}"
        counts[key] = counts.get(key, 0) + 1
    print(f"session: {len(session)} records, {counts}")


if __name__ == "__main__":
    main()
