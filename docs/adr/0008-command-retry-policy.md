# 0008. Retry only RTL and land; send arm once

- Status: Accepted
- Date: 2026-10-28
- Issue: #98

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
- **Arm's late window is 10 s** (`ARM_LATE_WINDOW`), 11.5 s from the send. An earlier arm's late FAILED taken as a
  new arm's answer would read "rejected" while the new arm arms the vehicle; refusing a new arm meanwhile covers round
  trips up to 11.5 s. The ACK has no sequence number, so no rule closes it for any latency: past that, the HEARTBEAT's
  armed state is the truth, and the screen shows it over any ACK (#104).
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
  a second route) blocks every command for the rest of the session, which #105 must fix before any real flight.
- Follow-ups: take off reuses this policy (#99); the operator accepts a new route (#105); signing authenticates the
  ACK (v0.6).
