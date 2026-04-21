# Common agent rules

## 🧠 General rules

- If something is unclear or ambiguous — **ask before making changes**.

## 📌 Commands & project tasks

- Frequently used commands are usually defined in project-level automation files, such as:
  - `Makefile`, `justfile`, or similar task runners;
  - `package.json` scripts or equivalent package manager configs;
  - build system configuration files;
  - CI configuration files.
- Always prefer **existing project commands** over ad-hoc or custom executions.
- If no command exists, ask before introducing a new one.

## 📦 Dependencies & external libraries

- When adding or updating dependencies:
  - **Always check the internet for the latest stable version**.
  - **Read the official documentation for that version**, not assumptions based on model memory.
- Assume that:
  - language ecosystems evolve quickly;
  - APIs, defaults, and best practices change over time;
  - model knowledge may be outdated.
- Prefer:
  - actively maintained libraries;
  - official or well-established ecosystem tools.
- Verify:
  - compatibility with the current project stack;
  - license compatibility;
  - security implications.
- Do not introduce new dependencies if existing project tools can solve the problem.

## 📌 Testing & verification

- Always use the **appropriate commands, scripts, or tasks for the project's stack** to:
  - run tests;
  - execute linters / formatters / static analysis;
  - validate build artifacts or configuration.
- Tests must:
  - follow conventions of the current language/framework;
  - be placed and named according to ecosystem standards.

## 🗄️ Databases

- Treat database-related changes with extra caution.
- Before modifying schemas, migrations, or queries:
  - inspect existing migrations and conventions;
  - understand the current data model and constraints;
  - verify backward compatibility where applicable.
- Prefer **migrations over manual changes**.
- Ensure all database changes are:
  - reversible when possible;
  - compatible with existing data;
  - tested using the project’s database tooling.
- Use database tooling **appropriate to the stack**:
  - migration frameworks;
  - schema management tools;
  - local or containerized database setups.
- Never hardcode credentials or sensitive connection details.

## 📁 Git workflow

- Always check the current repository state (status, diff, etc.) before modifying anything.
- **Never stage or commit** unless explicitly instructed.
- Follow the repository's existing Git conventions (branching, commit messages, etc.).

## 📚 Documentation

- For Markdown files:
  - prefer a **maximum line length of ~120 characters**, except for:
    - long tables;
    - long links;
    - code blocks.
  - headers must use sentence case (only the first letter capitalized)
    unless explicitly requested otherwise by the user.
    - Correct example: `## Project rules and conventions`.
    - Incorrect example: `## Project Rules And Conventions`.
  - always put a blank line after headers (as in this file).
  - new documents should be named using **`UPPERCASE_WITH_UNDERSCORES.md`**.
- Before making any changes to the project, check whether any existing
  documentation can help you better understand the context.
- Any meaningful change must be reflected in documentation:
  - `README.md`;
  - inline comments or additional docs when applicable.
- Use only standard ASCII characters instead of styled or typographic characters,
  except when the user explicitly includes styled characters in their input.<br>
  Examples:
  - `'` instead of `’`
  - `"` instead of `“`

## 🔐 Security

- **Never log or expose sensitive data.**
- For security-sensitive projects, always apply **best-practice security standards appropriate to the stack**.
- Prefer secure defaults over convenience.

## 🧾 Logging & error handling

- Use logging facilities appropriate to the current stack.
- Prefer structured logging when supported.
- Error handling must follow best practices of the language or framework (wrapping, propagation, contextual
  metadata, etc.).

## 🧹 Code style & conventions

- Follow the **existing code style and conventions defined in the repository**, including:
  - formatter and linter configurations;
  - naming conventions;
  - directory and module structure.
- Prefer **code reuse over duplication**:
  - first, check whether existing functions, modules, or utilities can be reused;
  - when duplication appears, extract shared logic to an appropriate common place;
  - avoid forced abstraction: do not move or extract code if it adds more complexity than it removes.
- Ensure all quality checks pass after modifying anything.

---

This file defines **stack-agnostic rules for AI agents and contributors**.
All concrete tools, commands, and files must be selected **according to the project's technology stack
and conventions**.

# Karpathy-inspired agent guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think before coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity first

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: every changed line should trace directly to the user's request.

## 4. Goal-driven execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication,
and clarifying questions come before implementation rather than after mistakes.
