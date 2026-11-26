---
name: android-tests
description: |
  Add or update unit tests in GCS (Ground Control Station): JUnit 5 + MockK + Kluent + Turbine,
  backtick GIVEN/WHEN/THEN names, three-block bodies, verifyOnce/verifyNever helpers and
  MainDispatcherExtension from :core:testing, @Tag("REQ-xxx") traceability, and tests from
  captured SITL packets. Trigger on: "write a test", "unit test", "test the ViewModel",
  "test a use case", "MockK", "Kluent", "Turbine", "runTest", "GIVEN WHEN THEN", "fixture",
  "captured packet", "fuzz".
---

# Unit tests — GCS

The testing rules in `CLAUDE.md` are the source of truth and are mandatory. This skill shows how
they look in practice.

## Stack

| Concern | Library |
|---|---|
| Framework | JUnit Jupiter (`@Test`, `@BeforeEach`, `@Tag`, `@RegisterExtension`) |
| Assertions | Kluent (`org.amshove.kluent.*`) |
| Mocking | MockK |
| Flows | Turbine (`flow.test { }`) |
| Coroutines | `kotlinx-coroutines-test` (`runTest`) |
| Helpers | `:core:testing` (always `testImplementation`) |

All of it comes from the convention plugins; never add test dependencies by hand. No Robolectric.

## `:core:testing` helpers

Package `com.owindev.gcs.core.testing`:

- `verifyOnce { ... }`, `verifyNever { ... }`, `coVerifyOnce { ... }`, `coVerifyNever { ... }` —
  the only verification calls allowed (never plain `verify`/`coVerify`, never `exactly = n`).
- `MainDispatcherExtension` — JUnit 5 extension that sets `Dispatchers.Main` to a test dispatcher
  and resets it afterwards. Use it in every ViewModel test:
  `@RegisterExtension val mainDispatcher = MainDispatcherExtension()`.
- `readFixture(path: String): ByteArray` — reads a file from the test module's
  `src/test/resources` (captured SITL packets, `.tlog` excerpts).

## Traceability

Every test that covers a requirement is tagged with it — on the class if every test covers it, on
the method otherwise — and the matrix in `docs/requirements.md` is updated in the same PR:

```kotlin
@Tag("REQ-012")
@Test
fun `GIVEN ... WHEN ... THEN ...`() { ... }
```

## Anatomy

```kotlin
@Tag("REQ-030")
class MissionValidatorTest {

    private val limits = MissionLimits(maxAltitudeMetres = 120.0, maxDistanceFromHomeMetres = 500.0, maxWaypoints = 50)

    private lateinit var validator: MissionValidator

    @BeforeEach
    fun setUp() {
        validator = MissionValidator(limits)
    }

    @Test
    fun `GIVEN a waypoint above the maximum altitude WHEN validate THEN returns AltitudeTooHigh`() {
        val mission = missionWith(altitudeMetres = 150.0)

        val result = validator.validate(mission)

        result shouldBeEqualTo MissionValidation.Rejected(MissionViolation.AltitudeTooHigh(index = 0))
    }
}

private fun missionWith(altitudeMetres: Double): Mission = Mission(/* ... */)
```

(Illustrative — these types don't exist yet.)

Rules encoded there:

- Name: backticks, `GIVEN … WHEN … THEN …`; those words appear only in the name.
- Body: exactly three implicit blocks (given / when / then), one blank line between them, no blank
  lines inside a block, no comments labelling the blocks.
- The WHEN block assigns its result (`val result = …`); no inline assertions.
- For suspend calls: `val deferred = async(start = CoroutineStart.UNDISPATCHED) { … }` then
  `val result = deferred.await()`.
- SUT is a `lateinit var` right before `@BeforeEach`, named by role (`validator`, `repository`,
  `viewModel`, `useCase`). `@BeforeEach` only builds the SUT — no test-specific stubbing.
- Mocks typed explicitly: `val transport: UdpTransport = mockk()`, `val slot: CapturingSlot<Foo> = slot()`.
  No `relaxed = true`. `just runs` (`import io.mockk.Runs as runs`), never `just Runs`/`returns Unit`.
- Test data: deterministic, private builders at the end of the file, repeated literals as `val`s.

## Flows with Turbine

```kotlin
@Test
fun `GIVEN no heartbeat for the timeout WHEN observing the link THEN emits Lost`() = runTest {
    val watchdog = LinkWatchdog(timeout = 3.seconds, clock = testClock)

    watchdog.state.test {
        advanceTimeBy(3.seconds + 1.milliseconds)
        val result = expectMostRecentItem()
    }
    ...
}
```

If a three-block structure is impossible inside `test { }`, collect the items into a `val` in the
WHEN block and assert after it.

## Codec tests (`:core:mavlink`, `codec-review` issues)

These tests are written **before** the code under test (test-first) and start red.

- Inputs are **real packets captured from ArduPilot SITL**, stored under
  `core/mavlink/src/test/resources/<message>/…` and read with `readFixture`. Document in a sibling
  `README.md` how each capture was taken (SITL version, vehicle, command).
- Expected values come from the capture's decoding by a reference tool (QGroundControl/MAVLink
  Inspector, `pymavlink`), never from the implementation.
- Cover: valid frame; bad CRC; wrong CRC_EXTRA; truncated payload (MAVLink 2 trailing-zero
  truncation); unknown message ID; garbage between frames; split frames across datagrams.
- Robustness: a fuzz-style test feeding random bytes (fixed seed) must never throw.
- Field names, IDs and CRC_EXTRA values must match the official definitions
  (`mavlink.io/en/messages/common.html`, `message_definitions/v1.0/common.xml`). If unsure, ask —
  never invent them.

## Mutation checks

A green test proves its rule only once a mutation of that rule turns it red (#68, #71, #74, #98, #99, #104, #139,
#143).

1. Copy the file aside before mutating it: `git checkout -- <file>` also drops the uncommitted fix.
2. Mutate one condition at a time: in a gate of two checks, one failing hides the other.
3. Run the narrow task without `--rerun` and read the build output: a mutant that does not compile, or a task that is
   UP-TO-DATE or FROM-CACHE, looks like a survivor.
4. The test must fail for the mutated reason, not another; then restore the copy and run it green.

A test that reads a source file (`values-es/strings.xml`, `res/xml`, `assets`) declares it as an input of the module's
test task, or the build cache replays an old pass:

```kotlin
tasks.withType<Test>().configureEach {
    inputs.file("src/main/res/values-es/strings.xml").withPathSensitivity(PathSensitivity.RELATIVE)
}
```

## What to test

Every domain rule, use case, repository, mapper with logic, state machine and ViewModel. Not data
classes without logic, not DI modules.

## Running

```bash
./gradlew :core:domain:test --tests "com.owindev.gcs.core.domain.*"     # JVM module
./gradlew :data:vehicle:testDebugUnitTest                                 # Android module
./gradlew test                                                            # everything
```
