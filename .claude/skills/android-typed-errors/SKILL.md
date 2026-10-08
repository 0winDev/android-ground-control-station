---
name: android-typed-errors
description: |
  Error handling conventions in GCS (Ground Control Station): sealed result types at the domain
  boundary, a parser that never throws on bad input, link and command failures folded into UI
  state. Trigger on: "error handling", "typed errors", "try/catch", "exceptions", "runCatching",
  "Result", "how do I handle failures", "repository error", "corrupted packet", "command timeout".
---

# Typed errors — GCS

Failures that can happen are visible in the type or in UI state — never a silent `null`, never a
raw exception reaching the UI. No Arrow: Kotlin sealed types are enough here.

## Expected failures are values

Anything that is a normal outcome in the field is modelled as a sealed type, not an exception:

```kotlin
// Illustrative
sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Rejected(val reason: CommandRejection) : CommandResult   // from COMMAND_ACK
    data object TimedOut : CommandResult                                // after retries
    data object LinkLost : CommandResult                                // blocked before sending
}

sealed interface MissionValidation {
    data object Valid : MissionValidation
    data class Rejected(val violations: List<MissionViolation>) : MissionValidation
}
```

- Domain rules (`:core:domain`) return these types.
- `:data:vehicle` maps protocol outcomes (ACK result codes, timeouts) into them.
- ViewModels fold them into `UiState`; failures stay visible until the operator acknowledges them.

## The parser never throws on input

Corrupted, truncated, unknown or malicious bytes are expected input for a radio link. The codec's
contract (hand-written by the owner — review it, never write it) is: bad input is **dropped and
counted**, never thrown. Exceptions from the parser are bugs. Tests with random bytes enforce it
(see `android-tests`).

## Exceptions are for bugs and infrastructure

- Programming errors (`require`/`check`) may throw; they are bugs to fix, not to catch.
- Infrastructure failures (socket cannot bind, port in use) are caught at the boundary that can act
  on them — the transport or repository — and turned into a state (`ConnectionState.Failed(reason)`).
- **Always rethrow `CancellationException`** before handling anything else:

```kotlin
try {
    socket.receive(packet)
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    _state.value = ConnectionState.Failed(TransportError.Io(e.message))
}
```

- Prefer catching specific exceptions over `Exception`; avoid `runCatching` around suspend calls (it
  swallows cancellation).

## Rules

- Give each real failure its own name in a sealed hierarchy; no `Exception("...")` inline.
- Safety-relevant failures (link lost, command rejected/timed out, mission upload failed, signature
  rejected) are always surfaced to the operator and logged.
- A half-done operation must leave a known state: a failed mission upload means "the vehicle keeps
  its previous mission", and the UI says so.
