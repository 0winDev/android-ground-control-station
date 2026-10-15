#!/usr/bin/env python3
"""Drives ArduPilot SITL's battery and logs what the vehicle reports about it (manual SITL tool, #41).

Connects to a SITL TCP port of its own (Mission Planner's simulator: 5762; the app's relay uses 5763) and logs,
decoded by pymavlink (the reference decoder), every change of SYS_STATUS's battery: the battery bit
(MAV_SYS_STATUS_SENSOR_BATTERY) in `onboard_control_sensors_present`, `_enabled` and `_health`, `voltage_battery`,
`current_battery` and `battery_remaining`; at most once per second while only the readings move. It also logs
the vehicle's mode and armed changes and its STATUSTEXT, which is where ArduPilot announces a battery failsafe.

Commands, one per line, written to <log>.cmd (the tool runs them in order and deletes the file):
  get NAME             read a parameter
  set NAME VALUE       set a parameter and log the value the vehicle sends back (PARAM_VALUE)
  mode NAME            change flight mode (e.g. GUIDED, LAND)
  arm | disarm         MAV_CMD_COMPONENT_ARM_DISARM
  takeoff ALT          MAV_CMD_NAV_TAKEOFF to ALT metres (GUIDED and armed first)
  version              log AUTOPILOT_VERSION (record it with every session, #39)
  reboot               reboot the autopilot (BATT_MONITOR only takes effect after it; the connection drops)

SITL only: never point it at a real vehicle.

pymavlink is only a tool dependency (py -m pip install --user pymavlink).

Usage:
  python sitl_battery.py --sitl tcp:127.0.0.1:5762 --log battery-41.log
  echo "get BATT_MONITOR" > battery-41.log.cmd
"""

import argparse
import os
import time

from pymavlink import mavutil

# common.xml: MAV_SYS_STATUS_SENSOR_BATTERY (0x02000000), SYS_STATUS (1), MAV_CMD_SET_MESSAGE_INTERVAL (511),
# MAV_CMD_COMPONENT_ARM_DISARM (400), MAV_CMD_NAV_TAKEOFF (22), MAV_PARAM_TYPE_REAL32 (9),
# MAV_CMD_REQUEST_MESSAGE (512) and AUTOPILOT_VERSION (148, standard.xml), MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN (246)
# with REBOOT_SHUTDOWN_ACTION_REBOOT (1), MAV_TYPE_GCS (6), MAV_AUTOPILOT_INVALID (8).
MAV_SYS_STATUS_SENSOR_BATTERY = 0x0200_0000
SYS_STATUS_ID = 1
MAV_CMD_SET_MESSAGE_INTERVAL = 511
MAV_CMD_COMPONENT_ARM_DISARM = 400
MAV_CMD_NAV_TAKEOFF = 22
MAV_PARAM_TYPE_REAL32 = 9
MAV_CMD_REQUEST_MESSAGE = 512
AUTOPILOT_VERSION_ID = 148
MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN = 246
REBOOT_SHUTDOWN_ACTION_REBOOT = 1
MAV_TYPE_GCS = 6
MAV_AUTOPILOT_INVALID = 8
HEARTBEAT_EVERY_S = 1.0
SYS_STATUS_INTERVAL_US = 500_000
VEHICLE_SYSTEM_ID = 1
MAV_COMP_ID_AUTOPILOT1 = 1
# Not 255: the app is the GCS at 255 (REQ-021), and this tool must not look like it.
TOOL_SYSTEM_ID = 246
READINGS_EVERY_S = 1.0
# A HEARTBEAT after this long without one means the autopilot rebooted.
REBOOT_GAP_S = 1.5
# The autopilot forgets this channel's rates when it reboots, sometimes faster than REBOOT_GAP_S shows: ask again.
REQUEST_EVERY_S = 5.0
PARAM_TIMEOUT_S = 5.0
CONTROL_POLL_S = 0.2

