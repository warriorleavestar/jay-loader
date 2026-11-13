# Notes for coding agents and reviewers

Jay Loader is a universal Minecraft mod loader prototype driven by the Jay launcher. Build and layout are described in [README.md](README.md); the launcher interface is `docs/amber-bake-interface.md`.

## Coding rules

- Original code only. Don't copy code from other loaders or from decompiled binaries. Public format and protocol documentation is fine. Code derived from the Fabric API stays under its own licence, as `THIRD_PARTY_NOTICES.md` says.
- Never commit Minecraft, Forge or NeoForge jars or anything else from `mc/` or `deps/`.
- Every downloaded library is pinned by SHA-256 in `libs.lock.json`. A changed hash or a new download source needs a reason in the pull request. Downloads come only from the official sources.
- Tests and experiments use a separate test game directory. Nothing may write to a real `.minecraft`.
- No telemetry and no network access beyond fetching pinned libraries and the official game files for local testing.
- The CLI output (JSON lines) is an interface the launcher parses: keep it backward compatible and escape every value.
- Python tools run with `python -I`.

## Review guidelines

### What to check

- Correctness of remapping, Mixin and access widener handling, class loading order and the bake cache (cache keys cover every input, stale caches are rebuilt).
- Resource leaks (file handles, class loaders, threads) and behaviour on malformed mod metadata or jars.
- Compatibility of the bake interface with the current Jay launcher.
- Tests for new behaviour.

### How to write the review

- Write review comments in Simplified Chinese (the maintainers' working language). Keep code identifiers, file names and commands as they are.
- Each finding: file and line, what goes wrong, how it is triggered, and a suggested fix. Point out missing tests for new behaviour.
- Severity:
  - **Blocking (P0/P1)**: wrong behaviour users will hit, data loss, a crash, a broken build or test, a repository rule below broken, or a change that breaks compatibility without a version bump.
  - **Medium (P2)**: an edge case that fails, a resource or thread leak, a missing timeout or limit, missing tests for new behaviour, a confusing or wrong user-facing text.
  - **Low (P3)**: naming, comments, small simplifications, style.
- Don't report pure style preferences as blocking. Don't repeat a finding that an earlier review on the same lines already made unless the new commit didn't fix it.
- Never approve, merge, close or push. Only comment.

### Security findings stay out of public comments

This repository is public. Maintainers run a separate, private security review for every pull request.

- If you see a possible security vulnerability (a way to bypass a check, read or change data you shouldn't, crash or overload a service from outside, escalate privileges, or recover a secret), **don't describe it in the review**. Write one line only: `可能有安全问题，已交给维护者私下处理。` No file, line, attack steps or impact.
- Don't write about weaknesses that aren't fixed yet, limits that can be bypassed, or how a protection could be defeated, even as a side remark or a suggestion.
- Normal hardening advice that doesn't point at an exploitable gap (for example "add a unit test for the malformed input path") is fine.

### Privacy in the pull request itself

Flag as blocking anything in the diff, the commit messages or the pull request text that contains:

- a public IP address (use `example.org`, `192.0.2.x`, `198.51.100.x`, `203.0.113.x` or `2001:db8::` in tests and docs),
- an email address, a real name, a computer user name or host name, or a machine-specific absolute path,
- a key, token, password or other secret.
