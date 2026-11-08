# 0009. Keep the live link in a `connectedDevice` foreground service holding a wake lock

- Status: Proposed
- Date: 2026-11-08
- Issue: #28

## Context

The link is held open only while something observes it, and it closes about 10 s after the HUD is hidden; the API 36
emulator also blocks a hidden app's network after about 5 s (#14). Once the GCS HEARTBEAT stops, an armed vehicle
with `FS_GCS_ENABLE` set acts after `FS_GCS_TIMEOUT`. REQ-046 requires the link, the HEARTBEAT, the watchdog and the
recording to keep running in the background while a live vehicle is connected, and nothing in the app to stop them
while it is armed.

- A foreground service of type `connectedDevice` is for "interactions with external devices that require a
  Bluetooth, NFC, IR, USB, or network connection". From Android 14 it needs `FOREGROUND_SERVICE_CONNECTED_DEVICE` and
  "at least one of" `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`, `TRANSMIT_IR`,
  or a Bluetooth, UWB or USB grant
  ([service types](https://developer.android.com/develop/background-work/services/fgs/service-types)). An app on
  Google Play targeting Android 14+ declares each type in Play Console (*Policy > App content*).
- A foreground service does not keep the CPU awake: with the screen off the 1 Hz HEARTBEAT loop and the watchdog's
  timers (`delay`) pause with it. A `PARTIAL_WAKE_LOCK` keeps it running
  ([PowerManager](https://developer.android.com/reference/android/os/PowerManager#PARTIAL_WAKE_LOCK)).
- Doze "suspends network access" and "ignores wake locks"
  ([Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)), while an app "running a
  foreground service" has network "No restrictions"
  ([power details](https://developer.android.com/topic/performance/power/power-details)). The two pages do not say how
  they combine, so it is measured on a real device (REQ-046 matrix).
- Wi-Fi power save can delay incoming datagrams. `WIFI_MODE_FULL_LOW_LATENCY` "is only active when the screen is on"
  and "when the acquiring app is running in the foreground"; `WIFI_MODE_FULL_HIGH_PERF` is deprecated in API 34 and
  "automatically replaced with `WIFI_MODE_FULL_LOW_LATENCY` with all the restrictions"
  ([WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager)).

## Decision

While the active live vehicle is connected, `VehicleLinkService` runs as a `connectedDevice` foreground service and
observes the vehicles, which keeps the link session, the watchdog, the GCS HEARTBEAT and the recording running; the
REQ-021 rules for the HEARTBEAT do not change. It declares `FOREGROUND_SERVICE_CONNECTED_DEVICE` and, as its
prerequisite, `CHANGE_NETWORK_STATE`: the link is UDP over whatever network carries it (Wi-Fi, Ethernet, USB
tethering), not Wi-Fi only.

- **Start:** only from the foreground, when the active live vehicle becomes connected; never in replay or with no
  vehicle. The activity only starts it; the service, not the activity, observes in the background (#74).
- **Stop:** a pure rule (`BackgroundLinkRule`) decides. While the vehicle's last known state is armed nothing stops it:
  the notification's Disconnect is hidden and refused, removing the app from recents is ignored, and dismissing the
  notification posts it again. Disarmed, Disconnect and recents stop it, and so does 10 min of link loss (the
  recording's REQ-036 limit) or the end of the live session.
- **Wake lock:** one `PARTIAL_WAKE_LOCK`, not reference counted, acquired when the service starts and released when
  it stops, with no timeout: its lifetime is the service's, which the rule bounds.
- **No Wi-Fi lock:** with the screen off in the background no mode is active on API 34+, and the only one that was
  is deprecated.
- `START_NOT_STICKY`: a service restarted after a process kill would start with no vehicle.

## Alternatives considered

### Keep the link in the activity
- Pros: no service, no permissions.
- Cons: Android blocks a hidden app's network within seconds; the link stops with the screen.
- Why not: REQ-046 cannot be met.

### Battery optimisation exemption instead of, or with, the service
- Pros: survives deep Doze for certain.
- Cons: Play allows `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only when "the core function of the app is adversely
  affected"; the operator must act in system settings.
- Why not now: decided only if the device check shows the service is not enough.

## Consequences

- Positive: the vehicle keeps hearing this GCS with the app hidden or the screen off; an armed vehicle is never let go
  by the app.
- Negative / costs we accept: battery drain while a vehicle is connected; four permissions (P-2); a Play Console
  declaration with a video before any store release. Since Android 13 the user can stop the whole app from the Task
  Manager, and force stop does the same, with no callback
  ([user stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)); a process
  kill ends the service too. In each case the vehicle's own `FS_GCS` is the fallback.
- Follow-ups: PR 2 of #28 asks `POST_NOTIFICATIONS`, warns in the HUD when the notification is hidden and logs
  `ApplicationExitInfo` `REASON_USER_REQUESTED`.
