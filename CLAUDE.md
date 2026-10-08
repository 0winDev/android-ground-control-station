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

1. **The MAVLink codec (`:core:mavlink`) is written by the owner, by hand.** Never implement or edit
   framing, CRC, parsing or signing code in `core/mavlink/src/main/` — not even a one-line fix.
   You may create the empty module, write tests (red, from captured SITL packets) and review the
   owner's code (`mavlink-reviewer` agent). `.claude/settings.json` enforces this with a deny rule,
   `Edit(/core/mavlink/src/main/**)`: it blocks Claude's file tools, recognized Bash file commands
   (`sed`, `tee`…) and redirections there. It cannot block a script that opens files itself, so this
   written rule still applies in full; never work around the deny rule.
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

## What is delegated and what is not

| Piece | Who | Why |
|---|---|---|
| Gradle, modules, CI, detekt, Compose UI | Claude implements, owner reviews | Already mastered in a previous project |
| MAVLink codec (framing, CRC, parsing, signing) | **Owner writes**, Claude reviews and writes tests | The core of the interview |
| Mission upload state machine | Owner designs the diagram, Claude implements | Must be drawable on a whiteboard |
| Geo math (distances, bearings, MGRS) | Claude implements, owner explains each formula in the PR | Learn just enough georeferencing |
| README, ADRs, diagrams | Claude drafts, owner rewrites in their own words | Must sound like the owner |

**Control rule:** if the owner cannot explain a PR without looking at it, it is not merged.

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
4. `hand-written` issues: Claude writes red tests from SITL captures → owner implements →
   `mavlink-reviewer` reviews.
5. Verify: `./gradlew ktlintCheck detekt verifyModuleGraph lintDebug test assembleDebug`; SITL vs
   QGroundControl (`sitl-verify`) for protocol/telemetry/commands; `safety-reviewer` for failsafe,
   commands, missions or signing; always update the REQ → test matrix.
6. Commit with `/smart-commit`: Conventional Commits `feat|fix|refactor|test|docs|build|ci|chore`,
   one concept per commit, `(#<issue>)` at the end.
7. PR with the template, title without prefix, only true checkboxes; CI green; merge commit by the
   owner only.
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
- Comments explain *why*, not *what*; KDoc on public APIs of `:core:*` modules.

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

Add one line per closed issue: what surprised us and what to do differently.

- (none yet)
