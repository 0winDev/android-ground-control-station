#!/usr/bin/env python3
"""Check the app's flight log against the relay's own (manual SITL tool, #70).

sitl_relay.py --tlog <prefix> writes every frame it forwarded to the app (<prefix>.down.tlog) and every frame the app
sent (<prefix>.up.tlog). The app's log must be those two unbroken runs interleaved, byte for byte: each of its frames
is the next one forwarded or the next one sent. Direction comes from the relay's files, not from the system ID, since
other ground stations send as system 255 too and ArduPilot forwards them. Frames are matched by bytes, not by time,
because the emulator's clock can run seconds behind the host's (lesson #39).

pymavlink reads the three logs (the reference reader) and counts the frames by direction and message type.

Usage:
  py tools/sitl/compare_recording.py <app.tlog> <relay prefix>
"""

import argparse
import sys
from collections import Counter

from pymavlink import mavutil

MAVLINK2_START = 0xFD


def frames(path: str) -> list:
    """Every MAVLink 2 frame of [path] as pymavlink reads it: (type, bytes)."""
    log = mavutil.mavlink_connection(path, dialect="ardupilotmega", robust_parsing=True)
    result = []
    while True:
        msg = log.recv_msg()
        if msg is None:
            return result
        frame = bytes(msg.get_msgbuf())
        if msg.get_type() != "BAD_DATA" and frame and frame[0] == MAVLINK2_START:
            result.append((msg.get_type(), frame))


def start_of(frame: bytes, relay: list) -> int:
    return next((index for index, (_, candidate) in enumerate(relay) if candidate == frame), -1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("app")
    parser.add_argument("relay_prefix")
    args = parser.parse_args()
    app = frames(args.app)
    relay = {direction: frames(f"{args.relay_prefix}.{direction}.tlog") for direction in ("down", "up")}
    # Where each direction's run starts in the relay's file: found at the first app frame from that direction.
    start = {"down": None, "up": None}
    position = {}
    counts = {"down": Counter(), "up": Counter()}
    for index, (kind, frame) in enumerate(app):
        for direction in ("down", "up"):
            if start[direction] is None:
                found = start_of(frame, relay[direction])
                if found < 0:
                    continue
                start[direction] = position[direction] = found
            if position[direction] < len(relay[direction]) and relay[direction][position[direction]][1] == frame:
                position[direction] += 1
                counts[direction][kind] += 1
                break
        else:
            print(f"DIFF: app frame {index} ({kind}) is neither the next frame forwarded nor the next one sent")
            sys.exit(1)
    for direction, label in (("down", "received"), ("up", "sent")):
        total = sum(counts[direction].values())
        print(f"{label}: {total} frames, relay frames {start[direction]} to {position.get(direction, 0) - 1} "
              f"of {len(relay[direction])}, none missing in between")
        print("  " + ", ".join(f"{name} {count}" for name, count in sorted(counts[direction].items())))
    print("OK" if all(counts[d] for d in counts) else "DIFF: a direction is empty")


if __name__ == "__main__":
    main()
