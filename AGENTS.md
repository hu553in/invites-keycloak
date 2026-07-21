# Common agent rules

These rules apply to any stack. Project-local instructions, tooling, and conventions win.

## General rules

- Read the relevant project docs before changing files.
- Ask whenever real doubt remains between ways to implement something, or an obstacle blocks
  progress.
- Keep changes focused on the requested task.
- Prefer the repository's existing patterns over custom workflows.
- Before editing any file, read it first. Before modifying a function, grep for all callers.
- When the user asks a question or thinks out loud, answer it; do not start implementing until
  asked.
- When a fix that should have worked changes nothing, question your model of the system instead of
  retrying variations of the same fix.
- Revert changes made for a theory that proved wrong; the final diff must contain only what the
  actual fix needed.
- Prefer designs that make the bug impossible over designs that compensate for it with computation.
- Match what you deliver to the request: one artifact asked for, one delivered, without companion
  files nobody requested; checks and doc updates these rules require are not companions.

## Solution sizing

- Before writing a solution, climb this ladder and stop at the first rung that holds: the need is
  speculative, so skip it (say so in one line); already exists in this codebase; covered by the
  standard library; covered by a native platform feature (a database constraint, a built-in control,
  plain styling); covered by an already-installed dependency; covered by a new well-maintained
  dependency whose adoption cost fits the destination (weight matters in artifacts shipped to end
  users, far less in code running on your own infrastructure); expressible as a line or two; only
  then the minimum new code that works.
- The ladder shortens the solution, never the reading: trace the real flow end to end before picking
  a rung. A tiny change in the wrong place is a second bug, not efficiency.
- Fix bugs at the root cause: a report names a symptom; find all callers and fix once in the shared
  path instead of patching only the path the report mentions.
- No unrequested abstractions: no interface with one implementation, no factory for one product, no
  config knob for a value that never changes, no scaffolding "for later".
- Prefer deletion over addition and boring over clever; clever is what someone has to decode during
  an incident.
- Between two equally simple options, take the one that is correct on edge cases; simple means less
  code, not a flimsier algorithm.
- Mark a deliberate simplification that has a known ceiling with a comment naming the ceiling and
  the upgrade path.
- Never simplify away input validation at trust boundaries, error handling that prevents data loss,
  security measures, accessibility basics, or anything explicitly requested.
- Non-trivial new logic leaves behind one minimal runnable check that fails if the logic breaks;
  trivial one-liners need none.

## Commands and project tasks

- Prefer existing project commands from files such as `Makefile`, `package.json`, build configs, or
  CI configs.
- Ask before adding a new command or task runner entry when the project lacks one.
- Use stack-appropriate tools for tests, linting, formatting, static analysis, and config
  validation.
- When the project defines one command that runs all its checks with autofixes, prefer it over
  running individual tools and fixing their output by hand; let the project's own tooling apply the
  fixes it can.
- Every generated artifact must be reproducible by a checked-in script; if you repeated a manual
  action, propose scripting it. External side effects (uploads, publishing) count as steps to
  script.
- Keep producing an artifact and publishing it as separate steps: builds are cheap and repeatable,
  publishing is a decision.

## Repository contents

- Track only sources and files the project actually uses; ignore regenerable intermediates and build
  outputs instead of committing them.
- Keep intermediates out of published or output directories; anything placed there ships. Generate
  temporary files in a temp directory and clean them up even on failure.
- Keep personal data out of reusable projects; example configs use neutral placeholder values.

## Testing and verification

- Run the relevant checks after meaningful changes when feasible.
- Place tests according to the project's language, framework, and directory conventions.
- Report any check you could not run.
- Verify behavior on the real artifact: run it, render it, or measure it. Types and linters passing
  is not verification.
- Prove every new mode, flag, or option by running it at least once before handoff.
- Place boundary tests where the signal exists; a negative test on an empty case proves nothing.
- In negative tests, make the absence explicit; environments auto-inject config (env files,
  defaults), which turns "without X" tests falsely green.
- Smoke tests must assert expected content in produced artifacts, not only that they parse.
- Do not write a test whose only assertion is that a deleted symbol, route, or string stays absent
  (e.g. `.not.toContain('my-2')`); it only fails if someone un-deletes it, so it verifies nothing
  about current behavior.
- Do not write a test solely to move a coverage number; executing a line is not the same as
  verifying it.
- When changing algorithmic code (hashing, randomness, arithmetic), verify equivalence against a
  reference implementation, not by eyeballing output.
- Measure the artifact that ships, not its local twin; "computed here, applied there" drifts.
- When observed behavior contradicts the docs, settle it with a direct experiment and trust the
  experiment.
- When a metric does not respond to a change that should clearly move it, stop and check what the
  metric actually measures; averages often hide the moment that matters.

## Reviews

- Review in passes until a full pass finds nothing; report an empty pass honestly instead of
  manufacturing findings.