log_file = None


def log(line: str) -> None:
    stamp = time.strftime("%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}"
    text = f"[{stamp}] {line}"
    print(text, flush=True)
    if log_file:
        log_file.write(text + "\n")
        log_file.flush()


def bit(mask: int) -> int:
    return 1 if mask & MAV_SYS_STATUS_SENSOR_BATTERY else 0


class Watcher:
    """Logs the battery whenever its bits or remaining energy change, and its readings at most once per second."""

    def __init__(self) -> None:
        self.last_flags = None
        self.last_readings = None
        self.last_logged_at = 0.0
        self.last_state = None
        self.last_heartbeat_at = None

    def on_message(self, msg) -> None:
        kind = msg.get_type()
        if msg.get_srcSystem() != VEHICLE_SYSTEM_ID or msg.get_srcComponent() != MAV_COMP_ID_AUTOPILOT1:
            return
        if kind == "SYS_STATUS":
            self.on_sys_status(msg)
        elif kind == "HEARTBEAT":
            now = time.monotonic()
            if self.last_heartbeat_at is not None and now - self.last_heartbeat_at > REBOOT_GAP_S:
                log(f"VEHICLE heard again after {now - self.last_heartbeat_at:.1f} s")
            self.last_heartbeat_at = now
            state = (mavutil.mode_string_v10(msg), bool(msg.base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED))
            if state != self.last_state:
                log(f"VEHICLE mode={state[0]} armed={state[1]}")
                self.last_state = state
        elif kind == "STATUSTEXT":
            log(f"VEHICLE STATUSTEXT: {msg.text}")

    def on_sys_status(self, msg) -> None:
        flags = (
            bit(msg.onboard_control_sensors_present),
            bit(msg.onboard_control_sensors_enabled),
            bit(msg.onboard_control_sensors_health),
            msg.battery_remaining,
        )
        readings = (msg.voltage_battery, msg.current_battery)
        now = time.monotonic()
        changed = flags != self.last_flags
        due = readings != self.last_readings and now - self.last_logged_at >= READINGS_EVERY_S
        if changed or due:
            log(
                f"BATTERY present={flags[0]} enabled={flags[1]} health={flags[2]} remaining={flags[3]} "
                f"voltage_mV={readings[0]} current_cA={readings[1]}" + ("  (bits or remaining changed)" if changed else "")
            )
            self.last_flags = flags
            self.last_readings = readings
            self.last_logged_at = now


def pump(sitl, watcher: Watcher, until: float, wanted=None):
    """Feeds every message to [watcher] until [until]; returns the first one [wanted] accepts, or None."""
    while time.monotonic() < until:
        msg = sitl.recv_match(blocking=True, timeout=0.1)
        if msg is None:
            continue
        watcher.on_message(msg)
        if wanted and wanted(msg):
            return msg
    return None


def param_value(sitl, watcher: Watcher, name: str):
    return pump(
        sitl, watcher, time.monotonic() + PARAM_TIMEOUT_S,
        lambda m: m.get_type() == "PARAM_VALUE" and m.param_id == name,
    )


def run(sitl, watcher: Watcher, line: str) -> None:
    words = line.split()
    if not words:
        return
    log(f"COMMAND {line}")
    command = words[0].lower()
    if command == "get":
        sitl.mav.param_request_read_send(VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, words[1].encode(), -1)
        reply = param_value(sitl, watcher, words[1])
        log(f"PARAM {words[1]} = {reply.param_value if reply else 'no answer'}")
    elif command == "set":
        sitl.mav.param_set_send(
            VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, words[1].encode(), float(words[2]), MAV_PARAM_TYPE_REAL32,
        )
        reply = param_value(sitl, watcher, words[1])
        log(f"PARAM {words[1]} set, vehicle reports {reply.param_value if reply else 'no answer'}")
    elif command == "mode":
        sitl.set_mode(words[1].upper())
    elif command in ("arm", "disarm"):
        sitl.mav.command_long_send(
            VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, MAV_CMD_COMPONENT_ARM_DISARM, 0,
            1 if command == "arm" else 0, 0, 0, 0, 0, 0, 0,
        )
    elif command == "takeoff":
        sitl.mav.command_long_send(
            VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, MAV_CMD_NAV_TAKEOFF, 0,
            0, 0, 0, 0, 0, 0, float(words[1]),
        )
    elif command == "reboot":
        sitl.mav.command_long_send(
            VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN, 0,
            REBOOT_SHUTDOWN_ACTION_REBOOT, 0, 0, 0, 0, 0, 0,
        )
    elif command == "version":
        sitl.mav.command_long_send(
            VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, MAV_CMD_REQUEST_MESSAGE, 0,
            AUTOPILOT_VERSION_ID, 0, 0, 0, 0, 0, 0,
        )
        reply = pump(
            sitl, watcher, time.monotonic() + PARAM_TIMEOUT_S, lambda m: m.get_type() == "AUTOPILOT_VERSION",
        )
        if reply is None:
            log("AUTOPILOT_VERSION: no answer")
            return
        v = reply.flight_sw_version
        log(
            f"AUTOPILOT_VERSION {v >> 24 & 0xFF}.{v >> 16 & 0xFF}.{v >> 8 & 0xFF} type={v & 0xFF} "
            f"git={bytes(reply.flight_custom_version).decode('ascii', 'replace')}"
        )
        return
    else:
        log(f"unknown command: {line}")
        return
    if command in ("mode", "arm", "disarm", "takeoff", "reboot"):
        ack = pump(sitl, watcher, time.monotonic() + PARAM_TIMEOUT_S, lambda m: m.get_type() == "COMMAND_ACK")
        log(f"ACK {f'command={ack.command} result={ack.result}' if ack else 'none'}")


def request_sys_status(sitl) -> None:
    """ArduPilot keeps rates per channel (#36) and forgets them on reboot: asks for SYS_STATUS on this one."""
    sitl.mav.command_long_send(
        VEHICLE_SYSTEM_ID, MAV_COMP_ID_AUTOPILOT1, MAV_CMD_SET_MESSAGE_INTERVAL, 0,
        SYS_STATUS_ID, SYS_STATUS_INTERVAL_US, 0, 0, 0, 0, 0,
    )


def main() -> None:
    global log_file
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sitl", default="tcp:127.0.0.1:5762")
    parser.add_argument("--log", default="battery-41.log")
    args = parser.parse_args()
    log_file = open(args.log, "a", encoding="utf-8")
    commands = args.log + ".cmd"

    sitl = mavutil.mavlink_connection(args.sitl, source_system=TOOL_SYSTEM_ID, dialect="common", autoreconnect=True)
    log(f"waiting for SITL on {args.sitl}...")
    sitl.wait_heartbeat(timeout=30)
    log(f"up on {args.sitl}; write commands to {commands}")
    watcher = Watcher()
    heartbeat_at = 0.0
    requested_at = 0.0
    while True:
        if time.monotonic() - requested_at >= REQUEST_EVERY_S:
            request_sys_status(sitl)
            requested_at = time.monotonic()
        # ArduPilot only streams to a channel it hears from: without this, nothing arrives after a reboot.
        if time.monotonic() - heartbeat_at >= HEARTBEAT_EVERY_S:
            sitl.mav.heartbeat_send(MAV_TYPE_GCS, MAV_AUTOPILOT_INVALID, 0, 0, 0)
            heartbeat_at = time.monotonic()
        pump(sitl, watcher, time.monotonic() + CONTROL_POLL_S)
        if os.path.exists(commands):
            with open(commands, encoding="utf-8") as f:
                lines = f.read().splitlines()
            os.remove(commands)
            for line in lines:
                run(sitl, watcher, line.strip())


if __name__ == "__main__":
    main()
