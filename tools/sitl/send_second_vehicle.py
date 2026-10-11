#!/usr/bin/env python3
"""Send a second vehicle's HEARTBEAT to the app once per second, to check N-1 / REQ-020 against SITL.

Manual SITL tool, standard library only. It replays one captured frame as is, the fixture
derived/heartbeat-sysid-2.bin (a real ArduCopter SITL HEARTBEAT re-packed by pymavlink with system
ID 2, see tools/fixtures/derive_fixtures.py), so the app hears system 2 next to the simulator's
system 1. The frame is never built or modified here.

Usage, with the emulator listening on 14550 and a host port redirected to it for this script only
(the Mission Planner mirror keeps its own redirect):

  adb emu redir add udp:14561:14550
  python tools/sitl/send_second_vehicle.py --port 14561

Stop it with Ctrl+C; the app then sees system 2 go silent and drops it from the warning after the
link-loss timeout (3 s).
"""

import argparse
import socket
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURE = REPO_ROOT / "core/mavlink/src/test/resources/derived/heartbeat-sysid-2.bin"
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 14561
DEFAULT_INTERVAL_S = 1.0  # ArduPilot sends HEARTBEAT at 1 Hz

MAVLINK2_START = 0xFD
MAVLINK2_HEADER_LEN = 10
MAVLINK2_CHECKSUM_LEN = 2
MSG_ID_HEARTBEAT = 0
SYSID_OFFSET = 5


def read_frame(path: Path) -> bytes:
    """Read the fixture and check it is exactly one unsigned MAVLink 2 HEARTBEAT, without decoding it."""
    frame = path.read_bytes()
    if len(frame) < MAVLINK2_HEADER_LEN or frame[0] != MAVLINK2_START:
        sys.exit(f"{path} is not a MAVLink 2 frame")
    payload_len = frame[1]
    expected_len = MAVLINK2_HEADER_LEN + payload_len + MAVLINK2_CHECKSUM_LEN
    if len(frame) != expected_len:
        sys.exit(f"{path} has {len(frame)} bytes, expected {expected_len} for one unsigned frame")
    msg_id = int.from_bytes(frame[7:10], "little")
    if msg_id != MSG_ID_HEARTBEAT:
        sys.exit(f"{path} carries message ID {msg_id}, not HEARTBEAT")
    return frame


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE, help="frame to send (default: %(default)s)")
    parser.add_argument("--host", default=DEFAULT_HOST, help="destination host (default: %(default)s)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="destination UDP port (default: %(default)s)")
    parser.add_argument(
        "--interval",
        type=float,
        default=DEFAULT_INTERVAL_S,
        help="seconds between frames (default: %(default)s)",
    )
    args = parser.parse_args()

    frame = read_frame(args.fixture)
    print(
        f"Sending {args.fixture.name} (system ID {frame[SYSID_OFFSET]}) to {args.host}:{args.port} "
        f"every {args.interval} s. Ctrl+C to stop.",
    )
    sent = 0
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            while True:
                sock.sendto(frame, (args.host, args.port))
                sent += 1
                print(f"\r{sent} sent", end="", flush=True)
                time.sleep(args.interval)
        except KeyboardInterrupt:
            print(f"\nStopped after {sent} frames.")


if __name__ == "__main__":
    main()
