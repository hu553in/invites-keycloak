# Project rules

## General

- Please ask questions before doing any changes if you have any doubts about anything.

## Testing and checking

- Always use `make test` command for testing.
- Always use `make check` command for full checking (testing, linting, etc.)

## git

- Always check staged and unstaged changes before doing any work to have a clear context.
- Don't stage/unstage any changes and don't do any commits until explicitly asked.

## DB

- Always consider performance and complexity and try to use existing or create new indexes.

## Docs

- In any Markdown file please consider the max line length equal 120 (excluding tables, long links
  or code blocks, etc.)
- After doing any changes in project, check that any existing docs must be actualized:
    - `AGENTS.md`
    - `README.md`
    - code comments

## Security

- Never log any sensitive data.
- This project is related to security, so implement all things with the highest level of security in mind.

## Code conventions

- Follow defined code style rules (see `config/detekt.yml` and `.editorconfig`).
- Verify everything via `make check`.
