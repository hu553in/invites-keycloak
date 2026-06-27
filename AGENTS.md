# Common agent rules

These rules are stack-agnostic. Project-local instructions, tooling, and conventions take
precedence.

## General rules

- Read the relevant project documentation before changing files.
- Ask only when a requirement is ambiguous and a reasonable assumption would be risky.
- Keep changes focused on the requested task.
- Prefer the repository's existing patterns over new abstractions or custom workflows.

## Commands and project tasks

- Prefer existing project commands from files such as `Makefile`, `justfile`, `package.json`, build
  configs, or CI configs.
- Ask before adding a new command or task runner entry when the project does not already provide
  one.
- Use stack-appropriate tools for tests, linting, formatting, static analysis, and config
  validation.

## Testing and verification

- Run the relevant checks after meaningful changes when feasible.
- Place tests according to the project's language, framework, and directory conventions.
- Report any check that could not be run.

## Dependencies

- When adding or updating dependencies, check the current stable version and official docs.
- Prefer actively maintained libraries and standard ecosystem tools.
- Verify compatibility, license fit, and security impact.
- Do not add a dependency when existing project tools solve the problem cleanly.

## Git workflow

- Check repository status before editing.
- Never stage, commit, push, or rewrite history unless explicitly instructed.
- Preserve unrelated user changes, including staged changes.

## Documentation

- Use sentence-case headings unless the user or upstream standard requires otherwise.
- Put a blank line after every heading.
- Name new documentation files as `UPPERCASE_WITH_UNDERSCORES.md`.
- Use ASCII punctuation unless the file already intentionally uses non-ASCII text.
- Update documentation when a meaningful behavior, command, or setup detail changes.

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

## Logging and errors

- Use the project's existing logging and error-handling patterns.
- Include useful context in errors without exposing secrets.

## Code style

- Follow the repository's formatter, linter, naming, module, and directory conventions.
- Reuse existing helpers and patterns before adding new ones.
- Avoid speculative features, premature abstractions, and unrelated refactors.
- Remove only the dead code or unused wiring introduced by your own change unless asked otherwise.
