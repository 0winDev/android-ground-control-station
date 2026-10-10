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

**A. Mission Planner simulator (Windows) — verified setup, issue #5**

Verified with Mission Planner 1.3.83 and ArduCopter V4.7.2-beta1 (the Spanish UI translates the
labels; mode names stay in English).

1. Mission Planner → *Simulation* → *Multirotor*. It downloads and runs the ArduCopter SITL binary
   and connects over TCP 5760. Check the version it reports: stable may not be selectable (it loaded
   V4.8.0-dev first, then V4.7.2-beta1); record the exact version with any capture.
2. **QGroundControl connects to SITL directly**, not through Mission Planner: *Application Settings →
   Comm Links → Add*, type **TCP**, server `127.0.0.1`, port **5762** (SITL also listens on 5763).
3. **Emulator:** `adb emu redir add udp:14550:14550`, then in Mission Planner press **Ctrl+F** →
   *MAVLink Mirror*: Type **UDP**, Direction **Outbound**, Host `127.0.0.1`, Port `14550`, **Write
   unchecked** (read-only) → *Go* (shows *Started*). Check reception from the host:
   `adb shell "timeout 10 nc -u -l -p 14550 > /data/local/tmp/udp.bin; ls -l /data/local/tmp/udp.bin"`.
4. Mission Planner records every session as a `.tlog` under
   `Documents\Mission Planner\logs\SITL\QUADROTOR\1\`; the file is locked until you press
   *Disconnect*.

Mission Planner pitfalls:
- The mirror's UDP type binds the **same local port** it sends to (`0.0.0.0:<port>`), so QGroundControl
  cannot listen on that port on the same PC — hence QGC over TCP 5762.
- Use **one** mirror window: with two, the first one stopped delivering.
- Closing a mirror window does not stop it; restart Mission Planner to clear mirrors.
- QGroundControl's UDP AutoConnect binds host port 14550 on start, so `adb emu redir add
  udp:14550:14550` silently fails (`adb emu redir list` shows nothing). Instead of fighting it,
  redirect a free host port to the app's port, e.g. `adb emu redir add udp:14560:14550`, and point
  the mirror at `127.0.0.1:14560`. With two emulators, pick the target with `adb -s <serial>`.
- Check the result from the host: `netstat -ano` shows who holds 14550, and the HUD count can be
  read with `adb shell uiautomator dump` (set `MSYS_NO_PATHCONV=1` in Git Bash so device paths are
  not rewritten).

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

The redirect occupies host port 14550, so on the same machine QGroundControl must use something else:
with Mission Planner, TCP 5762 straight to SITL (see A); with `sim_vehicle.py`, a UDP comm link on
14551 plus `--out=udp:127.0.0.1:14551`.

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
  small binary fixtures under `core/mavlink/src/test/resources/` with
  `tools/fixtures/extract_fixtures.py` (pymavlink, offline; never a project dependency). See
  `core/mavlink/src/test/resources/README.md` for the reference capture and how to regenerate it.
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
