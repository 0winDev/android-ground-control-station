# Contributing

This is the workflow every change follows, from an idea to a merged pull request. It applies to
humans and to AI assistants alike (see [`CLAUDE.md`](CLAUDE.md) for the assistant-specific rules).

Everything that lives in the repository — code, comments, docs, ADRs, requirements, issues, pull
requests and commit messages — is written in **English**.

## 1. Roadmap, milestones and epics

- Every roadmap phase (`v0.0` … `v1.0`, see the [README](README.md#status)) is a **GitHub milestone**
  and an **epic issue** (label `epic`) with native sub-issues.
- Child issues are created with the `new-phase` skill and **ordered by risk, not by the order they
  were proposed**: protocol and safety first, then data, then UI. A UI issue never starts while a
  protocol issue it depends on is open.
- Each phase has a single **"done when"** criterion. The phase is not closed until it holds.

## 2. Idea → issue

1. Discuss the idea first and close the product and safety decisions before any code: thresholds,
   priorities, what happens in edge cases (link loss mid-command, half-uploaded mission, stale data).
2. Create the issue with `/create-issue`. Every issue contains:
   - **Context** — what and why.
   - **Acceptance criteria** — verifiable checkboxes.
   - **Requirements covered** — the `REQ-xxx` IDs from [`docs/requirements.md`](docs/requirements.md).
     If a requirement is missing, add it in the same PR.
   - **Phase "done when"** — copied from the roadmap, so the issue can be judged against it.
   - **Known pitfalls** — protocol traps, ArduPilot quirks, things that bit us before.
3. Labels: one `phase:v0.x`, one or more `area:*`, plus `codec-review` when it touches the codec.
   Anything that comes up but is outside the phase gets its own issue with `out-of-scope`.

### Codec issues (`:core:mavlink`)

Issues that touch the MAVLink codec (framing, CRC, parsing, signing) carry the `codec-review` label
and are done **test-first**:

1. Tests are written first, **red**, from real packets captured from ArduPilot SITL and stored under
   `core/mavlink/src/test/resources/`, with expected values from a reference decoder.
2. The implementation follows until the tests pass.
3. The `mavlink-reviewer` agent reviews it against the official MAVLink specification.
4. The owner reviews every line; nothing is merged that the owner cannot explain line by line.

Red tests only live on the feature branch; the PR is opened as a **draft** until CI is green.

## 3. Branch

```bash
git fetch origin
git switch -c feature/<issue>-<slug> origin/main   # or fix/<issue>-<slug>
```

- Always branch from an up-to-date `origin/main`, never from a stale local `main`.
- Names: `feature/<issue>-<slug>` or `fix/<issue>-<slug>`, e.g. `feature/12-heartbeat-parser`.

## 4. Plan

With Claude Code, steps 3 and 4 start with `/start-issue <n>`: it checks the working tree, reads the
issue (and stops if it is closed, has no milestone or no acceptance criteria), summarizes it, assigns
it to you, proposes the branch and creates it from `origin/main` after your OK, reads the lessons
learned, the REQs, the related ADRs and the layer skill, and presents a plan that waits for your
approval. It never commits or pushes.

- Read the issue first: `gh issue view <n>`.
- Anything non-trivial is planned before it is implemented, and the plan is approved first.
- Use the repository skills for the layer you are touching (`android-feature`, `android-tests`,
  `compose-ui`, `android-modularization`, …).

## 5. Implement

- Module boundaries are enforced by `./gradlew verifyModuleGraph`; see the architecture section of
  the README. Features never talk to the codec or the network.
- UI state is an immutable `StateFlow`; no protocol logic in ViewModels.
- Safety-critical rules (mission validation, pre-flight checks, geo math, data staleness) live in
  `:core:domain`, in pure Kotlin.
- User-facing strings in English (`values/`) and Spanish (`values-es/`).
- Only the `common`-dialect messages the current phase needs. Never invent message fields or IDs —
  check the [MAVLink documentation](https://mavlink.io/en/messages/common.html).

## 6. Verify

Always:

```bash
./gradlew ktlintCheck detekt verifyModuleGraph lintDebug test assembleDebug
```

- Unit tests: JUnit 5, MockK, Kluent, Turbine; backtick `GIVEN … WHEN … THEN …` names and the
  three-block body (see `CLAUDE.md`).
- Every test that covers a requirement is tagged with it: `@Tag("REQ-012")`.
- **Update the requirement → test matrix** in `docs/requirements.md` in the same PR.

Additionally, depending on what the change touches:

| The change touches… | Extra verification |
|---|---|
| Protocol, telemetry or commands | Run against ArduPilot SITL and compare with QGroundControl connected to the same SITL (`sitl-verify` skill). Record the result in the PR. |
| Failsafe, commands, missions or message signing | Review with the `safety-reviewer` agent and address its findings. |
| Visible UI | Check it on a device or emulator and attach screenshots to the PR. |

Optional before committing: `/code-review` or `/simplify` on the diff.

## 7. Commit

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), small, one concept per
commit, with the issue number at the end of the subject:

```
feat: parse HEARTBEAT message (#12)
```

Allowed types (enforced by the `commit-msg` hook):

| Type | Use for |
|---|---|
| `feat` | New behavior |
| `fix` | Bug fix |
| `refactor` | Code change with no behavior change |
| `test` | Tests only |
| `docs` | Documentation only |
| `build` | Gradle, dependencies, convention plugins |
| `ci` | GitHub Actions, Dependabot |
| `chore` | Anything else (tooling, repo housekeeping) |

Hooks are installed with pre-commit (see the [README](README.md#git-hooks)). Never bypass them with
`--no-verify`.

## 8. Pull request

- Base: `main` (check it after `git fetch origin`; if stacked on another branch, retarget it when
  that branch is merged).
- Title: one descriptive sentence, **no type prefix**, e.g. "Parse HEARTBEAT and track link state per
  system ID".
- Body: always [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md). Tick **only** the
  boxes that are actually true; explain anything that could not be verified under Notes.
- No "Generated with …" footers in issues, PRs or commits.

## 9. Review and merge

- CI (`android-ci.yml`) must be green; `main` is protected and only accepts PRs with a green CI.
- Review comments are worked through with the `pr-review-resolver` agent: unambiguous fixes are
  applied, the rest get a drafted reply (in English).
- Whatever comes up in review and is out of scope becomes a new issue (`out-of-scope`), not more
  commits in the PR.
- **Control rule:** if you cannot explain every line of the PR without looking at it, it is not
  merged.
- Merge with a **merge commit** (no squash, no rebase). Only the owner merges.

## 10. Close

After each issue:

- Add the lessons learned to the "Lessons learned" section of `CLAUDE.md`.
- Pick the next issue following the order agreed in the phase epic.

After each phase:

1. Verify the phase's "done when" criterion.
2. Tag the merge commit on `main`: `git tag -a v0.X -m "v0.X <name>" && git push origin v0.X`.
3. Publish a GitHub Release from the tag (from `v1.0`, with the APK attached).
4. Update the status table in the README.
5. Run `pre-commit autoupdate` and open a `chore` PR if anything changed.
6. From `v0.3` on, the phase is worth a LinkedIn post (the first one goes out at `v0.3`).
