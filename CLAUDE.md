# CLAUDE.md

Source of truth for how AI assistants (Claude Code and any other) work in this repository.
`AGENTS.md` points here. The workflow itself is in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Project

GCS (Ground Control Station) is an Android app that acts as a ground control station for ArduPilot
drones over **MAVLink v2 on UDP**: real-time telemetry, a map, and automatic routes (waypoint
missions the drone flies on its own). It is a portfolio project aimed at defense and aerospace
companies, built to be defended line by line in an interview.

- **Product view:** an operator plans and supervises automatic flights from a tablet. Not a
  joystick: manual piloting stays on the physical transmitter.
- **Stack:** Kotlin, Jetpack Compose + Material 3, Coroutines/Flow/StateFlow, Hilt, MVVM + Clean
  Architecture, multi-module, Gradle Kotlin DSL + version catalog + `build-logic` convention plugins.
  Later phases add MapLibre Compose (v0.3) and Room (v0.5).
- **Target:** ArduPilot (ArduCopter) only until v1.0, developed and tested exclusively against
  ArduPilot SITL, with QGroundControl as the reference.
- **Educational project, not for real operations.** "Inspired by" sector practices (DO-178C-style
  traceability, MASVS), never "compliant with".
- Package `com.owindev.gcs`, minSdk 26, JDK 21 toolchain.

Roadmap and status: README. Requirements: `docs/requirements.md`. Decisions: `docs/adr/`.
Security checklist: `docs/security.md`.

## Non-negotiable rules

1. **The MAVLink codec (`:core:mavlink`) is built test-first.** Every change to framing, CRC,
   parsing or signing starts with red tests from real packets captured from ArduPilot SITL; the
   implementation follows until they pass; the `mavlink-reviewer` agent reviews it against the
   official specification; and nothing is merged until every line has been reviewed and can be
   explained.
2. **`:core:mavlink` is pure Kotlin (JVM)**: no Android dependencies, no external MAVLink library.
   `verifyModuleGraph` enforces it.
3. **Never invent** MAVLink message fields, IDs, enums, CRC_EXTRA values, ArduPilot parameters or
   library versions. Check the official docs (mavlink.io, `common.xml`, ArduPilot docs, Maven
   Central / Google Maven / Gradle Plugin Portal) and cite them. If you cannot verify, ask.
4. Only the `common`-dialect messages the current phase needs — never generate the whole dialect.
5. **No dependencies before their phase** (no MapLibre before v0.3, no Room before v0.5, …).
6. **Everything in the repo is in English** — code, comments, docs, ADRs, requirements, issues, PRs,
   commits — and carries **no "Generated with Claude Code" footer** or AI co-author trailer.
   Conversation with the owner is in Spanish.
7. **GitHub actions need the owner's OK first**: show exactly what will be created or changed
   (labels, milestones, issues, repository settings) and wait. **Never merge a PR.**
8. Never commit secrets: the MAVLink signing key lives in Android Keystore-protected storage, never in
   code, resources, logs or the repo.

## How each piece is built

| Piece | How | Why |
|---|---|---|
| Gradle, modules, CI, detekt, Compose UI | Reviewed in the PR | Already mastered in a previous project |
| MAVLink codec (framing, CRC, parsing, signing) | Test-first from SITL captures, `mavlink-reviewer` review, **every line reviewed and explained** | The core of the interview |
| Mission upload state machine | The state diagram is drawn first; the code follows it | Must be drawable on a whiteboard |
| Geo math (distances, bearings, MGRS) | Every formula is explained with its source in the PR | Learn just enough georeferencing |
| README, ADRs, diagrams | Each ADR records context, decision and consequences, and is reviewed in its PR | Every decision must be defensible |

**Control rule:** a PR that cannot be explained without looking at it is not merged.

## Architecture

```
:app ──► everything (Hilt wiring, navigation)
:feature:{hud,map,mission} ──► :core:domain, :core:designsystem
:data:vehicle ──► :core:domain, :core:mavlink, :core:transport
:core:domain, :core:mavlink, :core:transport, :core:designsystem ──► nothing
:core:testing ──► test-only helper, used as testImplementation
```

- **Features never talk to the codec or the network**, not even transitively. They read state from
  `:core:domain` interfaces, implemented by `:data:vehicle` and bound in `:app` with Hilt.
- **`:core:domain` (pure Kotlin)** holds domain models, repository interfaces and the
  safety-critical rules: mission validation, pre-flight checks, geo math, data staleness.
