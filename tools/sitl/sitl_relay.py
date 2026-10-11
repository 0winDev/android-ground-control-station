#!/usr/bin/env python3
"""Relay between ArduPilot SITL and the app, to check what the app sends (manual SITL tool, #14).

Downlink: every frame SITL sends on a TCP port is forwarded as one UDP datagram to the emulator
(host port redirected into the app's 14550). Uplink: whatever the app sends back arrives at this relay's
UDP socket (the emulator NAT replies to the sender address), is decoded by pymavlink (the reference
decoder) and logged, and is forwarded to SITL so the vehicle hears it.

Control, from a terminal (key then Enter): d = toggle the downlink (a downlink-only loss), q = quit.
With --background (no terminal; on Windows NUL even claims to be one): create the file
<log>.downlink-off to cut the downlink and delete it to restore it; stop the process to quit.

pymavlink is the reference decoder here and only a tool dependency (py -m pip install --user pymavlink).

Usage:
  adb emu redir add udp:14561:14550
  python sitl_relay.py --sitl tcp:127.0.0.1:5763 --app 127.0.0.1:14561
  (standalone SITL without Mission Planner: --sitl tcp:127.0.0.1:5760)
"""

import argparse
import os
import socket
import sys
import threading
import time

from pymavlink import mavutil
from pymavlink.dialects.v20 import common

CONTROL_POLL_S = 0.2
downlink_on = threading.Event()
downlink_on.set()
stop = threading.Event()
log_file = None


def log(line: str) -> None:
    stamp = time.strftime("%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}"
    text = f"[{stamp}] {line}"
    print(text, flush=True)
    if log_file:
        log_file.write(text + "\n")
        log_file.flush()


def downlink(sitl, udp: socket.socket, app: tuple) -> None:
    """SITL -> app, one frame per datagram; logs the vehicle's mode/armed changes and STATUSTEXT."""
    last = None
    while not stop.is_set():
        msg = sitl.recv_msg()
        if msg is None:
            time.sleep(0.005)
            continue
        if msg.get_type() == "BAD_DATA":
            continue
        if msg.get_type() == "HEARTBEAT" and msg.get_srcSystem() == 1 and msg.get_srcComponent() == 1:
            state = (mavutil.mode_string_v10(msg), bool(msg.base_mode & 128))
            if state != last:
                log(f"VEHICLE mode={state[0]} armed={state[1]}")
                last = state
        if msg.get_type() == "STATUSTEXT":
            log(f"VEHICLE STATUSTEXT: {msg.text}")
        if downlink_on.is_set():
            udp.sendto(bytes(msg.get_msgbuf()), app)


def uplink(sitl, udp: socket.socket) -> None:
    """App -> SITL; decodes every frame with pymavlink and logs the app's HEARTBEATs."""
    parser = common.MAVLink(None)
    parser.robust_parsing = True
    last_seq = None
    first_logged = False
    while not stop.is_set():
        try:
            data, sender = udp.recvfrom(65_535)
        except socket.timeout:
            continue
        sitl.write(data)
        for msg in parser.parse_buffer(data) or []:
            if msg.get_type() != "HEARTBEAT":
                log(f"APP {msg.get_type()} from {sender}")
                continue
            seq = msg.get_seq()
            gap = "" if last_seq is None or seq == (last_seq + 1) % 256 else f"  (seq jump from {last_seq})"
            last_seq = seq
            log(
                f"APP HEARTBEAT from {sender[0]}:{sender[1]} sys={msg.get_srcSystem()} comp={msg.get_srcComponent()} "
                f"seq={seq} type={msg.type} autopilot={msg.autopilot} base_mode={msg.base_mode} "
                f"custom_mode={msg.custom_mode} system_status={msg.system_status} "
                f"mavlink_version={msg.mavlink_version}{gap}"
            )
            if not first_logged:
                log(f"APP first frame bytes: {data.hex(' ')}")
                first_logged = True


def main() -> None:
    global log_file
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sitl", default="tcp:127.0.0.1:5763")
    parser.add_argument("--app", default="127.0.0.1:14561", help="host:port redirected into the emulator")
    parser.add_argument("--log", default="relay-14.log")
    parser.add_argument("--background", action="store_true", help="control by file instead of the terminal")
    args = parser.parse_args()
    host, port = args.app.rsplit(":", 1)
    app = (host, int(port))
    log_file = open(args.log, "a", encoding="utf-8")

    sitl = mavutil.mavlink_connection(args.sitl, source_system=253, dialect="common")
    log(f"waiting for SITL on {args.sitl}...")
    sitl.wait_heartbeat(timeout=30)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("127.0.0.1", 0))
    udp.settimeout(0.5)
    log(f"relay up: SITL {args.sitl} -> app {app}; app replies come back to {udp.getsockname()}")

    for target in (lambda: downlink(sitl, udp, app), lambda: uplink(sitl, udp)):
        threading.Thread(target=target, daemon=True).start()
    if not args.background:
        for line in sys.stdin:
            key = line.strip().lower()
            if key == "d":
                set_downlink(not downlink_on.is_set())
            elif key == "q":
                break
    else:
        control = args.log + ".downlink-off"
        log(f"background: create {control} to cut the downlink, delete it to restore it")
        while True:
            set_downlink(not os.path.exists(control))
            time.sleep(CONTROL_POLL_S)
    stop.set()


def set_downlink(on: bool) -> None:
    if on == downlink_on.is_set():
        return
    if on:
        downlink_on.set()
        log("DOWNLINK ON")
    else:
        downlink_on.clear()
        log("DOWNLINK OFF (the app should show link lost about 3 s later and stop its HEARTBEAT)")


if __name__ == "__main__":
    main()
