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

## `derived/` and `unknown/`

Parser fixtures (#9). `derived/` frames are built from `heartbeat/disarmed-stabilize.bin` **by pymavlink
2.4.50**, never by our codec; `unknown/` holds a real frame extracted from `heartbeat/session.tlog`.
Regenerate (same bytes):

```bash
py tools/fixtures/derive_fixtures.py
```

| File | How it was made | Used for |
|---|---|---|
| `derived/heartbeat-signed.bin` | The HEARTBEAT re-packed with the same seq/sysid/compid and signed by pymavlink: incompat `0x01`, link ID 0, timestamp `0x12345678`, test key `00 01 02 … 1f` (32 bytes, test only). pymavlink verifies the signature before the file is written. | A signed frame: the 13-byte signature is extracted (not verified until v0.6) |
| `derived/heartbeat-unknown-incompat.bin` | Incompat flags set to `0x02`, checksum recomputed with pymavlink's `x25crc` and CRC_EXTRA | Unsupported incompatibility flag: dropped (REQ-014) |
| `derived/heartbeat-unknown-compat.bin` | Compat flags set to `0x80`, checksum recomputed the same way; pymavlink still decodes it | Unknown compatibility flag: ignored, the frame is accepted |
| `derived/heartbeat-empty-payload.bin` | The HEARTBEAT header with LEN 0 and no payload, checksum recomputed with pymavlink's `x25crc` and CRC_EXTRA | Invalid frame: [serialization.html](https://mavlink.io/en/guide/serialization.html) says "The first byte of the payload is never truncated", so LEN 0 is dropped and counted |
| `derived/heartbeat-extra-byte.bin` | The HEARTBEAT with one unknown extension byte `0x2A` appended (LEN 10), checksum recomputed by pymavlink; pymavlink decodes it with the same field values as the original | A payload longer than the definition: the extra bytes are ignored ([define_xml_element.html](https://mavlink.io/en/guide/define_xml_element.html), Message Extensions: "the fields will not be seen") (#10) |
| `derived/heartbeat-max-custom-mode.bin` | The HEARTBEAT re-packed by pymavlink with `custom_mode` = `0xFFFFFFFF`; pymavlink decodes 4294967295 | `custom_mode` is a uint32 and must not come out signed (#10) |
| `unknown/attitude-fd-in-payload.bin` | Real ATTITUDE (id 30, LEN 28, seq 42) from `session.tlog`, the first whose payload contains `0xFD` | A frame with an unknown message ID is skipped whole by its LEN, so the `0xFD` inside it starts no false frame |
| `derived/attitude-signed.bin` | `unknown/attitude-fd-in-payload.bin` re-packed and signed by pymavlink with the same test key, link ID and timestamp; signature verified by pymavlink | A signed frame with a message ID the tests treat as unknown: skipped whole, signature included |
| `derived/session-stream.bin` | `session.tlog` without the 8-byte timestamps: every frame (MAVLink 1 and 2) in order, 187,700 bytes, rebuilt from pymavlink's decoded frames (no BAD_DATA) | The whole session as a parser input, cut into datagrams by the tests |

Raw bytes:

```
derived/heartbeat-signed.bin            fd 09 01 00 3b 01 01 00 00 00 00 00 00 00 02 03 51 03 03 69 4a
                                        00 78 56 34 12 00 00 5a a5 3a fc 01 b6
derived/heartbeat-unknown-incompat.bin  fd 09 02 00 3b 01 01 00 00 00 00 00 00 00 02 03 51 03 03 51 4b
derived/heartbeat-unknown-compat.bin    fd 09 00 80 3b 01 01 00 00 00 00 00 00 00 02 03 51 03 03 86 f5
derived/heartbeat-empty-payload.bin     fd 00 00 00 3b 01 01 00 00 00 b1 21
derived/heartbeat-extra-byte.bin        fd 0a 00 00 3b 01 01 00 00 00 00 00 00 00 02 03 51 03 03 2a ef 4d
derived/heartbeat-max-custom-mode.bin   fd 09 00 00 3b 01 01 00 00 00 ff ff ff ff 02 03 51 03 03 85 52
unknown/attitude-fd-in-payload.bin      fd 1c 00 00 2a 01 01 1e 00 00 3f 0f 06 00 c9 b0 89 ba e6 32 a8 ba
                                        b1 39 3a bc 50 f7 69 b9 00 fd 67 b9 c0 a8 4a ba 42 9f
derived/attitude-signed.bin             fd 1c 01 00 2a 01 01 1e 00 00 3f 0f 06 00 c9 b0 89 ba e6 32 a8 ba
                                        b1 39 3a bc 50 f7 69 b9 00 fd 67 b9 c0 a8 4a ba 1d 25 00 78 56 34 12
                                        00 00 34 7e c3 65 29 bc
```

`derived/session-stream.bin` as a parser input (counts from pymavlink, printed by `derive_fixtures.py`): 5,524 frames, 5,522 MAVLink 2 frames (all incompat `0x00`, 39 message
IDs including ArduPilot-dialect ones), 133 of them HEARTBEAT, plus 2 MAVLink 1 frames (`0xFE`, COMMAND_LONG, 41 bytes each, no `0xFD` inside). With a
lookup that knows only HEARTBEAT, the other 5,389 MAVLink 2 frames are unknown message IDs.
With the full v0.1 message table (#10): 133 HEARTBEAT, 325 SYS_STATUS, 130 SERVO_OUTPUT_RAW and 325
POWER_STATUS frames are known, and the other 4,609 MAVLink 2 frames are unknown message IDs.
