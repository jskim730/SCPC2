# SCPC2 Workspace Instructions

## Required context

Before planning, implementing, testing, documenting, or packaging this project, read
`COMPETITION_CONTEXT.md`.

For technical details, also read the relevant official source under `release_v3/`. Treat
`release_v3/` as a read-only reference Kit; do not use it as the app implementation directory and do not
leave generated caches or build outputs inside it.

## Source-of-truth policy

- Use `COMPETITION_CONTEXT.md` as the default local snapshot for stable competition facts.
- Use Dacon's current competition pages and talk answers when the task concerns a new announcement, changed deadline,
  upload path, organizer clarification, or other current operational state.
- Dacon's newer official notice overrides the local snapshot. Update the context document and its change log when that
  happens.
- Use KST (`Asia/Seoul`) for every deadline.
- If web boilerplate conflicts with the competition-specific Android/Probe contract, flag the conflict and seek an
  organizer clarification when it can affect eligibility or submission.

## Non-negotiable project rules

- Do not begin product implementation until the Mission, E1-E4 causal chain, Primary value, Signature mechanism,
  comparison baseline, and Probe role/operation mapping have been explicitly approved.
- Do not create or edit `MISSION_LOCK.json`; Dacon generates it from the submitted Mission PDF.
- Do not hand-author SHA-256 values, release IDs, certificate digests, runtime identity, or export indexes. Use the
  official Kit tools.
- Keep product UI, public Probe UI, and protected Probe execution connected to the same production core.
- Never put PASS/FAIL, expected relations, anchors, Q scores, or cut estimates in Probe results.
- Use only synthetic data and app-local simulated actions; do not cause real purchases, payments, reservations,
  messages, calls, posts, account changes, or other external effects.
- Preserve current-authority, deletion/no-resurrection, restart reconciliation, idempotency, evidence integrity, and
  full/claim-off state isolation as first-class acceptance criteria.
