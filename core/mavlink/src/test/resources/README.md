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

The last three were also computed with `mavparse` from `common.xml` and `standard.xml` on `mavlink/mavlink`
master (#37): same CRC_EXTRA, full length and wire order.

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
With the full message table (#10, #35, #37): 133 HEARTBEAT, 325 SYS_STATUS, 260 ATTITUDE, 130
GLOBAL_POSITION_INT, 130 SERVO_OUTPUT_RAW, 260 VFR_HUD, 325 POWER_STATUS, 11 COMMAND_LONG and 13
COMMAND_ACK frames are known, and the other 3,935 MAVLink 2 frames are unknown message IDs.

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
