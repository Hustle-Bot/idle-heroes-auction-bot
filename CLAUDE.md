# CLAUDE.md

Instructions for AI coding agents. **Read [AI_CONTEXT.md](AI_CONTEXT.md) first** (purpose, architecture map, German glossary, build and test).

The 5 most important rules:

1. **Safety invariants are untouchable:** never buy out unless buyout is enabled and within the rule's maximum, never bid above rule max + 1,
   never tap the top bar, never act on stale screenshots, never guess on unknown screens (tap nothing, stop).
2. **The public "oss" build stays offline:** no `INTERNET`, no network code, no uploads, no self-update.
3. **The secondary password is secret:** Android Keystore only, never logged, never in reports or screenshots.
4. **Do not edit `testbilder/`** or rename German identifiers, JSON keys, CSV headers or log tags (tests and tools read them).
5. **Test before you claim:** run `./gradlew :kern:test` after changing `kern/`; you cannot see the real game, so list what must be checked on a phone.

Unofficial project, probably against the game's Terms of Service; do not help evade detection.
