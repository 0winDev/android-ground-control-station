#!/usr/bin/env python3
"""Relay between ArduPilot SITL and the app, to check what the app sends (manual SITL tool, #14, #36).

Downlink: every frame SITL sends on a TCP port is forwarded as one UDP datagram to the emulator
(host port redirected into the app's 14550). Uplink: whatever the app sends back arrives at this relay's
UDP socket (the emulator NAT replies to the sender address), is decoded by pymavlink (the reference
decoder) and logged, and is forwarded to SITL so the vehicle hears it.

Telemetry streams (#36): every --rates-every seconds it logs the rate at which the vehicle sent each message
the app requests (counted on the SITL side, so even while the downlink is cut), and it logs every
COMMAND_LONG from the app with the vehicle's COMMAND_ACK and how long the answer took.

Stopping a stream (#39): s = ask the vehicle, on this relay's channel (the app's), to stop sending ATTITUDE
(MAV_CMD_SET_MESSAGE_INTERVAL with interval -1, "disable" in common.xml). ArduPilot keeps rates per channel
(#36), so the app stops receiving it and should request it again; the RATES lines show it come back.

Stopping the battery (#41): b = the same for SYS_STATUS, so the app's battery reading goes stale.

Recording (#70): with --tlog <prefix> it writes, as .tlogs, every frame it forwards to the app (<prefix>.down.tlog) and
every frame the app sends (<prefix>.up.tlog), so compare_recording.py can check the app's own log against them. Two
files, because other ground stations also send as system 255 and ArduPilot forwards them to the app.

Control, from a terminal (key then Enter): d = toggle the downlink (a downlink-only loss), s = stop ATTITUDE,
b = stop SYS_STATUS, q = quit. With --background (no terminal; on Windows NUL even claims to be one): create the
file <log>.downlink-off to cut the downlink and delete it to restore it; create <log>.stop-attitude or
<log>.stop-sys-status to stop that stream once (the relay deletes it); stop the process to quit.

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
# common.xml: MAV_CMD_SET_MESSAGE_INTERVAL (511), param1 message ID, param2 interval in us, -1 to disable.
MAV_CMD_SET_MESSAGE_INTERVAL = 511
DISABLE_INTERVAL = -1
ATTITUDE_ID = 30
SYS_STATUS_ID = 1
VEHICLE_SYSTEM_ID = 1
MAV_COMP_ID_AUTOPILOT1 = 1
# The streams the app requests (data/vehicle/.../telemetry/TelemetryStream.kt), by pymavlink type name.
REQUESTED = ("ATTITUDE", "GLOBAL_POSITION_INT", "VFR_HUD", "GPS_RAW_INT", "SYS_STATUS")
counts = {name: 0 for name in REQUESTED}
counts_lock = threading.Lock()
# When each COMMAND_LONG from the app reached the relay, by command, to time the vehicle's answer.
commands_sent = {}
downlink_on = threading.Event()
downlink_on.set()
stop = threading.Event()
log_file = None
tlog_files = {}
tlog_lock = threading.Lock()


def log(line: str) -> None:
    stamp = time.strftime("%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}"
    text = f"[{stamp}] {line}"
    print(text, flush=True)
    if log_file:
        log_file.write(text + "\n")
        log_file.flush()


def record(direction: str, frame: bytes) -> None:
    """Appends [frame] to the [direction] .tlog: 8-byte big-endian us since the Unix epoch, then the frame."""
    if direction not in tlog_files:
        return
    with tlog_lock:
        tlog_files[direction].write(int(time.time() * 1_000_000).to_bytes(8, "big") + frame)
        tlog_files[direction].flush()


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
        if msg.get_srcSystem() == 1 and msg.get_type() in counts:
            with counts_lock:
                counts[msg.get_type()] += 1
        if msg.get_type() == "COMMAND_ACK":
            sent_at = commands_sent.pop(msg.command, None)
            latency = "" if sent_at is None else f" after {(time.monotonic() - sent_at) * 1000:.0f} ms"
            log(
                f"VEHICLE COMMAND_ACK from {msg.get_srcSystem()}/{msg.get_srcComponent()} command={msg.command} "
                f"result={msg.result} target={msg.target_system}/{msg.target_component}{latency}"
            )
        if downlink_on.is_set():
            frame = bytes(msg.get_msgbuf())
            udp.sendto(frame, app)
            record("down", frame)


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
        except ConnectionResetError:
            # Windows reports an earlier ICMP "port unreachable" (emulator not up yet) on the next recvfrom (#42).
            continue
        sitl.write(data)
        for msg in parser.parse_buffer(data) or []:
            if msg.get_type() != "BAD_DATA":
                record("up", bytes(msg.get_msgbuf()))
            if msg.get_type() == "COMMAND_LONG":
                commands_sent[msg.command] = time.monotonic()
                log(
                    f"APP COMMAND_LONG seq={msg.get_seq()} sys={msg.get_srcSystem()} comp={msg.get_srcComponent()} "
                    f"to {msg.target_system}/{msg.target_component} command={msg.command} "
                    f"params={[msg.param1, msg.param2, msg.param3, msg.param4, msg.param5, msg.param6, msg.param7]} "
                    f"confirmation={msg.confirmation}"
                )
                continue
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


def rates(every_s: float) -> None:
    """Logs the vehicle's rate of each requested message over the last [every_s] seconds."""
    while not stop.wait(every_s):
        with counts_lock:
            snapshot = dict(counts)
            for name in counts:
                counts[name] = 0
        log("RATES " + " ".join(f"{name}={snapshot[name] / every_s:.1f}Hz" for name in REQUESTED))


