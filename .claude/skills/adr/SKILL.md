---
name: adr
description: |
  Draft an Architecture Decision Record in docs/adr/ for GCS (Ground Control Station): context,
  decision, alternatives considered, consequences. Trigger on: "ADR", "architecture decision",
  "record this decision", "why did we choose", "document the decision", or when a plan changes
  module boundaries, adds a major dependency, or picks between libraries/protocols.
---

# ADR — GCS

ADRs record decisions that someone will ask "why?" about in an interview or a review. Claude drafts
them; the owner rewrites them in their own words before merging.

## When an ADR is needed

- A module boundary or dependency rule changes.
- A major dependency is added or rejected (map SDK, navigation library, persistence).
- A protocol or safety behavior is decided (timeouts, retries, failsafe interaction, signing).
- Scope is cut or deferred deliberately.

## Steps

1. List `docs/adr/` and take the next number (`NNNN`, zero-padded, never reused).
2. Gather the facts: the issue, the discussion, official docs. Every claim about a library or
   protocol is backed by a link; no invented versions or capabilities.
3. Write `docs/adr/NNNN-<kebab-title>.md` with the template below, status `Proposed`.
4. Add it to the index in `docs/adr/README.md`.
5. If it supersedes an ADR, set the old one to `Superseded by NNNN` (the only edit allowed to an
   accepted ADR).
6. Commit with `docs: add ADR NNNN <title> (#<issue>)`. The owner changes the status to `Accepted`
   when the PR is approved.

## Template

```markdown
# NNNN. <Decision as a short statement>

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD
- Issue: #<n>

## Context

What problem forces a decision, the constraints (portfolio goal, safety, offline use, Android-only,
solo developer) and what we know. Facts, with links.

## Decision

What we do, in one or two paragraphs, active voice ("We write our own MAVLink v2 codec…").

## Alternatives considered

### <Alternative A>
- Pros:
- Cons:
- Why not:

### <Alternative B>
...

## Consequences

- Positive:
- Negative / costs we accept:
- Follow-ups (issues to open, things to revisit and when):
```

## Rules

- English, concise, no marketing. "Inspired by" sector practices, never "compliant with".
- One decision per ADR.
- Accepted ADRs are immutable; change direction with a new ADR.
