---
name: sitl-verify
description: |
  Verify GCS (Ground Control Station) against ArduPilot SITL and compare with QGroundControl:
  start SITL (Mission Planner simulator on Windows, or sim_vehicle.py in WSL/Linux), route its
  MAVLink stream to the app (device or emulator) and to QGroundControl, compare values, and
  capture real packets as test fixtures. Trigger on: "SITL", "simulator", "verify against
  QGroundControl", "compare with QGC", "capture packets", "tlog", "fixtures", "does it work with
  ArduPilot".
---

# SITL verification — GCS

Use this whenever a change touches protocol, telemetry or commands (CONTRIBUTING §6). The output
is a short report that goes into the PR's "How it was tested".

Safety: SITL only. Never send commands or missions to a real vehicle without a physical
transmitter and a pilot ready to take over (README disclaimer).

## 1. Start SITL (ArduCopter)

Pick one; the owner's working option is recorded in `CLAUDE.md` → "Lessons learned" once chosen.

**A. Mission Planner simulator (Windows, quickest)**
1. Mission Planner → *Simulation* tab → *Multirotor*.
2. Forward its MAVLink stream to the app and QGroundControl with Mission Planner's MAVLink
   mirror/forwarding (UDP to the target IP and port). Check the Mission Planner docs for the exact
   menu in the installed version.

**B. `sim_vehicle.py` (WSL2 or Linux)**, from an ArduPilot checkout (see the ArduPilot SITL docs):
```bash
sim_vehicle.py -v ArduCopter --console --map \
  --out=udp:<DEVICE_IP>:14550 \
  --out=udp:127.0.0.1:14551
```
MAVProxy writes the session to `mav.tlog` in the working directory.

## 2. Connect the app

| Target | How |
|---|---|
| Physical device (recommended) | Same Wi-Fi as the PC. Send SITL output to `<DEVICE_IP>:14550`. |
| Emulator | Send SITL output to the host's `127.0.0.1:14550`, then forward it into the emulator: `adb emu redir add udp:14550:14550`. |

The redirect occupies host port 14550, so on the same machine QGroundControl must listen on another
port (e.g. add a UDP comm link on 14551 in QGroundControl and add `--out=udp:127.0.0.1:14551`).

## 3. Compare with QGroundControl

QGroundControl is the reference. For each value the change touches, record app vs QGC at the same
moment (screenshot side by side or MAVLink Inspector in QGC):

| Check | Expected |
|---|---|
| Connection, system ID, vehicle type, flight mode, armed state | Identical |
| Position, altitude, heading, speeds, attitude, battery | Identical within display rounding |
| Command (arm/takeoff/RTL/land) | Same COMMAND_ACK result as QGC shows; vehicle reacts the same |
| Link loss (stop SITL or cut the forwarding) | App shows "link lost" within the configured timeout; ArduPilot's GCS failsafe (`FS_GCS_ENABLE`) acts as configured |
| Recovery | App reconnects by itself when the stream comes back |

## 4. Capture packets for tests

- Keep the `.tlog` of the session (MAVProxy's `mav.tlog`, or a telemetry log from QGC/Mission
  Planner).
- Extract only the frames a test needs (a few HEARTBEATs, one of each message under test) into
  small binary fixtures under `core/mavlink/src/test/resources/<message>/`. `pymavlink` can be used
  as an offline tool to locate and dump raw frames (`msg.get_msgbuf()`), never as a project
  dependency.
- Add a `README.md` next to the fixtures: SITL version, vehicle, how it was captured, and the
  decoded expected values from the reference tool.
- Keep fixtures small (the `check-added-large-files` hook caps files at 2 MB).

## 5. Report (paste into the PR)

```
SITL: <Mission Planner sim | sim_vehicle.py> · ArduCopter <version> · <device|emulator API x>
Compared with QGroundControl <version>:
- <value/behavior>: app <x> · QGC <y> · OK/DIFF
Link loss: <timeout observed> · recovery: <yes/no>
Fixtures captured: <paths or "none">
```

Any DIFF blocks the PR unless explained and accepted in Notes.
