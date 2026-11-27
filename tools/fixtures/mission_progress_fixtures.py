#!/usr/bin/env python3
"""Mission progress fixtures for :core:mavlink (#149), from a route flown in AUTO in SITL.

Offline tool, like mission_fixtures.py, whose helpers it reuses: pymavlink is the reference decoder that provides the
expected values; our own codec is never used here.

Input: a .tlog of what the vehicle sent on one SITL TCP channel (8-byte big-endian µs timestamp + frame per record),
recorded by a pymavlink script that uploaded a route (home, NAV_TAKEOFF, two NAV_WAYPOINTs, RTL), armed in GUIDED and
started it with MAV_CMD_MISSION_START, with MISSION_CURRENT requested at 1 Hz.

Writes:

  <out>/mission/progress-session.tlog       every record holding a MISSION_CURRENT or MISSION_ITEM_REACHED, copied
                                            byte for byte, in order
  <out>/mission/progress-session.tsv        those frames as pymavlink decodes them, in order
  <out>/mission/<name>.bin                  real frames of that session (not derived), see REAL below
  <out>/derived/mission-<name>.bin          real frames re-packed by pymavlink with some fields replaced, see main()

pymavlink 2.4.50 bundles a common.xml without MISSION_CURRENT's `mission_id`, `fence_id` and `rally_points_id`
extensions, so every frame is decoded, and the frames that carry them packed, with a dialect generated from
common.xml on mavlink/mavlink master (--master-dialect, a folder holding master_common.py). Its CRC_EXTRA is the same
as 2.4.50's (28): extensions are not in it.

Usage:
  py -m pip install --user pymavlink==2.4.50
  (master_common.py generated as in mission_fixtures.py)
  py tools/fixtures/mission_progress_fixtures.py <capture.tlog> --master-dialect <folder>
      [--out core/mavlink/src/test/resources]
"""

import argparse
import importlib
import struct
import sys
from pathlib import Path

from mission_fixtures import START_MARKER, decode, fields_text, records, with_message_fields

PROGRESS_TYPES = {"MISSION_CURRENT", "MISSION_ITEM_REACHED"}

# Each at or above 2^31 and distinct, so a signed read or a swapped offset shows.
ARBITRARY_MISSION_ID = 0x87654321
ARBITRARY_FENCE_ID = 0x98765432
ARBITRARY_RALLY_POINTS_ID = 0xA9876543
# MISSION_STATE in common.xml ends at 5 (COMPLETE): a state the codec does not know.
STATE_PAST_ENUM = 6
# common.xml: `total` is UINT16_MAX "if no mission is present on the vehicle".
TOTAL_NO_MISSION = 0xFFFF

MISSION_STATE_NO_MISSION = 1
MISSION_STATE_NOT_STARTED = 2
MISSION_STATE_ACTIVE = 3
MISSION_STATE_PAUSED = 4
MISSION_STATE_COMPLETE = 5
MISSION_MODE_SUSPENDED = 2

# name -> (message type, predicate): the first frame of the session that matches.
REAL = {
    "current-not-started": (
        "MISSION_CURRENT", lambda m: m.mission_state == MISSION_STATE_NOT_STARTED and m.mission_mode == 0,
    ),
    "current-active": ("MISSION_CURRENT", lambda m: m.mission_state == MISSION_STATE_ACTIVE and m.seq == 2),
    "current-complete": ("MISSION_CURRENT", lambda m: m.mission_state == MISSION_STATE_COMPLETE),
    "reached-takeoff": ("MISSION_ITEM_REACHED", lambda m: m.seq == 1),
    "reached-rtl": ("MISSION_ITEM_REACHED", lambda m: m.seq == 4),
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--master-dialect", type=Path, required=True, help="folder holding master_common.py")
    parser.add_argument("--out", type=Path, default=Path("core/mavlink/src/test/resources"))
    args = parser.parse_args()
    sys.path.insert(0, str(args.master_dialect))
    master = importlib.import_module("master_common")

    session = [
        (stamp, frame, decode(frame, master))
        for stamp, frame, msg in records(args.capture)
        if frame[0] == START_MARKER and msg.get_type() in PROGRESS_TYPES
    ]

    mission_dir = args.out / "mission"
    derived_dir = args.out / "derived"
    mission_dir.mkdir(parents=True, exist_ok=True)
    derived_dir.mkdir(parents=True, exist_ok=True)
    (mission_dir / "progress-session.tlog").write_bytes(b"".join(struct.pack(">Q", s) + f for s, f, _ in session))
    with open(mission_dir / "progress-session.tsv", "w", encoding="utf-8", newline="\n") as table:
        table.write("seq\tLEN\tmessage\tfields\n")
        for _, frame, msg in session:
            values = ", ".join(f"{name}={getattr(msg, name)}" for name in msg.get_fieldnames())
            table.write(f"{frame[4]}\t{frame[1]}\t{msg.get_type()}\t{values}\n")

    real = {
        name: next(f for _, f, m in session if m.get_type() == message_type and matches(m))
        for name, (message_type, matches) in REAL.items()
    }

    outputs = {mission_dir / f"{name}.bin": frame for name, frame in real.items()}
    outputs.update({
        # Every field non-zero and distinct, a state and a mode ArduPilot never sends: full length, every offset shows.
        derived_dir / "mission-current-full.bin": with_message_fields(
            real["current-active"], master, seq=0x0102, total=0x0304, mission_state=MISSION_STATE_PAUSED,
            mission_mode=MISSION_MODE_SUSPENDED, mission_id=ARBITRARY_MISSION_ID, fence_id=ARBITRARY_FENCE_ID,
            rally_points_id=ARBITRARY_RALLY_POINTS_ID,
        ),
        # Only seq, as from an autopilot that predates the extensions: LEN 2, every extension zero-filled.
        derived_dir / "mission-current-seq-only.bin": with_message_fields(
            real["current-active"], master, total=0, mission_state=0, mission_mode=0,
        ),
        derived_dir / "mission-current-no-mission.bin": with_message_fields(
            real["current-not-started"], master, seq=0, total=TOTAL_NO_MISSION, mission_state=MISSION_STATE_NO_MISSION,
        ),
        derived_dir / "mission-current-state-past-enum.bin": with_message_fields(
            real["current-active"], master, mission_state=STATE_PAST_ENUM,
        ),
        derived_dir / "mission-item-reached-seq-high.bin": with_message_fields(real["reached-rtl"], master, seq=0xFFFE),
    })

    for path, frame in outputs.items():
        path.write_bytes(frame)
        print(f"{path.relative_to(args.out).as_posix()}: seq {frame[4]}, {fields_text(frame, master)}")
        print(f"  {frame.hex(' ')}")
    print()
    counts = {}
    for _, frame, msg in session:
        key = f"{msg.get_type()} LEN {frame[1]}"
        counts[key] = counts.get(key, 0) + 1
    print(f"session: {len(session)} records, {counts}")


if __name__ == "__main__":
    main()
