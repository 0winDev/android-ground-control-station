---
description: Open a GCS GitHub issue in English with context, acceptance criteria, REQ-xxx, phase, "done when", pitfalls, labels and milestone. Shows the draft and waits for approval.
argument-hint: <title>
---

You are creating a GitHub issue for GCS (Ground Control Station). Title provided: `$ARGUMENTS`.

If `$ARGUMENTS` is empty, ask for a title and stop.

## Steps

1. Run in parallel:
   - `gh auth status`
   - `gh label list --limit 100`
   - `gh api repos/{owner}/{repo}/milestones --jq '.[] | "\(.title): \(.description)"'`
   - `gh issue list --label epic --state open`
2. Abort if `gh` is not authenticated.
3. Read `docs/requirements.md` to pick the REQ-xxx the issue covers. If none fits, propose a new
   requirement with the next free ID (it will be added to `docs/requirements.md` in the issue's PR).
4. Draft the body in **English**, following `.github/ISSUE_TEMPLATE/feature.yml` (or `bug.yml`):
   ```
   ## Context
   <what and why, 1–4 sentences>

   ## Acceptance criteria
   - [ ] <verifiable outcome>

   ## Requirements covered
   REQ-xxx, REQ-yyy

   ## Phase "done when" (<phase>)
   <copied from the milestone description>

   ## Known pitfalls
   - <protocol/ArduPilot/safety traps; only real ones, with spec links when possible>

   ## Flags
   - [ ] Touches the MAVLink codec (`codec-review`, test-first, line-by-line owner review)
   - [ ] Touches failsafe, commands, missions or signing (needs `safety-reviewer`)
   - [ ] Touches protocol, telemetry or commands (needs SITL + QGroundControl check)
   ```
5. Propose labels — exactly one `phase:v0.x`, one or more `area:*`, `enhancement` or `bug`,
   `codec-review` if it touches `:core:mavlink`, `out-of-scope` if it is outside the current phase —
   the milestone, and the parent epic (if any).
6. Show the draft (title, body, labels, milestone, epic) and wait for confirmation.
7. On confirm:
   ```bash
   gh issue create --title "<title>" --body-file <file> --label <l1> --label <l2> --milestone "<milestone>"
   ```
   If there is a parent epic, link it as a sub-issue:
   ```bash
   gh api graphql -f query='mutation($p:ID!,$c:ID!){addSubIssue(input:{issueId:$p,subIssueId:$c}){issue{number}}}' \
     -f p=<epic node_id> -f c=<new issue node_id>
   ```
   and add it to the epic's checklist in the agreed build order.
8. Print the issue URL.

## Rules

- Title in English, ≤ 70 chars, no prefix, no trailing period.
- No "Generated with" footer.
- Do not assign anyone unless asked. Do not invent labels.
- A `codec-review` issue states the test-first flow and the line-by-line owner review before merge.
