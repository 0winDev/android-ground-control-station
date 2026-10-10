---
name: new-phase
description: |
  Plan a GCS (Ground Control Station) roadmap phase on GitHub: draft the epic and its child
  issues from the roadmap, ordered by risk (protocol and safety → data → UI), with REQ-xxx,
  the phase "done when", labels and known pitfalls; create them only after explicit approval.
  Trigger on: "new phase", "plan v0.x", "create the issues for v0.x", "epic for the next phase",
  "what are the issues of v0.x".
argument-hint: <phase, e.g. v0.1>
---

# New phase — GCS

Turns one roadmap phase into a GitHub epic plus ordered child issues. **Nothing is created on
GitHub until the owner approves the full draft.**

## Inputs

- Phase (e.g. `v0.1`). If missing, ask.
- Roadmap and "done when": README status section and the milestone description
  (`gh api repos/{owner}/{repo}/milestones --jq '.[] | "\(.title): \(.description)"'`).
- Requirements: `docs/requirements.md` (existing REQs for the phase; propose new ones if missing).
- `CLAUDE.md` lessons learned and open issues (`gh issue list --state open`).

## Steps

1. **Check the previous phase.** If its epic still has open children, say so: the new phase starts
   anyway only if the owner confirms.
2. **Break the phase into issues** that each fit one PR (one concept). For each issue draft:
   - Title (English, ≤ 70 chars, no prefix, no trailing period).
   - Body following `.github/ISSUE_TEMPLATE/feature.yml`: Context, Acceptance criteria,
     Requirements covered, Roadmap phase, Phase "done when", Known pitfalls, Flags.
   - Labels: `phase:<phase>`, `area:*`, `enhancement`, and `codec-review` when the issue touches
     `:core:mavlink` (framing, CRC, parsing, signing).
   - Milestone: the phase milestone.
3. **Order by risk**, not by proposal order: protocol and safety → transport/data → domain rules →
   UI. Within that, unblock dependencies first. Explain the order in one line per issue.
4. **Requirements**: every issue cites ≥ 1 REQ. Missing REQs are proposed with the next free IDs and
   added to `docs/requirements.md` in the first PR of the phase.
5. **Pitfalls**: include known MAVLink/ArduPilot traps relevant to each issue (and only real ones,
   with a link to the spec when possible): e.g. field order by size on the wire vs XML order,
   CRC_EXTRA per message, MAVLink 2 payload truncation of trailing zeros, extension fields,
   `sysid`/`compid` of the GCS, units (`degE7`, cm vs m, cdeg).
6. **Show the full draft** (epic + children, in order) and wait for an explicit OK. Apply changes and
   show it again if asked.
7. **On approval**, create:
   ```bash
   gh issue create --title "Epic: <phase> <name>" --label epic --label phase:<phase> --milestone "<milestone>" --body-file epic.md
   gh issue create --title "<child>" --label ... --milestone "<milestone>" --body-file child-N.md
   ```
   Then link each child as a native sub-issue of the epic:
   ```bash
   gh api graphql -f query='mutation($p:ID!,$c:ID!){addSubIssue(input:{issueId:$p,subIssueId:$c}){issue{number}}}' \
     -f p=<epic node_id> -f c=<child node_id>
   ```
   (node IDs: `gh api repos/{owner}/{repo}/issues/<n> --jq .node_id`). Finally edit the epic body so
   its checklist lists the children in build order with their numbers.
8. Print the epic URL and the ordered list of child issues.

## Epic body

```markdown
## Goal
<one paragraph from the roadmap>

## Done when
<phase done-when criterion>

## Children (in build order)
- [ ] #<n> <title> — <why this position>

## Out of scope for this phase
<anything explicitly deferred, linked to out-of-scope issues>
```

## Rules

- English, no "Generated with" footer.
- `codec-review` issues say "test-first (red tests from SITL captures), then the implementation,
  review by `mavlink-reviewer`, line-by-line review by the owner before merge".
- Don't pull in dependencies of later phases (MapLibre before v0.3, Room before v0.5, …).
