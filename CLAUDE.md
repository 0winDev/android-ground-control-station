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
  `android-cli`, `navigation-3` (ADR 0007). Generic third-party skills are not part of the
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
- Messages hide in includes (HEARTBEAT in `minimal.xml`, GLOBAL_POSITION_INT in `standard.xml`): search all (#8, #37).
- Extension fields keep XML order on the wire; only base fields are sorted by size (#35).
- Zero-fill cannot give a field's "invalid" value, and a field may have two: read each `invalid` (#35, #38).
- Truncation cuts bytes, not fields, and never HEARTBEAT: CRC over the wire bytes, then zero-fill (#5, #10, #37).
- A uint64 extension below 2⁵⁶ always truncates: a full-length fixture needs its top byte non-zero (#66).
- Read ArduPilot's code, not only the XML: a fresh message is not a fresh value (#39, #41, #42).
- ArduPilot keeps rates per channel, streams only to a TCP channel it hears and forgets them on reboot (#36, #41).
- An ArduPilot reboot never goes LOST: detect it from `time_boot_ms` going back, then request the streams (#69).
- ArduPilot sends HOME_POSITION only when it sets or moves home: its silence is never a reason to ask (#69).
- Rejecting a value never erases state: drop the frame; compare ±π as float32 and integers with `in` (#49).

**Test data and references**
- Edge-case frames, expected LENs and MGRS letters come from pymavlink and reference tools, not by hand (#9, #37, #40).
- Decode every reused fixture with pymavlink and list the existing captures before planning a new one (#35, #38).
- A log may hold a message only as MAVLink 1 (Mission Planner's parameter download): count its MAVLink 2 frames (#97).
- A false start marker with a plausible header holds the parser: noise tests must keep the stream going (#9).
- Adding a message breaks tests that borrowed it as "unknown": borrow one the roadmap will never decode (#37).
- Kluent compares maps in iteration order: sort both sides (#10).
- pymavlink drops all a corrupted LEN claims, our parser only the marker: it proves a frame invalid, no more (#67).
- A reader without checksums anchors its plausibility window on two consecutive records, never one (#68).
- A tolerant reader's test can pass for the wrong reason: check it with a mutation of the rule it guards (#68, #71).
- A mutation that does not compile looks like a survivor: read the build output before trusting it (#98).
- MGRS reference points stay 1 cm off a 1 m boundary; GEOTRANS and GeoConvert disagree at exactly 84°N (#40).

**Flow and time**
- A `SharedFlow` with replay hands old messages over as new: messages go without replay, state in a `StateFlow` (#11).
- `stateIn` keeps its last value and evaluates nothing unobserved: time rules never depend on observers (#11, #13, #41).
- A `StateFlow` conflates a status set and replaced at once: use a `Channel`, and never test with `take(n)` (#31, #36).
- In virtual time a timeout and an answer due together race: keep test timings off each other's edges (#36).
- `try/catch` around `collect` catches what `emit` throws downstream: catch the upstream with `.catch` (#25).
- `shareIn` does not hand upstream crashes to subscribers: such a test needs its own scope with a handler (#25).
- One event can show on several messages: detect a reboot once per system (#69).
- Anything waiting for an answer reopens a closed socket: gate background sends on "the link is listened to" (#39).
- Two sessions of one kind look alike in a conflated `StateFlow`: tell them apart by an epoch (#71).
- A consumer started lazily starts from the producer's first state, never its current one (#71).
- A clock followed after it started running is anchored on its position, or time jumps ahead then freezes (#71).
- A value passed into a Navigation 3 entry never changes: read the palette and changing state inside the screen (#61).
- A value read in `combine` from outside its inputs can be newer than them: publish what must match as one value (#72).
- A camera move restarted at 5 Hz never moves the camera: follow with one conflated loop (#73).
- Ages shown side by side need one clock and one rounding, or the banner says 116 s and the panel 117 s (#73).
- An activity-scoped `Eagerly` collector keeps the link alive in the background: use `repeatOnLifecycle` (#74).
- A blocking read ignores cancellation: close its stream on cancel, or a stalled provider holds the lock (#74).
- A fake transport that never suspends hides `withContext`'s cancellation on return: make it suspend (#98).
- A cancelled job cleans up late: key shared state by request identity, never by kind (#98).

**Safety review**
- A state held to avoid flicker needs a time limit, or a silent socket keeps it forever (#31).
- Whatever is kept per system ID grows with made-up IDs: keep history (the trail) for the active vehicle only (#72).
- An occurrence id fixes the repository's race, not the screen's: a banner swapping under a finger needs a guard (#56).
- A warning must say its true reason: "No 3D GPS fix" over a stale RTK fix sent the operator the wrong way (#73).
- A hidden button still gets a tap drawn a frame earlier: check the rule again when the action runs (#74).
- Retention's "keep the newest" picks the file being added: name the one kept before adding it (#74).
- Log no recording names or exception messages: an imported file carries the name the operator picked (#74).

**SITL and QGroundControl**
- Mission Planner's simulator on Windows is enough; QGroundControl connects to SITL directly on TCP 5762 (#5).
- The simulator updates itself: query AUTOPILOT_VERSION every session and record it with every capture (#5, #39, #41).
- SITL tools send their own HEARTBEAT and stream requests; pass pymavlink `retries` across a reboot (#41, #42).
- Changing `BATT_CAPACITY` does not recharge the battery: relaunch the simulator (#56).
- Check QGroundControl's UI in the installed version's docs and compare values with its MAVLink Inspector (#42).
- Measure timings in SITL before documenting them: API 36 blocks a background app's network after about 5 s (#14).
- In airplane mode the emulator's bound UDP socket never fails: a transport failure cannot be reproduced there (#25).
- A SITL TCP port can stay silent for a whole session: count raw bytes on it first (#70).
- Check the map in flight, moving, not only hovering: a camera bug showed only at 10 m/s (#73).
- Other GCSs' HEARTBEATs keep the GCS failsafe off: check that the app's own HEARTBEAT stops instead (#74).
- A receiver's own app passes its permission: prove the guard from another UID or by the installed manifest (#98).

**Emulator, Windows and tooling**
- `adb emu redir` rules die with the emulator and stale ones keep feeding it: run `adb emu redir list` first (#13, #14).
- QGroundControl's UDP AutoConnect holds host port 14550: redirect a free port; find holders with `netstat -ano` (#6).
- The emulator's clock can run seconds behind the host: match logcat to the relay log by events (#39).
- On Windows `NUL` claims to be a terminal and Python writes CRLF unless `newline="\n"` (#14, #56).
- ktlint and detekt skip KDoc line length: check it by hand (#35).
- Claude Code's auto mode silently denies edits under `.claude/`: leave auto mode for those (#8).
- On Windows `@TempDir` cannot delete a file left open: close what the SUT opened in `@AfterEach` (#70).
- CI calls each module's `koverVerify` by name, never `check`: a module with a new minimum is added there (#63).
- Logic-free types in `:core:domain` sink its Kover ratio: exclude them by name, and drop one that gains logic (#98).
- On Windows `File.canonicalFile` does not resolve symbolic links: check a path's folder with `toRealPath()` (#71).
- Never reflow KDoc by script: it merges lists and rewraps untouched paragraphs (#71).
- MapLibre ignores the system proxy: test no network with a build pointing the style at an unreachable host (#73).
- MapLibre draws no overlay before a style is presented: start the map on an offline style (#73).
- A night map is the day style recoloured; OpenFreeMap answers 403 to a script without a User-Agent (#73).
- A screen holding MapLibre content needs `@UiComposable`, or callers get MapLibre's applier (#73).
- Never `--no-verify` a split commit: the hooks check the files as staged (#73).
- `git checkout -- <file>` to undo a mutation also drops the uncommitted fix: copy the file aside first (#74).
- A fixed-width child in a `Row` leaves its weighted sibling 0 dp: check new button rows at 360 dp on a phone (#61).
- Check a phone at 360 × 640 dp with every banner on (replay, LINK LOST), not only at 360 × 780 (#61).
