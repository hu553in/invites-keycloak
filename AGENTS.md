# Project rules

## General

- Please ask questions before making any changes if you have any doubts about anything.

## Testing and checking

- Always use the `make test` command for testing.
- Always use the `make check` command for full checking (testing, linting, etc.)

## git

- Always check staged and unstaged changes before doing any work to have a clear context.
- Don't stage or unstage any changes, and don't do any commits until explicitly asked.

## DB

- Always consider performance and complexity, and prefer using existing indexes or creating new ones.

## Docs

- In any Markdown file, keep the max line length to 120 (excluding tables, long links or code blocks, etc.)
- After making any changes to the project, ensure the existing docs are updated:
    - `AGENTS.md`
    - `README.md`
    - code comments

## Security

- Never log any sensitive data.
- This project is related to security, so implement all things with the highest level of security in mind.

## Code conventions

- Follow defined code style rules (see `config/detekt.yml` and `.editorconfig`).
- Verify everything via `make check`.
