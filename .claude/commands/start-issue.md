---
description: Start work on a GCS issue following CONTRIBUTING.md — checks, summary, self-assignment, branch from origin/main, required reading and a plan that waits for approval. No commits, no pushes.
argument-hint: <issue number>
---

You are starting work on GitHub issue `#$ARGUMENTS` of GCS (Ground Control Station).

If `$ARGUMENTS` is empty or not a number, ask for the issue number and stop.

Run the steps **in order**. If any step fails or a check does not pass, say what failed and **stop**.
Never commit and never push in this command.

## 1. Repository state

```bash
git fetch origin
git status --porcelain
```

If there are uncommitted changes (staged, unstaged or untracked files that are not ignored), list them and stop.

## 2. Read the issue

```bash
gh issue view $ARGUMENTS --json number,title,state,milestone,labels,assignees,body
```

Stop with a warning if:
- the issue is closed,
- it has no milestone,
- its body has no acceptance criteria (no "Acceptance criteria" section with at least one checkbox).

## 3. Summarize

In Spanish, briefly:
- **Context** — what and why.
- **Requirements** — the REQ-xxx it covers.
- **Done when** — the phase criterion.
- **Known pitfalls**.
- **Flags** — is it `codec-review` (label or flag)? Does it touch safety (failsafe, commands, missions,
  signing, `area:safety`) and so need `safety-reviewer`? Does it touch protocol, telemetry or commands
  and so need SITL + QGroundControl verification (`sitl-verify`)?

## 4. Assign it

```bash
gh issue edit $ARGUMENTS --add-assignee @me
```

## 5. Branch

Propose a branch name: `feature/<n>-<slug>`, or `fix/<n>-<slug>` if the issue is a bug (`bug` label).
The slug is short, lowercase, kebab-case, from the title. **Wait for the owner's OK** (they may change
the name), then:

```bash
git switch -c <branch> origin/main
git branch --unset-upstream
```

## 6. Read before planning

Read, and then say explicitly what you read:
- `CLAUDE.md` → "Lessons learned".
- `docs/requirements.md` → every REQ the issue cites (and the matrix rows).
- The ADRs in `docs/adr/` related to what the issue touches (e.g. codec → 0001, map → 0002,
  autopilot-specific behavior → 0003, domain/data boundaries → 0004; search the folder for others).
- The skill for the layer:

  | Area | Skill |
  |---|---|
  | `area:mavlink` | `android-tests` (codec tests section) |
  | `area:transport`, `area:data` | `android-feature`, `android-hilt`, `android-typed-errors` |
  | `area:ui` | `compose-ui`, `android-mvi` |
  | `area:safety` | `android-typed-errors`, `android-mvi` (dangerous commands) |
  | `area:build` | `android-modularization` |

## 7. Codec issues

If the issue is `codec-review`, remind the flow before planning:
- Test-first: **red** tests from **real** SITL captures (fixtures under
  `core/mavlink/src/test/resources/`), with expected values from a reference decoder.
- Then the implementation until the tests pass; `mavlink-reviewer` reviews it against the spec.
- Nothing is merged until the owner has reviewed it line by line and can explain every line.

## 8. Plan

Present a numbered plan. For every step say which REQ it covers, and state whether the issue will
need `safety-reviewer` and/or `sitl-verify`. Include how it will be verified and the commits you
expect (one concept each, Conventional Commits with `(#$ARGUMENTS)`).

**Wait for the owner's approval before writing any code.**
