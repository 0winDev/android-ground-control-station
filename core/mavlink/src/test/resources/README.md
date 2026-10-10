# MAVLink test fixtures

Real MAVLink 2 frames captured from ArduPilot SITL, used by the codec tests (read them with
`readFixture("heartbeat/disarmed-stabilize.bin")` from `:core:testing`). Expected values come from the
reference decoder below — **never** from our own codec.

## How they were captured

| | |
|---|---|
| Date | 2026-10-08 (issue #5) |
| Simulator | Mission Planner 1.3.83 (build 1.3.9384.38258) → Simulation → Multirotor |
| Autopilot | **ArduCopter V4.7.2-beta1** (`383331ff`), from AUTOPILOT_VERSION in the log: `flight_sw_version` 4.7.2, type 128 = `FIRMWARE_VERSION_TYPE_BETA`. Mission Planner did not offer the stable channel (V4.7.1) for SITL, so the capture uses the beta. |
| Session | Started disarmed in STABILIZE; then GUIDED, LOITER, GUIDED; armed in GUIDED for ~10 s; disarmed. |
| Log | `.tlog` recorded by Mission Planner (`Documents\Mission Planner\logs\SITL\QUADROTOR\1\2026-10-08 13-17-19.tlog`), not committed; `heartbeat/session.tlog` is a 65 s excerpt of it. |
| Routing | SITL TCP 5760 → Mission Planner (records the `.tlog`, mirrors UDP to the emulator on 14550); SITL TCP 5762 → QGroundControl directly. |
| Reference decoder | **pymavlink 2.4.50** (`mavutil` + `dialects.v20.common`); message lengths from pymavlink's bundled `message_definitions/v1.0/common.xml`. |
| Cross-check | QGroundControl's MAVLink Inspector showed HEARTBEAT `2 3 81 0 3 3` for a disarmed STABILIZE vehicle, identical to `disarmed-stabilize.bin`. |

Regenerate (same bytes for the same log):

```bash
py -m pip install --user pymavlink==2.4.50
py tools/fixtures/extract_fixtures.py "<path>/2026-10-08 13-17-19.tlog" --excerpt-from 395 --excerpt-seconds 65
```

Every `.bin` is one complete frame exactly as on the wire: 10-byte header + LEN payload bytes + 2-byte
checksum, no signature (incompatibility flags `0x00`) and no `.tlog` timestamp.

## CRC_EXTRA

The checksum tests (`MavlinkCrcTest`) need each message's CRC_EXTRA. The values were computed from
pymavlink 2.4.50's bundled definitions with `pymavlink.generator.mavparse` and match its generated
`dialects.v20.common` constants. HEARTBEAT is defined in `minimal.xml` (included by `common.xml`), so
parsing `common.xml` alone does not find it.

| Message (id) | Definition | CRC_EXTRA |
|---|---|---|
| HEARTBEAT (0) | `minimal.xml` | 50 |
| SYS_STATUS (1) | `common.xml` | 124 |
| SERVO_OUTPUT_RAW (36) | `common.xml` | 222 |
| POWER_STATUS (125) | `common.xml` | 203 |

With these values, pymavlink's `x25crc` over bytes 1 to 10 + LEN of each `.bin`, then CRC_EXTRA, equals
the frame's checksum bytes for all seven fixtures (truncated ones included: the checksum covers the
bytes on the wire).

## `heartbeat/`

HEARTBEAT from the autopilot (system 1, component 1). Field order on the wire is by type size:
`custom_mode` (uint32) first, then `type`, `autopilot`, `base_mode`, `system_status`, `mavlink_version`.
HEARTBEAT never truncates: its last wire byte is `mavlink_version = 3`.

| File | t (s) | seq | type | autopilot | base_mode | custom_mode | system_status | mavlink_version |
|---|---|---|---|---|---|---|---|---|
| `disarmed-stabilize.bin` | 0.8 | 59 | 2 | 3 | 81 | 0 (Stabilize) | 3 | 3 |
| `disarmed-guided.bin` | 400.9 | 18 | 2 | 3 | 89 | 4 (Guided) | 3 | 3 |
| `disarmed-loiter.bin` | 408.9 | 173 | 2 | 3 | 89 | 5 (Loiter) | 3 | 3 |
| `armed-guided.bin` | 442.1 | 122 | 2 | 3 | 217 | 4 (Guided) | 3 | 3 |

