---
name: commit-hygiene
description: Use this skill whenever committing code in the Event Ledger repo (or any repo where commit history is reviewed). Apply it on every commit — when staging changes, writing commit messages, or whenever the user mentions commits, git history, "commit as", or squashing.
---

# Commit Hygiene

The Event Ledger reviewers read the git log to judge how the work was done. The
history itself is a graded artifact. Follow these rules on every commit.

## Rules

1. **One logical change per commit.** Scaffolding, a feature, its tests, and docs are
   separate commits. Do not bundle unrelated changes.
2. **Conventional Commits.** `type(scope): summary` in the imperative mood, ~50-char
   subject. Types: `feat`, `fix`, `test`, `chore`, `docs`, `refactor`. Scopes used in
   this repo: `gateway`, `account`, `resiliency`, `observability`, `ops`.
3. **Never squash, never force-push, never amend already-shown commits.** The reviewer
   wants to see the real progression — including a small fixup commit if you got
   something wrong. A suspiciously clean single-commit history reads as AI-generated.
4. **Commit only working increments.** Each commit should build; tests that exist
   should pass. If a step is large, it's fine for tests to land in the very next
   `test:` commit, but never commit code that fails to compile.
5. **Use the message the playbook specifies.** When a prompt says
   `Commit as: feat(account): ...`, use that subject verbatim.

## Before each commit

- `git status` / `git diff --staged` — confirm only the intended files are staged.
- Stage deliberately (`git add <paths>`), not `git add -A`, so stray files don't slip in.
- Subject line answers "what changed"; add a short body only if the *why* isn't obvious.

Configure git identity if it isn't set, and make real, separate commits — do not
generate the history retroactively in one shot.