def main() -> None:
    global log_file
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sitl", default="tcp:127.0.0.1:5763")
    parser.add_argument("--app", default="127.0.0.1:14561", help="host:port redirected into the emulator")
    parser.add_argument("--log", default="relay-14.log")
    parser.add_argument("--background", action="store_true", help="control by file instead of the terminal")
    parser.add_argument("--tlog", help="write the frames forwarded each way to <prefix>.down.tlog and <prefix>.up.tlog")
    parser.add_argument("--rates-every", type=float, default=5.0, help="seconds between RATES lines")
    args = parser.parse_args()
    host, port = args.app.rsplit(":", 1)
    app = (host, int(port))
    log_file = open(args.log, "a", encoding="utf-8")
    if args.tlog:
        for direction in ("down", "up"):
            tlog_files[direction] = open(f"{args.tlog}.{direction}.tlog", "ab")

    # Reconnects when the autopilot reboots (#41), so the app sees the boot frames.
    sitl = mavutil.mavlink_connection(args.sitl, source_system=253, dialect="common", autoreconnect=True)
    log(f"waiting for SITL on {args.sitl}...")
    sitl.wait_heartbeat(timeout=30)
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("127.0.0.1", 0))
    udp.settimeout(0.5)
    log(f"relay up: SITL {args.sitl} -> app {app}; app replies come back to {udp.getsockname()}")

    for target in (lambda: downlink(sitl, udp, app), lambda: uplink(sitl, udp), lambda: rates(args.rates_every)):
        threading.Thread(target=target, daemon=True).start()
    if not args.background:
        for line in sys.stdin:
            key = line.strip().lower()
            if key == "d":
                set_downlink(not downlink_on.is_set())
            elif key == "s":
                stop_stream(sitl, ATTITUDE_ID)
            elif key == "b":
                stop_stream(sitl, SYS_STATUS_ID)
            elif key == "q":
                break
    else:
        control = args.log + ".downlink-off"
        stops = {args.log + ".stop-attitude": ATTITUDE_ID, args.log + ".stop-sys-status": SYS_STATUS_ID}
        log(f"background: create {control} to cut the downlink, delete it to restore it")
        log(f"background: create one of {list(stops)} to stop that stream once")
        while True:
            set_downlink(not os.path.exists(control))
            for path, message_id in stops.items():
                if os.path.exists(path):
                    os.remove(path)
                    stop_stream(sitl, message_id)
            time.sleep(CONTROL_POLL_S)
    stop.set()


def stop_stream(sitl, message_id: int) -> None:
    """Asks the vehicle to stop sending [message_id] on this channel, as if its stream had been lost."""
    commands_sent[MAV_CMD_SET_MESSAGE_INTERVAL] = time.monotonic()
    sitl.mav.command_long_send(
        VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, MAV_CMD_SET_MESSAGE_INTERVAL, 0,
        message_id, DISABLE_INTERVAL, 0, 0, 0, 0, 0,
    )
    log(f"STREAM {message_id} STOPPED by the relay (the app should request it again once it is stale and at least 5 s after its last request)")


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