- **`:data:vehicle`** maps MAVLink ↔ domain, keeps vehicle state **indexed by system ID (sysid)**
  from the start, runs the link watchdog, command retries and the mission protocol.
- UI state is exposed as an **immutable `StateFlow`**; **no protocol logic in ViewModels**.
- Dangerous commands (arm, takeoff, mission change in flight) always need explicit confirmation.
- Module rules are enforced by `./gradlew verifyModuleGraph`. Details: `android-modularization` skill.

## Workflow summary

Full version in [`CONTRIBUTING.md`](CONTRIBUTING.md).

1. Each roadmap phase = milestone + epic; child issues ordered by risk (protocol and safety → data →
   UI), created with `new-phase` / `/create-issue`, with REQ-xxx, the phase "done when" and known
   pitfalls.
2. Branch `feature/<issue>-<slug>` or `fix/<issue>-<slug>` from an up-to-date `origin/main`
   (`git fetch origin` first).
3. Start every issue with `/start-issue <n>` (checks, summary, assignment, branch, required reading,
   plan); get the plan approved before writing code.
4. `codec-review` issues: red tests from SITL captures → implementation → `mavlink-reviewer`
   review → line-by-line review.
5. Verify: `./gradlew ktlintCheck detekt verifyModuleGraph lintDebug test assembleDebug`; SITL vs
   QGroundControl (`sitl-verify`) for protocol/telemetry/commands; `safety-reviewer` for failsafe,
   commands, missions or signing; always update the REQ → test matrix.
6. Commit with `/smart-commit`: Conventional Commits `feat|fix|refactor|test|docs|build|ci|chore`,
   one concept per commit, `(#<issue>)` at the end.
7. PR with the template, title without prefix, only true checkboxes, milestone, labels (phase, type, areas) and the
   owner as assignee; CI green; merge commit by the owner only.
8. After each issue: add lessons learned below, pick the next issue in the epic's order. After each
   phase: tag `vX.Y`, GitHub Release, README status.

## Agents and skills

- Agents: `mavlink-reviewer`, `safety-reviewer`, `pr-review-resolver`, `flaky-test-debugger`,
  `module-cleaner`, `crash-investigator`.
- Commands: `/start-issue`, `/create-issue`, `/smart-commit`.
- Project skills: `new-phase`, `adr`, `sitl-verify`, `android-feature`, `android-modularization`,
  `android-hilt`, `android-mvi`, `android-tests`, `android-typed-errors`, `compose-ui`,
  `android-cli`, `navigation-3` (decision pending). Generic third-party skills are not part of the
  repository.

## Kotlin and Android standards

- Kotlin only; Compose for UI; Gradle Kotlin DSL; version catalog (`gradle/libs.versions.toml`);
  no Groovy.
- Module build files apply `gcs.*` convention plugins and declare only their own dependencies.
- Prefer immutable data and `val`; `data class` where it fits the domain; small, focused classes;
  composition over inheritance; explicitness over magic; no unnecessary abstractions.
- Constructor injection. Pure JVM modules have no DI annotations; they are provided from
  `:data:vehicle`/`:app` Hilt modules.
- Coroutines and Flow for async work; explicit dispatchers (`Dispatchers.IO` for sockets); structured
  concurrency; always rethrow `CancellationException`; no blocking calls on the main thread.
- Expected failures are sealed types; the parser never throws on input (see `android-typed-errors`).
- Inject clocks for anything time-based (watchdog, staleness, retries) so it is testable.
- Avoid allocations in hot paths (packet receive/parse loop).
- No deprecated Android APIs.
- User-facing strings in `values/` (English) and `values-es/` (Spanish).

## Code style

- Max line length 120; ktlint and detekt must be clean (`ktlintFormat` fixes formatting).
- Meaningful names; short functions; multiline formatting when a call or constructor has more than
  one argument.
- Extract repeated literals to named constants; no magic numbers in protocol or safety code.

## Comments and documentation

Write as little as the reader needs, once.

**Code**
- The code explains itself: clear names and small functions before comments.
- Comment only what the code cannot say: a non-obvious reason, a spec citation, a known pitfall. No comments that
  restate the line, narrate steps, or tell history or changes.
- KDoc only on public APIs of `:core:*` modules, in one or two sentences: what it does and what it guarantees. Do not
  repeat the name or obvious parameters.
- Tests: the name explains the case; no comments inside unless there is a real pitfall.

