# 0008. Retry only RTL and land; send arm once

- Status: Accepted
- Date: 2026-10-28
- Issue: #98, #99 (take off)

## Context

v0.4 sends arm, RTL and land (take off in #99) as COMMAND_LONG to the active vehicle and must end each one in a
result the operator can read (REQ-042). The command protocol says a command without acknowledgement "must be
automatically re-sent", that "the sender should increase the confirmation field", and that after
`MAV_RESULT_IN_PROGRESS` "the GCS should have a much increased timeout"; it gives no timeout and no number of attempts
([mavlink.io `services/command.html`](https://mavlink.io/en/services/command.html)).

QGroundControl waits 1200 ms (`_ackTimeoutMSecsDefault`), tries 3 times (`kMaxRetryCount`) with `confirmation` =
attempt − 1, but retries only status requests, never arm, take off, RTL or land: "you don't want an ARM command to
timeout with no response over a noisy link twice and then suddenly have the third try work 6 seconds later. At that
point the user could have walked up to the vehicle" (`src/Vehicle/MavCommandQueue.cc`, `_shouldRetry`).

ArduCopter answers RTL and land at once: each is a mode change, ACCEPTED or FAILED
(`ArduCopter/GCS_MAVLink_Copter.cpp`). The COMMAND_ACK names the command but carries no sequence number, so a late ACK
cannot be told from the answer to a newer command with the same ID. Until signing (v0.6) the ACK is not authenticated
(`docs/security.md`, N-2).

## Decision

- **Timeout 1.5 s** per attempt: ArduCopter answered in 6–27 ms over loopback (#36); 1.5 s leaves room for a radio
  link's round trip and keeps three attempts to 4.5 s.
- **RTL and land: up to 3 attempts**, `confirmation` 0, 1, 2. A late RTL or land is still what the operator wants:
  it brings the vehicle back or down.
- **Arm (and take off, #99): one attempt, never re-sent.** QGroundControl's reason: an arm that works seconds later
  surprises whoever walked up to the vehicle.
- **After `MAV_RESULT_IN_PROGRESS`: 10 s more**, from that ACK, never extended and never re-sent; silence then ends
  the command as no answer, marked as started by the vehicle, so the operator checks its state.
- **One command at a time.** A new RTL or land supersedes a pending RTL or land: its retries stop, it ends as
  superseded and the new one is sent at once. Arm never supersedes nor is superseded: the newcomer is not sent. RTL
  and land also supersede a pending take off (#99), even while IN_PROGRESS, so a long take off never holds back a
  return or a landing; only arm is never superseded.
- **A late window of 1.5 s** after a command ends without answer: its ACK is dropped and logged, and only the same
  command is refused meanwhile, as busy; any other is sent at once.
- **Arm's and take off's late window is 10 s** (`DANGEROUS_LATE_WINDOW`), 11.5 s from the send. An earlier arm's
  late FAILED taken as a new arm's answer would read "rejected" while the new arm arms the vehicle; refusing a new arm
  meanwhile covers round trips up to 11.5 s. The same holds for a take off, which keeps its window even when RTL or
  land supersedes it once an attempt was about to leave. The ACK has no sequence number, so no rule closes it for any
  latency: past that, the HEARTBEAT's armed state is the truth, and the screen shows it over any ACK (#104).
- **Take off is a sequence (#99).** ArduCopter takes off only in a mode that allows it, GUIDED here
  (`handle_MAV_CMD_NAV_TAKEOFF`, Copter-4.7.2). When the HEARTBEAT does not show GUIDED, `MAV_CMD_DO_SET_MODE` with
  GUIDED's custom mode is sent once; NAV_TAKEOFF follows only after its ACCEPTED **and** a HEARTBEAT showing GUIDED
  within 2.5 s (`GUIDED_CONFIRM_TIMEOUT`: two 1 Hz HEARTBEATs and margin), since an accepted mode change is not yet
  the mode. Otherwise the take off ends as mode not changed, with the mode change's result. Each step is sent only
  while the HEARTBEAT shows the vehicle armed and two independent signs say it is on the ground: an ON_GROUND landed
  state under 0.5 s old (REQ-052, #112) and a position under 0.5 s old at most 1 m above home and moving at most
  0.5 m/s vertically; all checked with the route under the sender's lock, since a mode change would abort a flying
  vehicle's landing or return. The mode change's late window is 1.5 s, and a new take off is refused meanwhile.
- **No answer may still have worked.** Once an attempt has left, a command ends as no answer, never as not sent, with
  the reason when the app stopped retrying early (link lost, route changed, replay).
- **One route.** Commands go to the active vehicle's autopilot (component 1) at the address of its first HEARTBEAT in
  the session, and an ACK counts only from there, from that system ID and component, naming the command, addressed to
  this GCS (255/190, or 0/0 when the extension fields are omitted). Another address blocks commands (accepting it is
  #105). Every attempt is checked under the sender's lock: never during a replay, to another system ID, while LOST or
  over a changed route (REQ-043).

## Alternatives considered

### QGroundControl's policy (no retries for flight commands)
- Pros: the reference GCS; simplest.
- Cons: an RTL lost on a noisy link leaves the operator to notice and send it again, against the protocol's "must be
  automatically re-sent".
- Why not: a late RTL or land is never the surprise a late arm is.

### Retry every command
- Pros: follows the protocol to the letter.
- Cons: an arm working on its third try, seconds after the operator gave up.
- Why not: the safety cost QGroundControl describes.

## Consequences

- Positive: an unanswered RTL or land is retried and ends in a clear result; arm can never arrive late by our doing.
- Negative / costs we accept: after an arm without answer, the operator cannot arm again for 10 s; an ACK later
  than the late window, or one for an RTL superseded by another RTL, can be taken as the next same command's answer;
  a forged unsigned ACK from the pinned address can fake an answer until signing (v0.6); a command sent before the
  link is lost ends as no answer, never as not sent; until #105 a change of the vehicle's address (a router restart,
  a second route) blocks every command for the rest of the session, which #105 must fix before any real flight; a
  take off stopped after an accepted mode change leaves the vehicle in GUIDED, armed on the ground, until it
  disarms by itself after `DISARM_DELAY`.
- Follow-ups: the operator accepts a new route (#105); signing authenticates the ACK (v0.6).
