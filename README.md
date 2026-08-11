# Chats — a calm Matrix (Beeper-style) messaging tool for the Light Phone

**PRIVATE (2026-08-11).** This client is not for sharing or publishing: it integrates
Beeper's private, undocumented API (the v1 login path) and is tied to the user's real
Beeper account. Keep Beeper endpoints/tokens/flows out of any public-facing docs or
repos, and treat this directory as non-publishable.

## What it is

A Matrix messaging tool in the `light-phone` workspace's two-module pattern, built with
the `light-sdk` tool plugin:

- **`:app` — the tool** (`com.lightphone.chats`, label "Chats"). A thin, quiet chat UI
  launched from the LightOS toolbox: a chat list, a thread with an LP3-keyboard
  composer, and Settings (login, connection status, E2EE device verification). All UI
  is SDK design-system primitives; the tool runtime's bans (services, notifications,
  background work) are respected.
- **`:server` — the companion** (`com.lightphone.chats.server`). A plain Android app
  that hosts the SDK's `LightSdkService` and the entire privileged Matrix stack:
  Trixnity `MatrixClient` (login/session restore/sync), the Room store, a foreground
  `ChatSyncService` sync loop, new-message notifications, E2EE (megolm + SAS device
  verification), and a background room-list cache.

**v1 scope:** WhatsApp via a Beeper account (Beeper's own bridges). Generic Matrix
homeserver login exists in the codebase as dev/test tooling only (the emulator runs
against a local Synapse). Everything else is an explicit non-goal for v1 — see
`PLAN.md`.

## Build

```bash
source tools/env.sh
tools/build --dir chats :app:assembleDebug :server:assembleDebug
```

APKs: `chats/app/build/outputs/apk/debug/app-debug.apk` and
`chats/server/build/outputs/apk/debug/server-debug.apk`. The build consumes
`../light-sdk` as an included (composite) build, so the additive Chats service methods
in `sdk/shared`/`sdk/server` are compiled in (see `PLAN.md` §2 for the SDK patch
pattern).

## Install & run (lightos emulator)

```bash
tools/emulator.sh                 # boots the AVD (writable-system)
adb root && adb remount
adb install -r chats/app/build/outputs/apk/debug/app-debug.apk
adb install -r chats/server/build/outputs/apk/debug/server-debug.apk
# open the Chats tool from the LightOS toolbox
```

The emulator's dev homeserver (local Synapse, `http://10.0.2.2:8008`) is the standard
functional test target — see `docs/SETUP.md` for the recipe. On a real LP3 the tool
needs the community-ADB sideload route + External tools → "All tools", and
`lighttool.toml`'s `serverPackage` pointed at `com.lightos` only if Light ships chat
methods natively.

## Status

- **Phase 1** scaffold — done (tool + companion + SDK `ChatPing`).
- **Phase 2** companion Matrix core (Trixnity, sync service, generic login) — done.
- **Phase 3** tool UI (3a plumbing, 3b design pass), **3c** Beeper login, **3d** E2EE
  unlock — done.
- **Phase 4** notifications & background receive — done.
- **Phase 5** hardening & emulator verification (room-list cache, reconnect/sync
  watchdog, session-expiry handling, pagination correctness, offline state, docs) —
  done.
- **Phase 6** real-device readiness — pending (needs the community-ADB route).

Details, decisions, and the phase-by-phase record live in `PLAN.md`; dev recipes in
`docs/SETUP.md`.

## Notes

- Beeper-specific code (endpoints, the API token, the `/keys/claim` interceptor) lives
  only in the companion (`MatrixRepository`), never in the tool or in docs.
- Trixnity is Apache-2.0; the Beeper login flow was adopted (MIT) from the
  `ironfeet/Beeper4LightOS` bootstrap — engine + login only, not its plugin-allowlist
  patch or sandbox escape.