**Documentation**
- PR description: what changes, why, and how it was verified; about 15 lines at most. No review chronicle, no tables
  of everything.
- Commits: subject, plus a short body only when the reason is not obvious.
- ADR: one page (context, decision, consequences).
- Fixture README: one table (file, origin, what it proves), no prose.
- Requirements matrix: one line per row.
- Lessons learned: only what prevents a future mistake, one line each.

## Testing rules

Stack: JUnit 5, MockK, Kluent, Turbine, `kotlinx-coroutines-test`, helpers from `:core:testing`.
No Robolectric. Details and examples: `android-tests` skill.

**Naming and structure**
- Names in backticks following `GIVEN ... WHEN ... THEN ...`; those words appear only in the name.
- The body has exactly 3 implicit blocks (given, when, then) separated by one blank line each; no
  other blank lines, no block comments.
- The WHEN block assigns its result to a variable (`val result = ...`); no inline assertions. For
  suspend calls: `val deferred = async(start = CoroutineStart.UNDISPATCHED) { ... }` then
  `val result = deferred.await()`. Don't create `val result` if it is never used.
- Every test covering a requirement has `@Tag("REQ-xxx")`.

**SUT and setup**
- The SUT is a `lateinit var` placed right before `@BeforeEach`, with a simplified name
  (`FooUseCase` → `useCase`, `FooRepository` → `repository`, `FooViewModel` → `viewModel`).
- Every test class has a minimal `@BeforeEach` that builds the SUT; no test-specific stubbing there.
- ViewModel tests register `MainDispatcherExtension` from `:core:testing`.

**MockK**
- Typed declarations: `val transport: UdpTransport = mockk()`, `val captured: CapturingSlot<Foo> = slot()`;
  never `mockk<Foo>()` or `slot<Foo>()`.
- No `relaxed = true`; avoid `relaxedMockk()`.
- `every`/`coEvery` for stubbing; `just runs` (`import io.mockk.Runs as runs`), never `just Runs` or
  `returns Unit`.
- Verification only with `verifyOnce`, `verifyNever`, `coVerifyOnce`, `coVerifyNever` from
  `:core:testing`: never plain `verify`/`coVerify`, `verify(exactly = n)` or `wasNot Called`; never
  declare local helpers with those names.
- Verification blocks are multiline, without blank lines; group positive checks in one `verifyOnce`
  and negative ones in one `verifyNever`; never leave a `verifyNever` block empty.

**Data**
- Deterministic test data (fixed seeds for random/fuzz tests); repeated values extracted to `val`s;
  explicit expected values; private builders at the end of the file.
- Codec tests use real captured SITL packets from `src/test/resources` (`readFixture`); expected
  values come from a reference decoder, never from the implementation.
- `()` instead of `.invoke()`. For boolean pairs, write the enabled case first.

## Lessons learned

What surprised us and what to do differently, by topic. After each issue, add a line under its topic or append
the issue number to an existing one.