- `type` 2 = `MAV_TYPE_QUADROTOR`; `autopilot` 3 = `MAV_AUTOPILOT_ARDUPILOTMEGA`; `system_status` 3 =
  `MAV_STATE_STANDBY`.
- `base_mode` 81 = `CUSTOM_MODE_ENABLED` + `STABILIZE_ENABLED` + `MANUAL_INPUT_ENABLED`; 89 adds
  `GUIDED_ENABLED`; 217 adds `SAFETY_ARMED`.
- `custom_mode` is ArduCopter-specific (ADR 0003): 0 = Stabilize, 4 = Guided, 5 = Loiter, per the
  [ArduCopter `FLTMODE1` parameter documentation](https://ardupilot.org/copter/docs/parameters.html#fltmode1).

Raw bytes:

```
disarmed-stabilize.bin  fd 09 00 00 3b 01 01 00 00 00 00 00 00 00 02 03 51 03 03 8e b2
disarmed-guided.bin     fd 09 00 00 12 01 01 00 00 00 04 00 00 00 02 03 59 03 03 1f 72
disarmed-loiter.bin     fd 09 00 00 ad 01 01 00 00 00 05 00 00 00 02 03 59 03 03 23 72
armed-guided.bin        fd 09 00 00 7a 01 01 00 00 00 04 00 00 00 02 03 d9 03 03 b6 d2
```

`session.tlog` is t = 395–460 s of the original log copied record by record (5,524 messages, 231,892
bytes): each record is an 8-byte big-endian timestamp in microseconds followed by one frame. It covers
the mode changes and the arm/disarm, and also contains MAVLink 1 frames and ArduPilot-dialect messages.

## `truncated/`

MAVLink 2 senders drop trailing zero bytes from the payload; the receiver must zero-fill the payload
back to the full length before decoding. These real frames have LEN below the message's full length in
`common.xml`:

| File | Message (id) | LEN on the wire | Min LEN (no extensions) | Full LEN | What was cut |
|---|---|---|---|---|---|
| `sys_status.bin` | SYS_STATUS (1) | 31 | 31 | 43 | all 12 bytes of extension fields |
| `power_status.bin` | POWER_STATUS (125) | 2 | 6 | 6 | base fields `Vservo` and `flags` (no extensions) |
| `servo_output_raw.bin` | SERVO_OUTPUT_RAW (36) | 12 | 21 | 37 | base fields `servo5_raw`…`servo8_raw` and `port` (last on the wire), and all extensions |

Decoded values (pymavlink, after zero-fill):

- `sys_status.bin`: onboard_control_sensors_present=1395784719, onboard_control_sensors_enabled=1375837199,
  onboard_control_sensors_health=1158781963, load=0, voltage_battery=0, current_battery=-1,
  battery_remaining=-1, drop_rate_comm=0, errors_comm=0, errors_count1..4=0; extensions all 0.
- `power_status.bin`: Vcc=5000, Vservo=0, flags=0.
- `servo_output_raw.bin`: time_usec=2619785, port=0, servo1_raw..servo4_raw=1000, servo5_raw..servo8_raw=0;
  extensions all 0.

Raw bytes:

```
sys_status.bin        fd 1f 00 00 15 01 01 01 00 00 0f fc 31 53 0f 9c 01 52 0b 9c 11 45 00 00 00 00 ff ff
                      00 00 00 00 00 00 00 00 00 00 00 00 ff ff 0d
power_status.bin      fd 02 00 00 16 01 01 7d 00 00 88 13 ad 25
servo_output_raw.bin  fd 0c 00 00 1d 01 01 24 00 00 89 f9 27 00 e8 03 e8 03 e8 03 e8 03 c4 c3
```
