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
`dialects.v20.common` constants. HEARTBEAT is defined in `minimal.xml` and GLOBAL_POSITION_INT in
`standard.xml` (both included by `common.xml`), so parsing `common.xml` alone finds neither.

| Message (id) | Definition | CRC_EXTRA |
|---|---|---|
| HEARTBEAT (0) | `minimal.xml` | 50 |
| SYS_STATUS (1) | `common.xml` | 124 |
| SERVO_OUTPUT_RAW (36) | `common.xml` | 222 |
| POWER_STATUS (125) | `common.xml` | 203 |
| COMMAND_LONG (76) | `common.xml` | 152 |
| COMMAND_ACK (77) | `common.xml` | 143 |
| ATTITUDE (30) | `common.xml` | 39 |
| GLOBAL_POSITION_INT (33) | `standard.xml` | 104 |
| VFR_HUD (74) | `common.xml` | 20 |
| GPS_RAW_INT (24) | `common.xml` | 24 |
| STATUSTEXT (253) | `common.xml` | 83 |

ATTITUDE, GLOBAL_POSITION_INT and VFR_HUD were also computed with `mavparse` from `common.xml` and
`standard.xml` on `mavlink/mavlink` master (#37), and SYS_STATUS and GPS_RAW_INT from `common.xml` on master
(#38): same CRC_EXTRA, full length and wire order.

With these values, pymavlink's `x25crc` over bytes 1 to 10 + LEN of each `.bin`, then CRC_EXTRA, equals
the frame's checksum bytes for the seven `heartbeat/` and `truncated/` fixtures and for every COMMAND and
telemetry fixture (truncated ones included: the checksum covers the bytes on the wire).

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

## `derived/`, `unknown/` and `gcs/`

Parser fixtures (#9), vehicle-state fixtures (#11, #12) and encoder fixtures (#14). `derived/` frames are built from the real
`heartbeat/` frames **by pymavlink 2.4.50**, never by our codec; `unknown/` and `gcs/` hold real frames
extracted from `heartbeat/session.tlog`.
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
| `derived/heartbeat-sysid-2.bin` | `heartbeat/disarmed-loiter.bin` re-packed by pymavlink with system ID 2; seq, component and every field unchanged (pymavlink decodes the same fields as the original) | A second vehicle on the link: its state is kept apart from system 1 (REQ-016, #11) |
| `derived/heartbeat-gimbal-component.bin` | `heartbeat/disarmed-stabilize.bin` re-packed by pymavlink from component 154 (`MAV_COMP_ID_GIMBAL`); system, seq and every field unchanged (pymavlink decodes the same fields as the original) | Another component of the same vehicle: its HEARTBEAT does not describe the vehicle nor keep its link alive (#12) |
| `derived/heartbeat-asymmetric-custom-mode.bin` | `heartbeat/disarmed-stabilize.bin` re-packed by pymavlink with `custom_mode` `0x12345678`; every other field unchanged | Encoder: the uint32 is little-endian byte by byte (`78 56 34 12`), so a swapped byte order would show (REQ-021, #14) |
| `derived/heartbeat-trailing-zeros.bin` | `heartbeat/disarmed-stabilize.bin` re-packed by pymavlink with `custom_mode` 5, `base_mode` 0, `system_status` 0 and `mavlink_version` 0 (same seq, system, component, `type` 2 and `autopilot` 3); pymavlink truncates the three trailing zero bytes, so LEN is 6. Not a frame a compliant sender produces: `mavlink_version` is "not writable by user" and always 3 (`minimal.xml`), which is why a real HEARTBEAT never truncates | Encoder framing: trailing zero bytes are truncated, as the specification requires of a MAVLink 2 sender; tested through the internal `MavlinkEncoder.frame` (REQ-021, #14) |
| `derived/heartbeat-all-zero.bin` | The same header with every field 0, re-packed by pymavlink: LEN 1, because the first payload byte is never truncated. Same caveat about `mavlink_version` | Encoder framing: an all-zero payload keeps one byte (#14) |
| `gcs/heartbeat-mission-planner.bin` | Real HEARTBEAT (seq 44) from `session.tlog` sent by Mission Planner: system 255, component 190, `type` 6 = `MAV_TYPE_GCS`, `autopilot` 8 = `MAV_AUTOPILOT_INVALID`, `base_mode` 0, `custom_mode` 0 | A ground station's HEARTBEAT is not a vehicle (#11) |
| `unknown/attitude-fd-in-payload.bin` | Real ATTITUDE (id 30, LEN 28, seq 42) from `session.tlog`, the first whose payload contains `0xFD` | A frame with an unknown message ID is skipped whole by its LEN, so the `0xFD` inside it starts no false frame. ATTITUDE is decoded since #37: it is unknown only to the parser tests' HEARTBEAT-only lookup |
| `unknown/scaled-pressure.bin` | Real SCALED_PRESSURE (id 29, LEN 14, seq 59) from `session.tlog` | A message outside the codec's table: undecoded, payload as received (#37) |
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
derived/heartbeat-sysid-2.bin           fd 09 00 00 ad 02 01 00 00 00 05 00 00 00 02 03 59 03 03 5d aa
derived/heartbeat-gimbal-component.bin  fd 09 00 00 3b 01 9a 00 00 00 00 00 00 00 02 03 51 03 03 da 8e
derived/heartbeat-asymmetric-custom-mode.bin  fd 09 00 00 3b 01 01 00 00 00 78 56 34 12 02 03 51 03 03 f7 30
derived/heartbeat-trailing-zeros.bin    fd 06 00 00 3b 01 01 00 00 00 05 00 00 00 02 03 cc 0d
derived/heartbeat-all-zero.bin          fd 01 00 00 3b 01 01 00 00 00 00 31 66
gcs/heartbeat-mission-planner.bin       fd 09 00 00 2c ff be 00 00 00 00 00 00 00 06 08 00 00 03 fb 18
unknown/attitude-fd-in-payload.bin      fd 1c 00 00 2a 01 01 1e 00 00 3f 0f 06 00 c9 b0 89 ba e6 32 a8 ba
                                        b1 39 3a bc 50 f7 69 b9 00 fd 67 b9 c0 a8 4a ba 42 9f
derived/attitude-signed.bin             fd 1c 01 00 2a 01 01 1e 00 00 3f 0f 06 00 c9 b0 89 ba e6 32 a8 ba
                                        b1 39 3a bc 50 f7 69 b9 00 fd 67 b9 c0 a8 4a ba 1d 25 00 78 56 34 12
                                        00 00 34 7e c3 65 29 bc
unknown/scaled-pressure.bin             fd 0e 00 00 3b 01 01 1d 00 00 e9 0f 06 00 8c 2b 6c 44 00 00 00 00
                                        2e 0c a0 3d
```

`derived/session-stream.bin` as a parser input (counts from pymavlink, printed by `derive_fixtures.py`): 5,524 frames, 5,522 MAVLink 2 frames (all incompat `0x00`, 39 message
IDs including ArduPilot-dialect ones), 133 of them HEARTBEAT, plus 2 MAVLink 1 frames (`0xFE`, COMMAND_LONG, 41 bytes each, no `0xFD` inside). With a
lookup that knows only HEARTBEAT, the other 5,389 MAVLink 2 frames are unknown message IDs.
With the full message table (#10, #35, #37, #38, #66, #96): 133 HEARTBEAT, 325 SYS_STATUS, 325 GPS_RAW_INT, 260
ATTITUDE, 130 GLOBAL_POSITION_INT, 130 SERVO_OUTPUT_RAW, 260 VFR_HUD, 325 POWER_STATUS, 11 COMMAND_LONG, 13
COMMAND_ACK, 1 HOME_POSITION and 2 STATUSTEXT frames are known, and the other 3,607 MAVLink 2 frames are unknown
message IDs.

## `command/` and the COMMAND fixtures in `derived/` (#35)

COMMAND_LONG (76) and COMMAND_ACK (77). `heartbeat/session.tlog` already holds them as MAVLink 2 frames:
11 COMMAND_LONGs sent by Mission Planner (system 255, component 190) and 13 COMMAND_ACKs from ArduCopter
(system 1, component 1). Only the 2 COMMAND_LONGs Mission Planner broadcasts to system 0 are MAVLink 1,
so no new capture was needed. `command/` holds real frames extracted from the session; the `derived/`
ones are re-packed **by pymavlink 2.4.50** from them (same seq, system and component), never by our
codec. Regenerate with `py tools/fixtures/derive_fixtures.py` (same bytes).

Wire layout (offsets from pymavlink's `mavparse` over `common.xml`; field types and order are the same
on `mavlink/mavlink` master, whose descriptions the KDoc quotes):

- COMMAND_LONG, full LEN 33, no extensions: `param1`…`param7` (float, offsets 0–24), `command` (uint16,
  28), `target_system` (30), `target_component` (31), `confirmation` (32). Floats are IEEE-754 single
  precision, little-endian.
- COMMAND_ACK, LEN 3 without extensions, full LEN 10: `command` (uint16, 0), `result` (uint8, 2), then
  the extensions **in XML order, not sorted by size**: `progress` (uint8, 3), `result_param2` (**int32**,
  4), `target_system` (8), `target_component` (9).

| File | How it was made | Fields (pymavlink) | Used for |
|---|---|---|---|
| `command/set-message-interval.bin` | Real COMMAND_LONG (seq 81) from Mission Planner: `MAV_CMD_SET_MESSAGE_INTERVAL` (511) | target 1/1, command 511, confirmation 0, param1 271.0 (message ID), param2 200000.0 (interval in µs), param3–7 0.0. LEN 32: the trailing `confirmation` 0 is truncated | Encoder: byte-identical to a real GCS request that ArduCopter accepted (REQ-022) |
| `command/set-message-interval-ack.bin` | ArduCopter's real COMMAND_ACK (seq 194) to that request | command 511, result 0 (`MAV_RESULT_ACCEPTED`), progress 0, result_param2 0, target 255/190. LEN 10: the non-zero targets keep every extension on the wire | Decoder: a real ACK, extensions included (REQ-022) |
| `command/command-long-confirmation.bin` | Real COMMAND_LONG (seq 138) from Mission Planner: a resend of `MAV_CMD_REQUEST_MESSAGE` (512) | target 1/1, command 512, confirmation 1, param1 259.0, param2–7 0.0. LEN 33 | Encoder: a full-length COMMAND_LONG |
| `derived/command-long-all-params.bin` | The SET_MESSAGE_INTERVAL re-packed with seven distinct params, command `0x1234` (arbitrary, not a real command) and confirmation 7 | params 1.5, -2.25, NaN (`00 00 c0 7f`), 100000.0, -1.0, 0.5, 65535.0; target 1/1 | Encoder: a swapped param slot, float byte order or uint16 byte order shows |
| `derived/command-ack-extensions.bin` | The ACK re-packed with result 5 (`MAV_RESULT_IN_PROGRESS`), progress 50, result_param2 -123456 | command 511, result 5, progress 50, result_param2 -123456 (`c0 1d fe ff`), target 255/190 | Decoder: extension offsets and a signed `result_param2` |
| `derived/command-ack-accepted-no-extensions.bin` | The ACK re-packed with every extension 0 | command 511, result 0, the rest 0. LEN **2**: `result` 0 is a trailing zero too, so it is truncated with the extensions | Decoder: zero-fill restores `result` itself, not only the extensions |
| `derived/command-ack-failed-no-extensions.bin` | The same with result 4 (`MAV_RESULT_FAILED`) | command 511, result 4, the rest 0. LEN 3 | Decoder: an ACK without extensions, as an older sender writes it |
| `derived/command-ack-split-param2.bin` | The ACK re-packed with result_param2 256 and targets 0 | command 511, result 0, progress 0, result_param2 256 (`00 01 00 00`), target 0/0. LEN 6: the payload ends after the second byte of `result_param2` | Decoder: an int32 cut by truncation reads its missing high bytes as zero |
| `derived/command-ack-target-system-only.bin` | The ACK re-packed with target_component 0 | command 511, result 0, progress 0, result_param2 0, target 255/0. LEN 9 | Decoder: only the last extension byte is missing |
| `derived/command-long-broadcast.bin` | The SET_MESSAGE_INTERVAL re-packed to target 0/0 | as `command/set-message-interval.bin`, target 0/0. LEN 30: the zero targets and confirmation are truncated | Encoder: a broadcast COMMAND_LONG truncates after `command` |

Raw bytes:

```
command/set-message-interval.bin                fd 20 00 00 51 ff be 4c 00 00 00 80 87 43 00 50 43 48
                                                00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
                                                00 00 ff 01 01 01 e0 5e
command/set-message-interval-ack.bin            fd 0a 00 00 c2 01 01 4d 00 00 ff 01 00 00 00 00 00 00
                                                ff be 3b 24
command/command-long-confirmation.bin           fd 21 00 00 8a ff be 4c 00 00 00 80 81 43 00 00 00 00
                                                00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
                                                00 02 01 01 01 a6 15
derived/command-long-all-params.bin             fd 21 00 00 51 ff be 4c 00 00 00 00 c0 3f 00 00 10 c0
                                                00 00 c0 7f 00 50 c3 47 00 00 80 bf 00 00 00 3f 00 ff
                                                7f 47 34 12 01 01 07 06 00
derived/command-ack-extensions.bin              fd 0a 00 00 c2 01 01 4d 00 00 ff 01 05 32 c0 1d fe ff
                                                ff be 7c bd
derived/command-ack-accepted-no-extensions.bin  fd 02 00 00 c2 01 01 4d 00 00 ff 01 64 ae
derived/command-ack-failed-no-extensions.bin    fd 03 00 00 c2 01 01 4d 00 00 ff 01 04 ba 34
derived/command-ack-split-param2.bin            fd 06 00 00 c2 01 01 4d 00 00 ff 01 00 00 00 01 28 59
derived/command-ack-target-system-only.bin      fd 09 00 00 c2 01 01 4d 00 00 ff 01 00 00 00 00 00 00
                                                ff 3a 8c
derived/command-long-broadcast.bin              fd 1e 00 00 51 ff be 4c 00 00 00 80 87 43 00 50 43 48
                                                00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
                                                00 00 ff 01 a1 5c
```

## `telemetry/` and the telemetry fixtures in `derived/` (#37)

ATTITUDE (30), GLOBAL_POSITION_INT (33) and VFR_HUD (74). `heartbeat/session.tlog` already holds them from
ArduCopter (system 1, component 1): 260 ATTITUDE, 130 GLOBAL_POSITION_INT and 260 VFR_HUD, all MAVLink 2,
so no new capture was needed. The vehicle stays on the ground at the SITL home in that session (position
fixed, speeds near zero), so the `derived/` frames, re-packed **by pymavlink 2.4.50** from the real ones
(same seq, system and component), add the values a grounded vehicle never sends. Regenerate with
`py tools/fixtures/derive_fixtures.py` (same bytes); it prints the decoded fields below, floats as the
shortest decimal text that is the same float32, so the Kotlin `Float` literals in the tests are bit-exact.

Wire layout (offsets from `mavparse`, the same on pymavlink 2.4.50 and on `mavlink/mavlink` master). None
of the three has extensions; floats are IEEE-754 single precision, little-endian:

- ATTITUDE, full LEN 28: `time_boot_ms` (uint32, 0), then `roll`, `pitch`, `yaw`, `rollspeed`,
  `pitchspeed`, `yawspeed` (float, 4–24). Angles in rad, rates in rad/s.
- GLOBAL_POSITION_INT, full LEN 28: `time_boot_ms` (uint32, 0), `lat`, `lon` (int32 degE7, 4, 8), `alt`,
  `relative_alt` (int32 mm, 12, 16), `vx`, `vy`, `vz` (int16 cm/s, 20, 22, 24), `hdg` (uint16 cdeg, 26;
  UINT16_MAX = unknown). The XML order is already sorted by size, so it is also the wire order.
- VFR_HUD, full LEN 20: `airspeed`, `groundspeed`, `alt`, `climb` (float, 0–12), then `heading` (int16
  deg, 16) and `throttle` (uint16 %, 18). The XML order is airspeed, groundspeed, **heading, throttle**,
  alt, climb: the size sort moves the two 16-bit fields after the floats.

| File | How it was made | Fields (pymavlink) | Used for |
|---|---|---|---|
| `telemetry/attitude.bin` | The first real ATTITUDE (seq 42) of the session; the same frame as `unknown/attitude-fd-in-payload.bin` | time_boot_ms 397119, roll -0.0010504957, pitch -0.0012832552, yaw -0.011366294, rollspeed -0.00022312743, pitchspeed -0.00022124127, yawspeed -0.0007730834. LEN 28 | Decoder: a real ATTITUDE (REQ-024) |
| `telemetry/global-position-int.bin` | The first real GLOBAL_POSITION_INT (seq 53) with a negative `vy` | time_boot_ms 397289, lat -353633514, lon 1491652411, alt 587130, relative_alt -13, vx 1, vy -1, vz 0, hdg 35935. LEN 28 | Decoder: a real one with negative int32 and int16 values (REQ-024) |
| `telemetry/vfr-hud.bin` | The first real VFR_HUD (seq 43) | airspeed 0.023872377, groundspeed 0.023871899, heading 359, throttle 0, alt 587.13, climb -0.00015098024. LEN **18**: `throttle` 0, last on the wire, is truncated | Decoder: a real truncated frame, zero-filled (REQ-013, REQ-024) |
| `derived/attitude-edges.bin` | The ATTITUDE re-packed with `time_boot_ms` 4,000,000,000 and distinct floats, `yaw` the float32 closest to -π | time_boot_ms 4000000000 (`00 28 6b ee`), roll 0.5, pitch -0.25, yaw -3.1415927, rollspeed 1.5, pitchspeed -0.75, yawspeed 0.125. LEN 28 | Decoder: a swapped float slot shows; a `time_boot_ms` above `Int.MAX_VALUE` stays positive |
| `derived/global-position-int-edges.bin` | The GLOBAL_POSITION_INT re-packed with `time_boot_ms` 4,000,000,000, negative altitudes and speeds and `hdg` UINT16_MAX | lat and lon as the real one, alt -12345, relative_alt -500, vx -300, vy 1234, vz -150, hdg 65535. LEN 28 | Decoder: int32 and int16 sign extension; an unknown `hdg` stays 65535, never -1 |
| `derived/vfr-hud-edges.bin` | The VFR_HUD re-packed with climb -2.5 and throttle 65 | as the real one, but throttle 65 and climb -2.5. LEN **19**: throttle 65 is `41 00`, so only its zero high byte is truncated | Decoder: a uint16 cut in half by truncation (REQ-013) |

Raw bytes:

```
telemetry/attitude.bin                  fd 1c 00 00 2a 01 01 1e 00 00 3f 0f 06 00 c9 b0 89 ba e6 32 a8 ba
                                        b1 39 3a bc 50 f7 69 b9 00 fd 67 b9 c0 a8 4a ba 42 9f
telemetry/global-position-int.bin       fd 1c 00 00 35 01 01 21 00 00 e9 0f 06 00 16 fb eb ea 3b cf e8 58
                                        7a f5 08 00 f3 ff ff ff 01 00 ff ff 00 00 5f 8c f8 c7
telemetry/vfr-hud.bin                   fd 12 00 00 2b 01 01 4a 00 00 01 90 c3 3c 00 8f c3 3c 52 c8 12 44
                                        73 50 1e b9 67 01 00 ef
derived/attitude-edges.bin              fd 1c 00 00 2a 01 01 1e 00 00 00 28 6b ee 00 00 00 3f 00 00 80 be
                                        db 0f 49 c0 00 00 c0 3f 00 00 40 bf 00 00 00 3e 04 46
derived/global-position-int-edges.bin   fd 1c 00 00 35 01 01 21 00 00 00 28 6b ee 16 fb eb ea 3b cf e8 58
                                        c7 cf ff ff 0c fe ff ff d4 fe d2 04 6a ff ff ff 90 05
derived/vfr-hud-edges.bin               fd 13 00 00 2b 01 01 4a 00 00 01 90 c3 3c 00 8f c3 3c 52 c8 12 44
                                        00 00 20 c0 67 01 41 b7 c8
```

## SYS_STATUS and GPS_RAW_INT (#38)

SYS_STATUS (1) and GPS_RAW_INT (24), both in `common.xml`. `heartbeat/session.tlog` holds 325 of each from
ArduCopter, all MAVLink 2 and all truncated the same way, so no new capture was needed: every SYS_STATUS has
LEN 31 (the three zero extensions cut) and every GPS_RAW_INT LEN 40 (cut inside `v_acc`). In the session the
battery always reads 12600 mV, 0 cA, 100 %; the only real "unknown" battery is `truncated/sys_status.bin`,
taken at boot from the original log (#5), with the battery bit enabled but not healthy. ArduPilot always sets
`MAV_SYS_STATUS_SENSOR_BATTERY` in `_present`; only `_enabled` says whether a monitor is configured, and
`_health` is clear while the monitor is unhealthy or in failsafe (`libraries/GCS_MAVLink/GCS.cpp`, #41). The
`derived/` frames are re-packed **by pymavlink 2.4.50** from the real ones (same seq, system and component).
Regenerate with `py tools/fixtures/derive_fixtures.py`.

Wire layout (offsets from `mavparse`, the same on pymavlink 2.4.50 and on `mavlink/mavlink` master):

- SYS_STATUS, LEN 31 without extensions, full LEN 43: `onboard_control_sensors_present`, `_enabled`,
  `_health` (uint32 bitmaps, 0, 4, 8), `load` (uint16 d%, 12), `voltage_battery` (uint16 mV, 14),
  `current_battery` (**int16** cA, 16), `drop_rate_comm` (uint16 c%, 18), `errors_comm` (20),
  `errors_count1`…`4` (22–28), `battery_remaining` (**int8** %, 30); then the extensions
  `onboard_control_sensors_present_extended`, `_enabled_extended`, `_health_extended` (uint32, 31, 35, 39).
  The size sort moves `battery_remaining` after the 16-bit fields.
- GPS_RAW_INT, LEN 30 without extensions, full LEN 52: `time_usec` (**uint64** µs, 0), `lat`, `lon`
  (int32 degE7, 8, 12), `alt` (int32 mm MSL, 16), `eph`, `epv` (uint16, 20, 22), `vel` (uint16 cm/s, 24),
  `cog` (uint16 cdeg, 26), `fix_type` (uint8 `GPS_FIX_TYPE`, 28), `satellites_visible` (uint8, 29); then
  the extensions in XML order: `alt_ellipsoid` (int32 mm, 30), `h_acc`, `v_acc` (uint32 mm, 34, 38),
  `vel_acc` (uint32 mm/s, 42), `hdg_acc` (uint32 degE5, 46), `yaw` (uint16 cdeg, 50).

"Unknown" values from `common.xml`: `voltage_battery` UINT16_MAX, `current_battery` and `battery_remaining`
-1; `eph`, `epv`, `vel`, `cog` UINT16_MAX, `satellites_visible` UINT8_MAX; `yaw` 0 when the GPS does not
provide yaw and UINT16_MAX when it should but cannot ("use 36000 for north"). Zero-fill restores a truncated
field as 0: for `yaw` that reads as "not provided", but a restored 0 accuracy or `alt_ellipsoid` is not a
real value, so the data layer must not trust extension fields the sender did not put on the wire.

| File | How it was made | Fields (pymavlink) | Used for |
|---|---|---|---|
| `telemetry/sys-status.bin` | The first real SYS_STATUS (seq 34) of the session with a battery reading | present 1399979055, enabled 1382128687, health 1467063343, load 0, voltage_battery 12600, current_battery 0, battery_remaining 100, drop_rate_comm, errors 0; extensions 0. LEN 31 | Decoder: a real SYS_STATUS, extensions zero-filled (REQ-013, REQ-025) |
| `truncated/sys_status.bin` | Real, from #5 (see `truncated/`) | voltage_battery 0, current_battery -1, battery_remaining -1 (`ff ff`, `ff`). LEN 31 | Decoder: real "unknown" battery values come out signed (REQ-025) |
| `telemetry/gps-raw-int.bin` | The first real GPS_RAW_INT (seq 39) of the session | time_usec 396763000, fix_type 6, lat -353633515, lon 1491652412, alt 587150, eph 121, epv 200, vel 0, cog 0, satellites_visible 10, alt_ellipsoid 587150, h_acc 300, v_acc 300, vel_acc 0, hdg_acc 0, yaw 0. LEN **40**: `v_acc` 300 is `2c 01 00 00`, so its two zero high bytes are cut with everything after it | Decoder: a real GPS_RAW_INT with extensions, one cut inside a field (REQ-013, REQ-025) |
| `derived/sys-status-edges.bin` | `truncated/sys_status.bin` re-packed with a distinct value in every field | sensors as the real one, load 500, voltage_battery 11100, current_battery 1550, battery_remaining 42, drop_rate_comm 250, errors_comm 1, errors_count1…4 2, 3, 4, 5, present_extended `0x01020304`, enabled_extended `0x05060708`, health_extended `0x89ABCDEF` (2309737967). LEN 43 | Decoder: a swapped offset shows; a uint32 above `Int.MAX_VALUE` stays positive |
| `derived/sys-status-unknown-battery.bin` | The same SYS_STATUS with `voltage_battery` UINT16_MAX and the other two battery fields -1 | voltage_battery 65535, current_battery -1, battery_remaining -1, the rest as the real one. LEN 31 | Decoder: the unknown voltage stays 65535, never -1 (REQ-025) |
| `derived/gps-raw-int-edges.bin` | The GPS_RAW_INT re-packed with a distinct value in every field | time_usec `0x0123456789ABCDEF` (81985529216486895), fix_type 3, lat and lon as the real one, alt -12345, eph 150, epv 250, vel 1234, cog 35999, satellites_visible 12, alt_ellipsoid -23456, h_acc `0x89ABCDEF` (2309737967), v_acc 400, vel_acc 50, hdg_acc 12345, yaw 36000. LEN 52 | Decoder: uint64 byte order, int32 sign, uint32 above `Int.MAX_VALUE`, every extension offset |
| `derived/gps-raw-int-unknown.bin` | The GPS_RAW_INT with every "unknown" value and the extensions 0 | time_usec, lat, lon, alt as the real one, fix_type 0 (`GPS_FIX_TYPE_NO_GPS`), eph, epv, vel, cog 65535, satellites_visible 255, extensions 0. LEN **30**: exactly the base fields | Decoder: unsigned sentinels stay positive; extensions restored as 0 (REQ-013, REQ-025) |
| `derived/sys-status-battery-remaining-min.bin` | `telemetry/sys-status.bin` re-packed with `battery_remaining` -128 | as the real one, battery_remaining -128 (`80`). LEN 31 | Decoder: the int8 is sign-extended, not read as 128 or special-cased on `ff` |
| `derived/gps-raw-int-time-2-63.bin` | `telemetry/gps-raw-int.bin` re-packed with `time_usec` 2⁶³ | as the real one, time_usec 9223372036854775808 (`00 00 00 00 00 00 00 80`). LEN 40 | Decoder: the documented limit, a uint64 of 2⁶³ or more comes out as a negative `Long` (here `Long.MIN_VALUE`) |
| `derived/sys-status-battery-zero-volts.bin` | `telemetry/sys-status.bin` re-packed with `voltage_battery` 0 (#41) | as the real one, voltage_battery 0; the battery bit (`MAV_SYS_STATUS_SENSOR_BATTERY`, `0x2000000`) set in present, enabled and health. LEN 31 | Mapper: a healthy, enabled monitor reading 0 V is a 0 V reading |
| `derived/sys-status-no-battery-monitor.bin` | `telemetry/sys-status.bin` re-packed without the battery bit in `_enabled`, `voltage_battery` 0 and the other two battery fields -1 (#41) | enabled 1348574255, voltage_battery 0, current_battery -1, battery_remaining -1, the rest as the real one. LEN 31 | Mapper: ArduPilot without a battery monitor, no reading |
| `derived/sys-status-battery-unhealthy.bin` | `telemetry/sys-status.bin` re-packed without the battery bit in `_health` (#41) | health 1433508911, the rest as the real one. LEN 31 | Mapper: an unhealthy monitor or a battery failsafe, voltage kept (#41) |

Raw bytes:

```
telemetry/sys-status.bin                fd 1f 00 00 22 01 01 01 00 00 2f fc 71 53 2f 9c 61 52 2f 9c 71 57
                                        00 00 38 31 00 00 00 00 00 00 00 00 00 00 00 00 00 00 64 80 91
telemetry/gps-raw-int.bin               fd 28 00 00 27 01 01 18 00 00 78 1f a6 17 00 00 00 00 15 fb eb ea
                                        3c cf e8 58 8e f5 08 00 79 00 c8 00 00 00 00 00 06 0a 8e f5 08 00
                                        2c 01 00 00 2c 01 f6 c2
derived/sys-status-edges.bin            fd 2b 00 00 15 01 01 01 00 00 0f fc 31 53 0f 9c 01 52 0b 9c 11 45
                                        f4 01 5c 2b 0e 06 fa 00 01 00 02 00 03 00 04 00 05 00 2a 04 03 02
                                        01 08 07 06 05 ef cd ab 89 14 ee
derived/sys-status-unknown-battery.bin  fd 1f 00 00 15 01 01 01 00 00 0f fc 31 53 0f 9c 01 52 0b 9c 11 45
                                        00 00 ff ff ff ff 00 00 00 00 00 00 00 00 00 00 00 00 ff 5d d8
derived/gps-raw-int-edges.bin           fd 34 00 00 27 01 01 18 00 00 ef cd ab 89 67 45 23 01 15 fb eb ea
                                        3c cf e8 58 c7 cf ff ff 96 00 fa 00 d2 04 9f 8c 03 0c 60 a4 ff ff
                                        ef cd ab 89 90 01 00 00 32 00 00 00 39 30 00 00 a0 8c c9 22
derived/gps-raw-int-unknown.bin         fd 1e 00 00 27 01 01 18 00 00 78 1f a6 17 00 00 00 00 15 fb eb ea
                                        3c cf e8 58 8e f5 08 00 ff ff ff ff ff ff ff ff 00 ff a8 97
derived/sys-status-battery-remaining-min.bin
                                        fd 1f 00 00 22 01 01 01 00 00 2f fc 71 53 2f 9c 61 52 2f 9c 71 57
                                        00 00 38 31 00 00 00 00 00 00 00 00 00 00 00 00 00 00 80 79 1f
derived/gps-raw-int-time-2-63.bin       fd 28 00 00 27 01 01 18 00 00 00 00 00 00 00 00 00 80 15 fb eb ea
                                        3c cf e8 58 8e f5 08 00 79 00 c8 00 00 00 00 00 06 0a 8e f5 08 00
                                        2c 01 00 00 2c 01 60 2f
derived/sys-status-battery-zero-volts.bin
                                        fd 1f 00 00 22 01 01 01 00 00 2f fc 71 53 2f 9c 61 52 2f 9c 71 57
                                        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 64 63 74
derived/sys-status-no-battery-monitor.bin
                                        fd 1f 00 00 22 01 01 01 00 00 2f fc 71 53 2f 9c 61 50 2f 9c 71 57
                                        00 00 00 00 ff ff 00 00 00 00 00 00 00 00 00 00 00 00 ff 36 d1
derived/sys-status-battery-unhealthy.bin
                                        fd 1f 00 00 22 01 01 01 00 00 2f fc 71 53 2f 9c 61 52 2f 9c 71 55
                                        00 00 38 31 00 00 00 00 00 00 00 00 00 00 00 00 00 00 64 58 ff
```

## Out-of-range telemetry (#49)

Derived by `tools/fixtures/derive_fixtures.py` from the real telemetry frames above, every field but the
replaced ones checked unchanged with pymavlink 2.4.50. Ranges from the field descriptions: GLOBAL_POSITION_INT
`hdg` "0.0..359.99 degrees" (`standard.xml`), GPS_RAW_INT `cog` "0.0..359.99 degrees" and `yaw` "Use 36000 for
north" (`common.xml`), VFR_HUD `throttle` "(0 to 100)", ATTITUDE angles "(-pi..+pi)"; latitude and longitude have
no range in the XML, so their WGS84 definition (±90°, ±180°) applies. Used by the mapper tests in `:data:vehicle`;
the codec decodes them like any other frame.

| File | Derived from | Replaced (pymavlink) | Used for |
|---|---|---|---|
| `derived/global-position-int-hdg-36000.bin` | `telemetry/global-position-int.bin` | hdg 36000. LEN 28 | heading past 359.99° |
| `derived/global-position-int-lat-past-90.bin` | same | lat 900000001. LEN 28 | latitude past 90° |
| `derived/global-position-int-lon-past-180.bin` | same | lon -1800000001. LEN 28 | longitude past -180° |
| `derived/global-position-int-lon-past-180-east.bin` | same | lon 1800000001. LEN 28 | longitude past 180° |
| `derived/global-position-int-lat-int32-min.bin` | same | lat -2147483648 (INT32_MIN). LEN 28 | a latitude whose absolute value overflows an int32 |
| `derived/global-position-int-ends.bin` | same | lat 900000000, lon -1800000000, hdg 35999. LEN 28 | the ends of every range, valid |
| `derived/global-position-int-ends-south-east.bin` | same | lat -900000000, lon 1800000000. LEN 28 | the other ends, valid |
| `derived/gps-raw-int-out-of-range.bin` | `telemetry/gps-raw-int.bin` | cog 40000, yaw 36001; the extensions are no longer truncated, LEN 52 | course and GPS yaw past their ranges |
| `derived/vfr-hud-out-of-range.bin` | `telemetry/vfr-hud.bin` | airspeed NaN (`00 00 c0 7f`), climb +infinity (`00 00 80 7f`), throttle 101. LEN 19 | non-finite floats, throttle past 100 |
| `derived/vfr-hud-throttle-100.bin` | same | throttle 100. LEN 19 | the end of the throttle range, valid |
| `derived/attitude-out-of-range.bin` | `telemetry/attitude.bin` | roll NaN, pitch -infinity (`00 00 80 ff`), yaw 0.5. LEN 28 | non-finite angles, one valid angle kept |
| `derived/attitude-past-pi.bin` | same | roll 3.1415927 (`db 0f 49 40`, the float32 closest to pi), pitch -0.25, yaw 3.1416 (`f9 0f 49 40`). LEN 28 | the end of the angle range, valid, and past it |

Raw bytes:

```
derived/global-position-int-hdg-36000.bin
                                        fd 1c 00 00 35 01 01 21 00 00 e9 0f 06 00 16 fb eb ea 3b cf e8 58
                                        7a f5 08 00 f3 ff ff ff 01 00 ff ff 00 00 a0 8c 0b 01
derived/global-position-int-lat-past-90.bin
                                        fd 1c 00 00 35 01 01 21 00 00 e9 0f 06 00 01 e9 a4 35 3b cf e8 58
                                        7a f5 08 00 f3 ff ff ff 01 00 ff ff 00 00 5f 8c ad e3
derived/global-position-int-lon-past-180.bin
                                        fd 1c 00 00 35 01 01 21 00 00 e9 0f 06 00 16 fb eb ea ff 2d b6 94
                                        7a f5 08 00 f3 ff ff ff 01 00 ff ff 00 00 5f 8c c7 ef
derived/global-position-int-lon-past-180-east.bin
                                        fd 1c 00 00 35 01 01 21 00 00 e9 0f 06 00 16 fb eb ea 01 d2 49 6b
                                        7a f5 08 00 f3 ff ff ff 01 00 ff ff 00 00 5f 8c 8a c5
derived/global-position-int-lat-int32-min.bin
                                        fd 1c 00 00 35 01 01 21 00 00 e9 0f 06 00 00 00 00 80 3b cf e8 58
                                        7a f5 08 00 f3 ff ff ff 01 00 ff ff 00 00 5f 8c 92 f1
derived/global-position-int-ends.bin    fd 1c 00 00 35 01 01 21 00 00 e9 0f 06 00 00 e9 a4 35 00 2e b6 94
                                        7a f5 08 00 f3 ff ff ff 01 00 ff ff 00 00 9f 8c 8e f4
derived/global-position-int-ends-south-east.bin
                                        fd 1c 00 00 35 01 01 21 00 00 e9 0f 06 00 00 17 5b ca 00 d2 49 6b
                                        7a f5 08 00 f3 ff ff ff 01 00 ff ff 00 00 5f 8c 94 ad
derived/gps-raw-int-out-of-range.bin    fd 34 00 00 27 01 01 18 00 00 78 1f a6 17 00 00 00 00 15 fb eb ea
                                        3c cf e8 58 8e f5 08 00 79 00 c8 00 00 00 40 9c 06 0a 8e f5 08 00
                                        2c 01 00 00 2c 01 00 00 00 00 00 00 00 00 00 00 a1 8c 82 0b
derived/vfr-hud-out-of-range.bin        fd 13 00 00 2b 01 01 4a 00 00 00 00 c0 7f 00 8f c3 3c 52 c8 12 44
                                        00 00 80 7f 67 01 65 d7 71
derived/vfr-hud-throttle-100.bin        fd 13 00 00 2b 01 01 4a 00 00 01 90 c3 3c 00 8f c3 3c 52 c8 12 44
                                        73 50 1e b9 67 01 64 bf 5f
derived/attitude-out-of-range.bin       fd 1c 00 00 2a 01 01 1e 00 00 3f 0f 06 00 00 00 c0 7f 00 00 80 ff
                                        00 00 00 3f 50 f7 69 b9 00 fd 67 b9 c0 a8 4a ba e8 12
derived/attitude-past-pi.bin            fd 1c 00 00 2a 01 01 1e 00 00 3f 0f 06 00 db 0f 49 40 00 00 80 be
                                        f9 0f 49 40 50 f7 69 b9 00 fd 67 b9 c0 a8 4a ba ee 4f
```

For `derived/session-stream.bin` the script also prints, from pymavlink, how many of the vehicle autopilot's
MAVLink 2 telemetry frames carry each value the app maps (a documented "unknown" not counted, our ranges not
applied): 130 GLOBAL_POSITION_INT, all with a heading; 260 ATTITUDE with roll, pitch and yaw; 260 VFR_HUD with
airspeed, groundspeed, climb and throttle; 325 GPS_RAW_INT, all with a course and none with a yaw (no
dual-antenna GPS in SITL).

## HOME_POSITION (#66, #69)

Extracted from `heartbeat/session.tlog` and re-packed by `py tools/fixtures/derive_fixtures.py` (pymavlink 2.4.50);
`mavparse` (`common.xml` master `2e2b6af`): CRC_EXTRA 104, LEN 52, 60 with `time_usec` at 52.

| File | Origin | What it proves |
|---|---|---|
| `telemetry/home-position.bin` | the real frame (seq 114, ArduCopter V4.7.2-beta1), LEN 56, NaN `q` | a real frame, extension cut inside (REQ-013, REQ-032) |
| `derived/home-position-full.bin` | distinct value in every field, `time_usec` `0x0102030405060708`, LEN 60 | every offset and the uint64 byte order (REQ-032) |
| `derived/home-position-no-extension.bin` | `time_usec` 0, `approach_z` -1.5, LEN 52 | a missing extension reads 0 (REQ-013, REQ-032) |
| `derived/home-position-cut-after-q.bin` | `time_usec` 0, LEN 40 | zero-fill after the NaN `q` (REQ-013, REQ-032) |
| `derived/home-position-moved.bin` | latitude -353624514, longitude 1491663411, altitude 590130, LEN 56 | home set again elsewhere (REQ-033) |
| `derived/home-position-lat-past-90.bin` | latitude 900000001, LEN 56 | a home past 90°: dropped (REQ-031) |
| `derived/home-position-lat-int32-min.bin` | latitude -2147483648 (INT32_MIN), LEN 56 | a latitude whose absolute value overflows an int32 (REQ-031) |
| `derived/home-position-null-island.bin` | latitude and longitude 0, LEN 56 | 0°, 0° is never a home (REQ-033) |

Raw bytes:

```
telemetry/home-position.bin             fd 38 00 00 72 01 01 f2 00 00 16 fb eb ea 3b cf e8 58 7a f5 08 00
                                        82 62 36 3c 06 bc 14 bc 0a d7 a3 3c 00 00 c0 7f 00 00 c0 7f 00 00
                                        c0 7f 00 00 c0 7f 00 00 00 00 00 00 00 00 00 00 00 00 3d 98 76 1a
                                        6c fe
derived/home-position-full.bin          fd 3c 00 00 72 01 01 f2 00 00 16 fb eb ea c5 30 17 a7 c7 cf ff ff
                                        00 00 c0 3f 00 00 10 c0 00 00 40 3f 00 00 00 3f 00 00 80 be 00 00
                                        00 3e 00 00 80 bd 00 00 60 40 00 00 90 c0 00 00 a8 40 08 07 06 05
                                        04 03 02 01 6a 14
derived/home-position-no-extension.bin  fd 34 00 00 72 01 01 f2 00 00 16 fb eb ea 3b cf e8 58 7a f5 08 00
                                        82 62 36 3c 06 bc 14 bc 0a d7 a3 3c 00 00 c0 7f 00 00 c0 7f 00 00
                                        c0 7f 00 00 c0 7f 00 00 00 00 00 00 00 00 00 00 c0 bf 89 9b
derived/home-position-cut-after-q.bin   fd 28 00 00 72 01 01 f2 00 00 16 fb eb ea 3b cf e8 58 7a f5 08 00
                                        82 62 36 3c 06 bc 14 bc 0a d7 a3 3c 00 00 c0 7f 00 00 c0 7f 00 00
                                        c0 7f 00 00 c0 7f 44 cc
derived/home-position-moved.bin         fd 38 00 00 72 01 01 f2 00 00 3e 1e ec ea 33 fa e8 58 32 01 09 00
                                        82 62 36 3c 06 bc 14 bc 0a d7 a3 3c 00 00 c0 7f 00 00 c0 7f 00 00
                                        c0 7f 00 00 c0 7f 00 00 00 00 00 00 00 00 00 00 00 00 3d 98 76 1a
                                        2e f9
derived/home-position-lat-past-90.bin   fd 38 00 00 72 01 01 f2 00 00 01 e9 a4 35 3b cf e8 58 7a f5 08 00
                                        82 62 36 3c 06 bc 14 bc 0a d7 a3 3c 00 00 c0 7f 00 00 c0 7f 00 00
                                        c0 7f 00 00 c0 7f 00 00 00 00 00 00 00 00 00 00 00 00 3d 98 76 1a
                                        76 48
derived/home-position-lat-int32-min.bin fd 38 00 00 72 01 01 f2 00 00 00 00 00 80 3b cf e8 58 7a f5 08 00
                                        82 62 36 3c 06 bc 14 bc 0a d7 a3 3c 00 00 c0 7f 00 00 c0 7f 00 00
                                        c0 7f 00 00 c0 7f 00 00 00 00 00 00 00 00 00 00 00 00 3d 98 76 1a
                                        66 c4
derived/home-position-null-island.bin   fd 38 00 00 72 01 01 f2 00 00 00 00 00 00 00 00 00 00 7a f5 08 00
                                        82 62 36 3c 06 bc 14 bc 0a d7 a3 3c 00 00 c0 7f 00 00 c0 7f 00 00
                                        c0 7f 00 00 c0 7f 00 00 00 00 00 00 00 00 00 00 00 00 3d 98 76 1a
                                        ee 06
```

## STATUSTEXT (#96)

Extracted from `heartbeat/session.tlog` and re-packed by `py tools/fixtures/derive_fixtures.py` (pymavlink 2.4.50);
`mavparse` (`common.xml` master `eec8824`): CRC_EXTRA 83, LEN 51, 54 with `id` at 51 and `chunk_seq` at 53. Every real
STATUSTEXT in `heartbeat/session.tlog` (2) and `tlog/mission-planner.tlog` (23) is truncated with no extension, and
none is a `PreArm:` or `Arm:`, so those two are derived with ArduCopter 4.7.2's texts (tag `Copter-4.7.2`:
`AP_Arming::check_failed` in `AP_Arming.cpp`, `AP_Arming_Copter.cpp`, `ModeRTL::name()` in `mode.h`).

| File | Origin | What it proves |
|---|---|---|
| `statustext/arming-motors.bin` | the real frame (seq 115, ArduCopter V4.7.2-beta1), INFO, LEN 14 | a real frame, text and extensions truncated (REQ-013, REQ-040) |
| `derived/statustext-prearm.bin` | `PreArm: RC not found`, CRITICAL, LEN 21 | a failed pre-arm check as ArduCopter sends it (REQ-040) |
| `derived/statustext-arm.bin` | `Arm: RTL mode not armable`, CRITICAL, LEN 26 | a refused arm as ArduCopter sends it (REQ-040) |
| `derived/statustext-full.bin` | 50 distinct text bytes, no NUL, WARNING, `id` 0x1234, `chunk_seq` 7, LEN 54 | every offset, the uint16 byte order, a text without NUL (REQ-040) |
| `derived/statustext-chunk-1.bin` | the first 50 bytes of a longer text, `id` 1, `chunk_seq` 0, LEN 52 | a first chunk as ArduPilot splits it (`GCS_Common.cpp`) (REQ-040) |
| `derived/statustext-chunk-2.bin` | the rest, NUL-padded, `id` 1, `chunk_seq` 1, LEN 54 | the text ends at the NUL before the extensions (REQ-040) |
| `derived/statustext-nul-inside.bin` | `ab`, NUL, `cd`, LEN 6 | the bytes after a NUL are not text (REQ-040) |
| `derived/statustext-utf8.bin` | `caf`, U+00E9 as `c3 a9`, ` `, `ff`, ` ok`, LEN 11 | UTF-8 decoding, an invalid byte as U+FFFD (REQ-012, REQ-040) |

pymavlink reads the text byte by byte, not as UTF-8 (`statustext-utf8.bin` comes out as `caf�� � ok`), so for
that fixture the expected text is `café � ok`, from `common.xml` ("UTF-8 encoded"), and the script checks its bytes.

Raw bytes:

```
statustext/arming-motors.bin      fd 0e 00 00 73 01 01 fd 00 00 06 41 72 6d 69 6e 67 20 6d 6f 74 6f
                                  72 73 83 50
derived/statustext-prearm.bin     fd 15 00 00 73 01 01 fd 00 00 02 50 72 65 41 72 6d 3a 20 52 43 20
                                  6e 6f 74 20 66 6f 75 6e 64 af d7
derived/statustext-arm.bin        fd 1a 00 00 73 01 01 fd 00 00 02 41 72 6d 3a 20 52 54 4c 20 6d 6f
                                  64 65 20 6e 6f 74 20 61 72 6d 61 62 6c 65 08 fa
derived/statustext-full.bin       fd 36 00 00 73 01 01 fd 00 00 04 41 42 43 44 45 46 47 48 49 4a 4b
                                  4c 4d 4e 4f 50 51 52 53 54 55 56 57 58 59 5a 61 62 63 64 65 66 67
                                  68 69 6a 6b 6c 6d 6e 6f 70 71 72 73 74 75 76 77 78 34 12 07 b8 26
derived/statustext-chunk-1.bin    fd 34 00 00 73 01 01 fd 00 00 06 41 20 53 54 41 54 55 53 54 45 58
                                  54 20 6c 6f 6e 67 65 72 20 74 68 61 6e 20 66 69 66 74 79 20 62 79
                                  74 65 73 20 67 6f 65 73 20 6f 75 74 20 69 6e 20 63 01 e6 7f
derived/statustext-chunk-2.bin    fd 36 00 00 73 01 01 fd 00 00 06 68 75 6e 6b 73 20 6f 66 20 66 69
                                  66 74 79 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
                                  00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 00 01 27 a9
derived/statustext-nul-inside.bin fd 06 00 00 73 01 01 fd 00 00 06 61 62 00 63 64 81 a2
derived/statustext-utf8.bin       fd 0b 00 00 73 01 01 fd 00 00 06 63 61 66 c3 a9 20 ff 20 6f 6b a3
                                  26
```

## Raw frames for the flight log (#67)

Made by `py tools/fixtures/derive_fixtures.py` (pymavlink 2.4.50).

| File | Origin | What it proves |
|---|---|---|
| `derived/session-mavlink2-stream.bin` | `heartbeat/session.tlog`'s 5,522 MAVLink 2 frames as pymavlink delimits them | `parseRaw()` hands out the session byte for byte (REQ-010, REQ-036) |
| `derived/heartbeat-corrupted-len-raised.bin` | `heartbeat/disarmed-stabilize.bin` with LEN 255, checksum kept | the real frames after a corrupted LEN still come out (REQ-010) |
| `derived/heartbeat-corrupted-len-lowered.bin` | the same with LEN 1 | the real frames after a LEN cut short still come out (REQ-010) |
| `unknown/*`, `derived/attitude-signed.bin`, `truncated/power_status.bin` | reused | unknown to a HEARTBEAT-only lookup, handed out byte for byte (REQ-010, REQ-036) |

Raw bytes:

```
derived/heartbeat-corrupted-len-raised.bin   fd ff 00 00 3b 01 01 00 00 00 00 00 00 00 02 03 51 03 03 8e b2
derived/heartbeat-corrupted-len-lowered.bin  fd 01 00 00 3b 01 01 00 00 00 00 00 00 00 02 03 51 03 03 8e b2
```

## Telemetry logs (#68)

Made by `py tools/fixtures/tlog_fixtures.py <qgroundcontrol.tlog> <mission-planner.tlog>` (pymavlink 2.4.50, same
bytes each run). Records are numbered from 0.

| File | Origin | What it proves |
|---|---|---|
| `tlog/qgroundcontrol.tlog` | QGroundControl, SITL ArduCopter 4.7.2 `fbd0a316`, 25 s to 35 s | 1,079 records (1 MAVLink 1); whole-ms timestamps; no SETUP_SIGNING or signature (ADR 0006) |
| `tlog/mission-planner.tlog` | Mission Planner, same SITL, its one signed record left out (the key may not be a throwaway) | 2,417 records (1,375 MAVLink 1); low bits set; no SETUP_SIGNING or signature (ADR 0006) |
| `heartbeat/session.tlog` | see "How they were captured" | 5,524 records (2 MAVLink 1); round trip |
| `tlog/<name>.records.csv` | pymavlink: timestamp as stored, frame length, one line per record | expected records |
| `tlog/<name>.frames.bin` | pymavlink: the frames concatenated (the session's are `derived/session-stream.bin`) | expected frames |
| `derived/tlog-garbage-between-records.bin` | 7 × `0x55` after the first of the session's first 10 records | garbage is skipped; no start marker 8 bytes past it |
| `derived/tlog-garbage-before-first-record.bin` | the same 7 bytes before the first record | garbage before any anchor is skipped; no start marker 8 bytes past it |
| `derived/tlog-false-start-before-first-record.bin` | 8 × `0x55` and `0xFE` before the first record | one false record never anchors |
| `derived/tlog-timestamp-without-frame.bin` | a lone timestamp after the fifth record | a lone timestamp is skipped; no start marker 8 bytes past it |
| `derived/tlog-false-start-after-lone-timestamp.bin` | records 418 to 440, a lone timestamp before 421, whose timestamp holds a start marker | the 3-day window rejects a false start |
| `derived/tlog-truncated-last-record.bin` | the last record cut to 23 of 38 bytes | a truncated record is counted |
| `derived/tlog-timestamp-at-end.bin` | a lone timestamp after the last record | a lone tail timestamp is counted |
| `derived/tlog-false-start-at-end.bin` | the 10 records, the last timestamp again with `fd ff 00 00 00 01 01 00 00 00` (LEN 255), then records 10 to 12 | `finish()` finds hidden records |

## Frames for the flight log (#70)

Made by `py tools/fixtures/derive_fixtures.py` (pymavlink 2.4.50, same bytes each run), signed with the test key,
link ID and timestamp of `derived/heartbeat-signed.bin`. pymavlink verifies each signature, and re-packs each signed
message unsigned with the same header to the bytes of its original before the files are written.

| File | Origin | What it proves |
|---|---|---|
| `derived/heartbeat-signed.bin`, `derived/attitude-signed.bin` | reused; unsigned they are `heartbeat/disarmed-stabilize.bin` and `unknown/attitude-fd-in-payload.bin` | a signed known message is logged unsigned, checksum recomputed (ADR 0006) |
| `derived/vfr-hud-signed.bin` | `telemetry/vfr-hud.bin` (LEN 18) signed | a truncated payload keeps its LEN when the signature goes |
| `derived/scaled-pressure-signed.bin` | `unknown/scaled-pressure.bin` signed | a signed unknown message ID is left out |
| `derived/setup-signing.bin` | SETUP_SIGNING (256) from 255/190 to 1/1 carrying the test key | SETUP_SIGNING is left out |
| `derived/setup-signing-signed.bin` | the same, signed | left out signed too |

Raw bytes:

```
derived/vfr-hud-signed.bin          fd 12 01 00 2b 01 01 4a 00 00 01 90 c3 3c 00 8f c3 3c 52 c8 12 44 73 50 1e b9
                                    67 01 48 bd 00 78 56 34 12 00 00 16 ab 8d 26 48 e4
derived/scaled-pressure-signed.bin  fd 0e 01 00 3b 01 01 1d 00 00 e9 0f 06 00 8c 2b 6c 44 00 00 00 00 2e 0c 69 b4
                                    00 78 56 34 12 00 00 6c c6 9b 13 4a 26
derived/setup-signing.bin           fd 2a 00 00 00 ff be 00 01 00 78 56 34 12 00 00 00 00 01 01 00 01 02 03 04 05
                                    06 07 08 09 0a 0b 0c 0d 0e 0f 10 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f
                                    b3 87
derived/setup-signing-signed.bin    fd 2a 01 00 00 ff be 00 01 00 78 56 34 12 00 00 00 00 01 01 00 01 02 03 04 05
                                    06 07 08 09 0a 0b 0c 0d 0e 0f 10 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f
                                    0f 82 00 78 56 34 12 00 00 8b 0c cc 4a 63 b1
```