**Protocol and XML**
- Messages hide in includes: HEARTBEAT is in `minimal.xml`, GLOBAL_POSITION_INT in `standard.xml`; search every
  include before saying a message is missing (#8, #37).
- Extension fields keep XML order on the wire; only base fields are sorted by size (#35).
- Zero-fill cannot produce a field's "invalid" value (COMMAND_ACK `progress` unknown is 255), and a field can have
  two (GPS_RAW_INT `yaw`: 0 and UINT16_MAX): read each `invalid` attribute and never take a restored 0 as real
  (#35, #38).
- Truncation cuts bytes, not fields (throttle 65 = `41 00` gives LEN 19), HEARTBEAT never truncates, and truncation
  only makes sense through the parser: CRC over the wire bytes, then zero-fill (#5, #10, #37).
- A uint64 extension below 2⁵⁶ always truncates (a boot-time `time_usec` cuts HOME_POSITION to LEN 56): a
  full-length fixture needs its top byte non-zero (#66).
- The XML is not the whole story; read ArduPilot's code. It sends `voltage_battery` 0 without a monitor, keeps the
  voltage coming after its battery failsafe clears the health bit, and sends GLOBAL_POSITION_INT 0°, 0° before a
  position and frozen after losing it: a fresh message is not a fresh value (#39, #41, #42).
- ArduPilot keeps message rates per channel, streams only to a TCP channel it hears from and forgets the rates on
  reboot: measure on the app's own channel and request the streams again (#36, #41).
- An ArduPilot reboot is shorter than the link timeout (1.2 s without HEARTBEAT in SITL), so it never goes LOST,
  and the channel then gets no streams until asked again: detect it from `time_boot_ms` going back (#69).
- ArduPilot sends HOME_POSITION once, unrequested, when it sets or moves home, and nothing before: its silence is
  normal, never a reason to request it again (#69).
- Rejecting a value must never erase state: drop the invalid frame instead of turning it into a `null` part. Compare
  ±π ranges as float32 and integer ranges with `in`, not `abs()` (`abs(Int.MIN_VALUE)` overflows) (#49).

**Test data and references**
- Edge-case frames (signed, odd flags, out of range) and expected LENs come from pymavlink and a regeneration script;
  MGRS letters from the reference converters' output. By hand, two of #40's reasons were wrong (#9, #37, #40).
- Decode every fixture you reuse with pymavlink first, and list existing captures before planning a new one: a
  fixture is not always what its folder suggests (#35, #38).
- A false start marker with a plausible header keeps the parser waiting for a full frame: noise tests must keep the
  stream going (#9).
- Adding a message to the table breaks tests that borrowed it as "unknown": pick one the roadmap will never decode
  (#37).
- Kluent compares maps in iteration order: sort both sides (#10).
- pymavlink and the C library drop every byte a corrupted LEN claims after a bad checksum, while our parser drops
  only the start marker: pymavlink proves a corrupted frame is invalid, not which frames come after it (#67).
- A reader that does not check checksums cannot anchor a plausibility window on one record: a false record taken
  from garbage would anchor a timestamp no real record is near. Anchor on two consecutive records (#68).
- A test of a tolerant reader can pass for the wrong reason (another recovery path masked the rule): check it with a
  mutation of the rule it guards (#68, #71).
- On a 1 m boundary a nanometre decides the MGRS digit, so reference points stay at least 1 cm off one; GEOTRANS
  2.4.2 is not sub-mm accurate and disagrees with GeoConvert at exactly 84°N (#40).

**Flow and time**
- A `SharedFlow` with replay hands an old message to a late subscriber as new: messages go without replay, what late
  subscribers need goes in a `StateFlow` (#11).
- `stateIn` keeps its last value after it stops and `WhileSubscribed` with no consumer evaluates nothing: time-based
  state must not depend on observers, screens let their state expire (`replayExpiration = ZERO`), and a SITL check
  before the UI exists needs a temporary, uncommitted observer (#11, #13, #41).
- A `StateFlow` conflates a status set and replaced in the same instant: the single writer sends transitions through
  a `Channel`, and tests check statuses by type or time, never by `take(n)` (#31, #36).
- In virtual time a timeout and an answer due at the same instant race: keep test timings off each other's edges
  (#36).
- A `try/catch` around `collect` also catches what `emit` throws downstream: catch the upstream with `.catch`.
  `shareIn` does not hand upstream crashes to subscribers, so such a test needs its own scope with a handler (#25).
- One event can show on several messages: a reboot seen on ATTITUDE and again on GLOBAL_POSITION_INT restarted the
  request round halfway, so detect it once per system (#69).
- Anything that waits for an answer subscribes to `messages` and reopens a closed socket: gate background sends on
  "the link is listened to", not only on CONNECTED (#39).
- Two sessions of the same kind look alike in a conflated `StateFlow`: tell them apart by an epoch, not only by
  kind (#71).
- A consumer started lazily must start from the producer's first state, never its current one, or it misses the
  change it started after (#71).
- A clock followed after it started running is anchored on its position, or the time jumps ahead and later freezes
  (#71).
- A value passed into a Navigation 3 entry stays the one the entry was built with: read the theme's palette and
  other changing state inside the screen, never as a captured parameter (#61).
- A value read inside `combine` from outside its inputs can belong to a newer emission than the inputs: publish what
  must match (a trail and its session) as one value (#72).
- A camera move restarted for every position (5 Hz) is cancelled before the camera moves: follow with one conflated
  loop, and check your own timing before blaming the library's gesture reason (#73).
- Ages shown side by side need one clock and one rounding, or the banner says 116 s and the panel 117 s (#73).
- An activity-scoped `Eagerly` collector keeps the link alive in the background: use `repeatOnLifecycle` (#74).
- A blocking read ignores cancellation: close its stream on cancel, or a stalled provider holds the lock (#74).

**Safety review**
- A state held to avoid flicker needs a time limit, or a silent socket keeps it forever (#31).
- Whatever is kept per system ID grows with every made-up ID on the link: keep history (the trail) for the
  active vehicle only (#72).
- An occurrence id fixes a race in the repository, not on screen: a banner that swaps alerts under a finger needs a
  guard, and an acknowledged caution keeps its level beside a newer advisory (#56).
- A warning must say its true reason: "No 3D GPS fix" over a stale RTK fix sent the operator the wrong way (#73).
- A hidden button still gets a tap drawn a frame earlier: check the rule again when the action runs (#74).
- Retention's "keep the newest" picks the file being added: name the one kept before adding it (#74).
- Log no recording names or exception messages: an imported file carries the name the operator picked (#74).

**SITL and QGroundControl**
- Mission Planner's simulator on Windows is enough (no WSL/Docker). Its mirror binds the port it sends to and only one
  works at a time, so QGroundControl connects to SITL directly on TCP 5762 (#5).
- The simulator updates itself: query AUTOPILOT_VERSION every session and record it with every capture (#5, #39,
  #41).
- SITL tools send their own HEARTBEAT and request their streams; pymavlink's TCP `autoreconnect` gives up after 3
  tries, so pass `retries` when the autopilot reboots (#41, #42).
- Changing `BATT_CAPACITY` does not recharge the battery: relaunch the simulator (#56).
- Check QGroundControl's UI in the docs for the installed version, and compare values against its MAVLink Inspector
  (#42).
- Measure timings in SITL before documenting them: on the API 36 emulator Android blocks a background app's network
  after about 5 s (`sendto` → `EPERM`) (#14).
- In airplane mode the emulator's bound UDP socket never fails, so a transport failure cannot be reproduced there
  (#25).
- A SITL TCP port can stay silent for a whole session (5762 sent nothing while 5763 worked): count raw bytes on it
  first (#70).
- In flight the map must be checked moving, not only hovering: a camera bug showed only at 10 m/s (#73).
- Other GCSs' HEARTBEATs (sysid 255) keep the GCS failsafe off: check the app's own HEARTBEAT stop instead (#74).

**Emulator, Windows and tooling**
- `adb emu redir` rules die with the emulator and stale ones keep feeding it: run `adb emu redir list` first and
  remove old redirects (#13, #14).
- QGroundControl's UDP AutoConnect holds host port 14550: redirect a free port (`adb emu redir add udp:14560:14550`)
  and check who holds a port with `netstat -ano` (#6).
- The emulator's clock can run seconds behind the host: match logcat to the relay log by events (#39).
- On Windows `NUL` claims to be a terminal (scripts in the background need an explicit flag instead of `isatty()`),
  and Python writes CRLF unless `newline="\n"`, which ktlint rejects (#14, #56).
- ktlint and detekt skip KDoc line length: check it by hand (#35).
- Claude Code's auto mode silently denies edits under `.claude/`: leave auto mode for those instead of retrying (#8).
- On Windows `@TempDir` cannot delete a file a test leaves open: close what the SUT opened in `@AfterEach` (#70).
- CI never runs `check`: its Coverage step calls each module's `koverVerify` by name, so a module with a new
  minimum must be added there too, or the minimum only fails locally (#63).
- On Windows `File.canonicalFile` does not resolve symbolic links: check a path's folder with `toRealPath()` (#71).
- Never reflow KDoc by script: it merges lists and rewraps untouched paragraphs; wrap long lines by hand (#71).
- MapLibre's engine ignores the system proxy and draws no overlay before a style is presented: test a dead network
  with a build that points the style at an unreachable host, and start the map on an offline style (#73).
- A night map is the day style recoloured, not another style (`fiord` lost labels); OpenFreeMap answers 403 to a
  script without an identifying User-Agent (#73).
- A screen holding MapLibre content needs `@UiComposable`, or the compiler infers MapLibre's applier for callers (#73).
- Never `--no-verify` a split commit: run the hooks on it, they check the files as staged (#73).
- `git checkout -- <file>` to undo a mutation also drops the uncommitted fix: copy the file aside first (#74).
- A fixed-width child in a `Row` leaves its weighted sibling 0 dp and the text wraps one letter per line: check every
  new row of buttons at 360 dp on the phone emulator, not the tablet's `wm size` (#61).
- Check a phone at 360 × 640 dp with every banner on (replay, LINK LOST): at 360 × 780 the HUD fitted, at 640 the
  telemetry got 0 dp (#61).