- Verify cross-file invariants held together only by comments or convention ("keep in sync
  with..."); no tool checks them.
- Internal consistency review cannot catch staleness: compare pinned versions of dependencies,
  actions, and tools against current upstream releases as a separate step.
- After renames and refactors, grep the whole repository for the old names, including docs, configs,
  CI, and error messages.
- Check what should exist but does not: license, ignore entries, docs for every option.

## Dependencies

- When adding or updating dependencies, check the current stable version and official docs.
- Prefer actively maintained libraries and standard ecosystem tools.
- Verify compatibility, license fit, and security impact.
- Replace custom code with a library or a ready-made tool whenever that reduces total complexity;
  prefer tools already present in the dependency tree.
- Judge a dependency by the obligations it adds (maintenance, majors, vulnerabilities), not by lines
  saved; one whose whole value is a line or two rarely pays for itself, and unmaintained wrappers
  never do.
- Custom code that exists because a library failed is legitimate; record why next to it.

## Git workflow

- Check repository status before editing.
- Never stage, commit, push, or rewrite history unless explicitly instructed.
- Preserve unrelated user changes, including staged changes.
- Make every change as a plain working-tree edit. To rename a tracked file, move it on disk and let
  the user restage. To untrack a file, edit the ignore file and tell the user which command to run.
- The user may stage or unstage changes at any moment while you work, including ones you did not
  make; index drift you did not cause is expected, not corruption. Do not investigate, revert, or
  otherwise touch the index in response, and do not treat it as an incident to fix.

## Documentation

- Use sentence-case headings unless the user or upstream standard requires otherwise.
- Put a blank line after every heading.
- Name new doc files as `UPPERCASE_WITH_UNDERSCORES.md`.
- Use ASCII punctuation unless the file already intentionally uses non-ASCII text.
- Update docs when a meaningful behavior, command, or setup detail changes.
- Re-check related docs after every structural change; a new command or option silently makes older
  prose stale.
- Treat examples in docs as code: validate that they parse and run, and keep shown defaults
  identical to the implementation.
- Remove sentences that duplicate an adjacent code block, table, or comment.
- Cut filler: every sentence must add a fact the reader cannot get from the nearby content.
- Write prose (docs, PR text, commit messages, replies) plainly: a short word over a long one,
  active voice over passive, an everyday term over jargon or a stock phrase, and no word that can be
  cut without losing meaning. This governs prose only; code and technical terms stay exact, and
  everyday wording substitutes only where precision survives.
- Break any prose rule sooner than write something awkward; check prose output against these rules
  before delivering.

## Configuration and inputs

- Validate external input with bounds and formats, not policy; guard against nonsense (size limits,
  count limits), then get out of the way.
- Give every option a sane default so the minimal config works; personal or stylistic behavior is
  opt-in, defaults follow least surprise for a third-party user.
- Reject known foot-guns with messages that point to the correct option.
- When the tool derives part of a command, path, or query from structured config, reject raw config
  that supplies the same part free-form.
- Escape and sanitize per destination context; the same string needs different escaping in different
  sinks. Output file names from config must not contain path separators.
- Treat external tool output defensively: validate content, not only exit codes; some tools print
  errors to stdout and exit zero.
- Fetch or compute only what the config in use needs; unused features must cost nothing at runtime.
- Error messages name the file, field, and the fix.

## CI and automation

- Add concurrency guards and timeouts to workflows; scheduled and push-triggered runs must not
  overlap.
- Pin toolchain and action versions; use an update bot (Dependabot, Renovate) so automation catches
  staleness instead of review luck.
- When the project is itself a tool or action, run it on itself in CI; dogfooding is the cheapest
  end-to-end test.

## Databases

- Treat schema, migration, and query changes as high risk.
- Inspect existing migrations, constraints, and data-model conventions before editing.
- Prefer migrations over manual changes.
- Keep changes compatible with existing data and verify them with the project's database tooling.
- Never hardcode credentials or sensitive connection details.

## Security

- Never log, commit, or expose secrets.
- Prefer secure defaults.
- Use stack-appropriate security practices for authentication, authorization, input handling, and
  dependency management.
- Put local secrets into ignored env files from the start, and never echo their values in command
  output.
- If a secret appears in chat, logs, or command history, say so immediately and recommend rotation.

## Logging and errors

- Use the project's existing logging and error-handling patterns.
- Include useful context in errors without exposing secrets.
- Choose deliberately per failure path between failing loudly and degrading quietly, and note the
  choice; a silent zero that hides an outage is worse than a crash.

## Code style

- Follow the repository's formatter, linter, naming, module, and directory conventions.
- Avoid unrelated refactors; the solution sizing ladder covers reuse and unneeded abstractions.
- Remove only the dead code or unused wiring introduced by your own change unless asked otherwise.
- After a refactor, rename identifiers whose names no longer tell the truth; a stale name lies more
  convincingly than a stale comment.
- Suppress a lint rule only at the exact spot and with a written reason; prefer a real fix wherever
  the rule is right. Suppressions must leave automated fixers idempotent: re-running the fixer
  produces no changes. After any automated unsafe fix, treat touched bit-twiddling, hashing, and
  randomness code as broken until verified.
- Enforce cross-cutting output invariants (casing, escaping, formatting) at the single output
  boundary, not by fixing every data source; sources are many and undisciplined, the boundary is
  one.
- Prefer deterministic generation: take time and randomness as explicit inputs (parameters, seeds),
  so identical inputs reproduce identical outputs.

## Comments

- Write a comment only for a non-obvious constraint, tradeoff, or gotcha (the why); delete narration
  of what the code does and notes addressed to the reviewer.
- Every fact in a comment is a commitment to keep it true; update or delete comments in the same
  change that alters the behavior they describe.
- Keep each comment directly above the single item it describes; sorted or reordered code silently
  orphans group comments.
- When a value looks arbitrary (a magic number, a pinned version, an unusual flag), the comment
  states the constraint that produced it; otherwise the next review "fixes" it back.
