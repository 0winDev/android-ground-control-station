---
description: Stage-aware commit for GCS. Inspects staged changes (or offers to stage), writes a Conventional Commits message in English with the issue number from the branch, and asks before committing. Refuses to commit on main.
argument-hint: [optional type or hint]
---

You are creating a git commit in GCS (Ground Control Station). Optional hint: `$ARGUMENTS`.

## Steps

1. Run in parallel:
   - `git status --short --branch`
   - `git diff --staged`
   - `git log --oneline -15`
2. **If the current branch is `main`, stop.** `main` is protected; work happens on
   `feature/<issue>-<slug>` or `fix/<issue>-<slug>` branched from an up-to-date `origin/main`.
   Offer to create the branch.
3. Take the issue number from the branch name (`feature/12-heartbeat-parser` → `#12`). If the branch
   has no number, ask for it.
4. If nothing is staged but there are changes, list them and ask what to stage. Never `git add .` /
   `git add -A` blindly.
5. Never stage secrets: `local.properties`, `.env`, `*.jks`, `*.keystore`, `*.mavkey`, signing keys,
   anything that looks like a key or token. gitleaks runs in the hook, but don't rely on it.
6. **Never commit code in `core/mavlink/src/main/`** — it is hand-written by the owner. If it is
   staged, stop and tell the user (they commit it themselves).
7. Write the message:
   ```
   <type>: <imperative summary> (#<issue>)
   ```
   - `type` is exactly one of: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, `chore`.
   - English, lowercase start, imperative, subject ≤ 72 characters, no trailing period.
   - Body only when the *why* is not obvious. No "Generated with"/co-author footers unless the user
     asks.
8. If the staged diff mixes concepts, propose splitting it (one concept per commit).
9. Show the message and wait for confirmation, then commit. The hooks run ktlintFormat/ktlintCheck,
   detekt, verifyModuleGraph, gitleaks and the commit-msg checks. If ktlintFormat rewrites files,
   review and re-stage them, then commit again. If a hook fails, fix the cause; never
   `--no-verify`, never `--amend` to get around a hook.

## Rules

- Do not commit during a merge conflict or rebase.
- Do not push. Do not amend pushed commits.
