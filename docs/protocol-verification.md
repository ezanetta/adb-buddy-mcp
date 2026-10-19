# Protocol verification

Verified on 2026-10-19 with `kotlin-sdk-server` 0.15.0, MCP Inspector CLI 2.11.0, macOS, JDK 17
(build) and JDK 24 (run), against an Android 17 (API 37) emulator.

Re-run with:

```bash
./gradlew shadowJar
scripts/verify-inspector.sh            # protocol checks, no device needed
scripts/verify-inspector.sh --device   # plus tools/call against a connected device
```

## Protocol eras

MCP has two eras. Up to `2025-11-25`, clients open a session with an `initialize` handshake.
The `2026-07-28` spec makes requests stateless and negotiates per request through
`server/discover`. The stable Kotlin SDK (0.15.0) implements only the handshake era; stateless
support is in progress upstream ([kotlin-sdk#985](https://github.com/modelcontextprotocol/kotlin-sdk/pull/985)).

| Inspector `--protocol-era` | What happens | Result |
| --- | --- | --- |
| `legacy` | `initialize` handshake | ✅ 14 tools, 1 prompt |
| `auto` | tries `server/discover`, falls back to `initialize` | ✅ 14 tools, 1 prompt |
| `modern` | pins `2026-07-28`, no fallback | ❌ expected: *"the server did not offer pinned protocol version 2026-07-28 via server/discover"* |

`tools/list --strict` (schema portability checks) passes.

## Handshake versions

Raw JSON-RPC over stdio (`initialize`, `notifications/initialized`, `tools/list`):

| Client requests | Server negotiates | `tools/list` |
| --- | --- | --- |
| `2024-11-05` | `2024-11-05` | 14 tools |
| `2025-03-26` | `2025-03-26` | 14 tools |
| `2025-06-18` | `2025-06-18` | 14 tools |
| `2025-11-25` | `2025-11-25` | 14 tools |
| `2026-07-28` | `2025-11-25` (downgraded, as the spec allows) | 14 tools |

Tool annotations were introduced in `2025-03-26`; older clients ignore the field.

## tools/call against the emulator

| Call | Result |
| --- | --- |
| `list_devices` | `emulator-5554 \| Google sdk_gphone16k_arm64 \| Android 17 (API 37) \| emulator` |
| `get_current_activity` | `package com.android.settings, activity …SettingsHomepageActivity` |
| `dump_ui_hierarchy` | Settings home: 12 elements (from 29 KB of XML) |
| `take_screenshot` | 570x1280 PNG, ~94 KB (device 1080x2424, 1.4 MB) |
| `tap` x=99999 | `isError: true`, "outside the screen (1080x2424 …)" |
| `input_text` with `'"$();&\|<>*\` and backticks | typed literally into the Settings search field |
| `install_apk` | reinstalled a debug (test-only) APK with `-r -t` |

## stdio hygiene

- stdout carries only JSON-RPC. `System.out` is redirected to stderr at startup, so a stray
  `println` from any library cannot corrupt the stream.
- The process exits when stdin closes, including when the client disconnects during setup.
