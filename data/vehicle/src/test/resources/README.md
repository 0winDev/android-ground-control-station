# Vehicle-state test fixtures

Byte-identical copies of MAVLink 2 HEARTBEAT frames from `core/mavlink/src/test/resources/`, where
their provenance (ArduCopter V4.7.2-beta1 SITL, 2026-10-08), raw bytes and reference decoding
(pymavlink 2.4.50) are documented. Never edit them here: regenerate them there
(`py tools/fixtures/derive_fixtures.py`) and copy them again.

| File | Origin | Sender | Expected (pymavlink + ArduCopter `FLTMODE1` docs) |
|---|---|---|---|
| `heartbeat/disarmed-stabilize.bin` | real | system 1, component 1, quadrotor, ArduPilot | Stabilize, disarmed |
| `heartbeat/disarmed-guided.bin` | real | same | Guided, disarmed |
| `heartbeat/disarmed-loiter.bin` | real | same | Loiter, disarmed |
| `heartbeat/armed-guided.bin` | real | same | Guided, armed |
| `derived/heartbeat-sysid-2.bin` | `disarmed-loiter.bin` re-packed by pymavlink with system ID 2 | system 2, component 1 | Loiter, disarmed |
| `derived/heartbeat-gimbal-component.bin` | `disarmed-stabilize.bin` re-packed by pymavlink from component 154 (`MAV_COMP_ID_GIMBAL`) | system 1, component 154 | not the vehicle |
| `gcs/heartbeat-mission-planner.bin` | real (Mission Planner) | system 255, component 190, `MAV_TYPE_GCS` | not a vehicle |

## Telemetry (#39)

Byte-identical copies of the telemetry fixtures in `core/mavlink/src/test/resources/` (#37, #38), where the
provenance of each one is documented. Decoded again with pymavlink 2.4.50 before copying them; the raw values
below are what it gives, and the mapper tests convert them by hand. The battery bit is
`MAV_SYS_STATUS_SENSOR_BATTERY` (`0x2000000`) in `onboard_control_sensors_enabled` and `_health`.

| File | Origin | Raw values (pymavlink) | Used for |
|---|---|---|---|
| `telemetry/global-position-int.bin` | real | lat -353633514, lon 1491652411, alt 587130, relative_alt -13, vx 1, vy -1, vz 0, hdg 35935 | degE7, mm, cm/s and cdeg converted |
| `derived/global-position-int-edges.bin` | derived | alt -12345, relative_alt -500, vx -300, vy 1234, vz -150, hdg 65535 | negative values; heading unknown |
| `telemetry/attitude.bin` | real | roll -0.0010504957, pitch -0.0012832552, yaw -0.011366294 (rad) | radians to degrees |
| `derived/attitude-edges.bin` | derived | roll 0.5, pitch -0.25, yaw -3.1415927 | yaw near -180° |
| `telemetry/vfr-hud.bin` | real | airspeed 0.023872377, groundspeed 0.023871899, throttle 0 (truncated), climb -0.00015098024 | air data |
| `derived/vfr-hud-edges.bin` | derived | throttle 65, climb -2.5 | throttle and a descent |
| `telemetry/sys-status.bin` | real | voltage_battery 12600, current_battery 0, battery_remaining 100; battery bit enabled and healthy | battery converted |
| `derived/sys-status-edges.bin` | derived | voltage_battery 11100, current_battery 1550, battery_remaining 42; battery bit enabled, not healthy | current and remaining converted, voltage not trusted |
| `derived/sys-status-unknown-battery.bin` | derived | voltage_battery 65535, current_battery -1, battery_remaining -1; battery bit enabled, not healthy | every battery reading unknown |
| `derived/sys-status-battery-remaining-min.bin` | derived | battery_remaining -128 | a remaining energy outside 0–100 |
| `truncated/sys_status.bin` | real (boot) | voltage_battery 0, current_battery -1, battery_remaining -1; battery bit enabled, not healthy | a monitor with no reading yet: no voltage, not 0 V |
| `derived/sys-status-battery-zero-volts.bin` | derived (#41) | voltage_battery 0; battery bit enabled and healthy | a healthy monitor reading 0 V |
| `derived/sys-status-no-battery-monitor.bin` | derived (#41) | battery bit not enabled, voltage_battery 0, current_battery -1, battery_remaining -1 | no battery monitor |
| `derived/sys-status-battery-unhealthy.bin` | derived (#41) | battery bit enabled, not healthy; the rest as `telemetry/sys-status.bin` | unhealthy monitor or battery failsafe |
| `telemetry/gps-raw-int.bin` | real | fix_type 6, eph 121, epv 200, vel 0, cog 0, satellites_visible 10, h_acc 300, v_acc 300, yaw 0 | GPS converted; yaw not provided |
| `derived/gps-raw-int-edges.bin` | derived | fix_type 3, eph 150, epv 250, vel 1234, cog 35999, satellites_visible 12, h_acc 2309737967, v_acc 400, yaw 36000 | uint32 accuracy; yaw north |
| `derived/gps-raw-int-unknown.bin` | derived | fix_type 0, eph, epv, vel, cog 65535, satellites_visible 255, extensions 0 | every GPS reading unknown |
