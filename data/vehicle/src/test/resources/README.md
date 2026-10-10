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
| `gcs/heartbeat-mission-planner.bin` | real (Mission Planner) | system 255, component 190, `MAV_TYPE_GCS` | not a vehicle |
