# adb-buddy

[![Build](https://github.com/ezanetta/adb-buddy-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/ezanetta/adb-buddy-mcp/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**An MCP server that lets Claude (or any MCP client) see, debug and drive your Android devices and emulators through adb.**

Ask *"why did my app just crash?"* and the assistant reads the stack trace, looks at the screen and
explains the cause, without you copying logs around. Written in Kotlin on the official
[MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk), shipped as a single JAR.

![Claude Code running debug_crash: it names the exception, the exact line, the probable cause and a fix](docs/demo/debug-crash.png)

*The `debug_crash` prompt on a real crash in a sample app: exception, exact line, probable cause and a
suggested fix, found with adb-buddy's tools and without changing anything on the device.*

## Quick start

1. Download `adb-buddy-<version>.jar` from [Releases](https://github.com/ezanetta/adb-buddy-mcp/releases)
   (needs Java 17+ and Android SDK platform-tools).
2. Add it to Claude Code:
   `claude mcp add -s user adb-buddy -- java -jar /path/to/adb-buddy-<version>.jar`
3. Start an emulator or plug in a device, then ask Claude: *"Which devices are connected?"*

## Features

| Tool | What it does | Kind |
| --- | --- | --- |
| `list_devices` | Serial, model, Android version, emulator or physical | read only |
| `get_logcat` | Recent logs filtered by tag, minimum level and/or app package | read only |
| `get_crash_log` | Latest Java crash, native crash (tombstone) or ANR for a package | read only |
| `take_screenshot` | Screen as an MCP image, downscaled to save tokens, with the scale factor for taps | read only |
| `dump_ui_hierarchy` | Visible elements as compact lines: text, id, state, tap coordinates | read only |
| `list_packages` | Installed third-party apps, optional filter | read only |
| `get_current_activity` | Foreground package and activity | read only |
| `launch_app` | Start an app through its launcher activity | action |
| `tap` / `swipe` | Touch input, validated against the current screen size | action |
| `input_text` | Type into the focused field, safely escaped | action |
| `press_key` | `BACK`, `HOME`, `ENTER`, `APP_SWITCH`, `VOLUME_UP`, `VOLUME_DOWN` | action |
| `install_apk` | Install or update from a local `.apk` (debug builds allowed) | destructive |
| `clear_app_data` | Wipe an app's data (`pm clear`) | destructive |

Every tool except `list_devices` takes an optional `serial`; it is only needed when more than one
device is connected. Tools carry MCP annotations (`readOnlyHint`, `destructiveHint`) that clients
can use to decide which calls need confirmation.

**Prompt:** `debug_crash(package)` walks the model through crash log → screenshot → UI hierarchy and
asks for the probable cause and a fix.

A UI dump of the Android Settings home screen, as the model sees it (29 KB of XML in, 12 elements out):

```text
Screen 1080x2424, rotation 0, app com.android.settings. 12 elements; (x,y) is the center to pass to tap.
[1] LinearLayout "Search Settings" id=search_action_bar clickable (540,257)
[2] ScrollView id=main_content_scrollable_container scrollable (540,1367)
[3] LinearLayout "Google | Services & preferences" clickable (540,485)
[4] LinearLayout "Network & internet | Mobile, Wi‑Fi, hotspot" clickable (540,719)
...
```

## Requirements

- JDK 17 or newer to run the JAR
- Android SDK platform-tools (`adb`). adb-buddy looks in `$ANDROID_HOME/platform-tools`, then
  `$ANDROID_SDK_ROOT/platform-tools`, then `PATH`.
- A device with USB debugging enabled, or a running emulator

## Build

```bash
./gradlew shadowJar          # → build/libs/adb-buddy.jar
./gradlew check              # unit tests + ktlint
```

The Gradle toolchain downloads JDK 17 if you don't have it. Prebuilt JARs are attached to every
[CI run](https://github.com/ezanetta/adb-buddy-mcp/actions/workflows/build.yml) as the `adb-buddy-jar` artifact.

## Setup

### Claude Code

```bash
claude mcp add adb-buddy -- java -jar /absolute/path/to/adb-buddy.jar
```

If `adb` is not on the `PATH` Claude Code sees, pass the SDK location:

```bash
claude mcp add adb-buddy -e ANDROID_HOME=$HOME/Library/Android/sdk -- java -jar /absolute/path/to/adb-buddy.jar
```

### Claude Desktop

Add to `claude_desktop_config.json` (macOS: `~/Library/Application Support/Claude/`,
Windows: `%APPDATA%\Claude\`), then restart Claude Desktop:

```json
{
  "mcpServers": {
    "adb-buddy": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/adb-buddy.jar"],
      "env": {
        "ANDROID_HOME": "/Users/you/Library/Android/sdk"
      }
    }
  }
}
```

Desktop apps don't inherit your shell's `PATH`, so set `ANDROID_HOME` (and use a full path to
`java` if it isn't found).

## Example prompts

- "Which devices are connected?"
- "My app `com.example.shop` just crashed. What happened?" (or run the `debug_crash` prompt)
- "Show me the warnings from OkHttp in the last 200 log lines."
- "Open Settings, go to Display and turn on dark theme."
- "Log in with our QA test account (qa@example.com / test-password) and tell me what the home screen shows."
- "Install `app/build/outputs/apk/debug/app-debug.apk`, launch it and take a screenshot."

## Safety

adb can do anything to a device, so adb-buddy deliberately exposes a narrow surface:

- **No generic shell tool.** Each tool runs a fixed adb command; the model only fills in validated values.
- **No host shell.** adb is started with `ProcessBuilder` argument lists, never a command string.
- **Device shell quoting.** `adb shell` joins its arguments into a string that the *device's* `sh`
  parses, so every argument is single-quoted. Typing `"; reboot` into a text field types exactly that.
- **Validation before execution.** Package names are checked against a strict pattern, keys against an
  allowlist, tap and swipe coordinates against the current screen size (rotation and `wm size`
  overrides included), APK paths for existence, extension and zip signature.
- **Bounded work.** Every adb command has a timeout and a cap on the bytes it reads. Text sent to the
  model is limited to 50,000 characters, and the result says when it was truncated.
- **Errors are results.** Problems come back as `isError` tool results with an actionable message
  (e.g. listing the connected devices when `serial` is ambiguous), never as stack traces.
- **Untrusted XML.** UI dumps are parsed with DTDs disabled (no XXE).
- **Annotations.** `clear_app_data` and `install_apk` are marked destructive. Keep them behind a
  confirmation in your client.
- **Privacy.** Screenshots, UI text, logs and anything you ask it to type are sent to your model
  provider as part of the conversation. Use test accounts and test data, and avoid devices that hold
  personal information.

Logging goes to stderr only; stdout is reserved for the MCP protocol.

## Design decisions

**Compact UI dumps instead of raw XML.** A uiautomator dump of the Settings home screen is 29 KB of
XML, mostly layout containers the model can't act on. adb-buddy sends one line per element that can
be read or tapped, with its center coordinates, so the model gets tap targets directly instead of
estimating them from a screenshot. A clickable row takes its children's text, so it is one target.

**Downscaled screenshots with a scale factor.** Device screenshots are large (1080x2424, about 1.4 MB
as PNG). adb-buddy scales them to 1280 px on the longest side by default and states the factor, so
coordinates read from the image can be converted back to device pixels. Screenshots are for visual
checks; the UI dump is the better source for taps.

**No generic shell tool.** A "run any command" tool would make every other safeguard optional. A
fixed set of tools with validated parameters keeps what the model can do reviewable, and lets the
annotations mean something. The cost is that a new capability needs code.

**Errors as tool results, not exceptions.** MCP separates protocol errors, which clients may not
show to the model, from tool results with `isError`, which the model reads. Problems such as an
ambiguous device or an off-screen tap come back as results with a next step ("pass `serial`: one
of …"), so the model can recover on its own.

**stdio first.** adb runs on the developer's machine, so the server does too. With stdio the client
starts and stops the process, there is no port to open and nothing to authenticate. Streamable HTTP
for remote device farms is on the roadmap.

## Architecture

```mermaid
flowchart LR
    Client["MCP client<br/>(Claude Code / Desktop)"] -- "JSON-RPC over stdio" --> Main
    subgraph adb-buddy
        Main["Main.kt<br/>stdio transport"] --> Server["AdbBuddyServer<br/>MCP Server"]
        Server --> Tools["tools/<br/>Device · Log · Screen · Input · App"]
        Server --> Prompt["prompts/<br/>debug_crash"]
        Tools --> Validators["validate/<br/>Validators"]
        Tools --> Parsers["parse/<br/>devices · logcat · crash · UI XML · packages"]
        Tools --> AdbClient["adb/AdbClient<br/>device resolution, shell quoting"]
        AdbClient --> Runner["ProcessRunner<br/>(timeouts, output caps)"]
    end
    Runner -- "argv, no shell" --> ADB["adb"] --> Device["📱 device / emulator"]
```

| Path | Responsibility |
| --- | --- |
| `adb/ProcessRunner.kt` | Runs a command as an argument list with timeout and output cap; a fake in tests |
| `adb/AdbLocator.kt` | Finds adb from `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `PATH` |
| `adb/AdbClient.kt` | Typed adb operations, device selection, device-shell quoting |
| `adb/AdbException.kt` | Every failure a client can see, with model-friendly messages |
| `parse/` | Pure parsers for adb output, tested against captured and synthetic fixtures |
| `tools/` | One file per tool group; thin MCP adapters with schemas and annotations |
| `validate/Validators.kt` | Input validation and `input text` encoding |

## Testing

- **Unit tests** (JUnit 5 + Kotest assertions) cover every parser. UI dumps, `getprop` and `dumpsys`
  fixtures were captured from an Android 17 emulator; logcat, crash and device-list fixtures are
  synthetic, written to cover edge cases (interleaved crash logs, rotated screens, XXE payloads,
  injection attempts). [Where each fixture comes from](src/test/resources/fixtures/README.md).
- **Tool tests** run the real MCP server against an in-memory MCP client, with adb replaced by a
  scripted fake that asserts the exact argument lists sent to adb.
- **Protocol checks** with the MCP Inspector CLI: `scripts/verify-inspector.sh` (add `--device` to
  call tools on a connected device). Results and the protocol-era matrix are in
  [docs/protocol-verification.md](docs/protocol-verification.md).

The server speaks every handshake-era protocol version (`2024-11-05` to `2025-11-25`). The
stateless `2026-07-28` era isn't available yet because the Kotlin SDK doesn't implement it;
see the roadmap.

## Roadmap

- [ ] Stateless MCP (`2026-07-28`) once the Kotlin SDK ships it ([kotlin-sdk#985](https://github.com/modelcontextprotocol/kotlin-sdk/pull/985))
- [ ] `structuredContent` + output schemas for `list_devices` and `dump_ui_hierarchy`
- [ ] More keys (`DEL`, `TAB`, `DPAD_*`) and a `long_press` tool
- [ ] `find_element` / `tap_element` by text or resource id, so the model doesn't need coordinates
- [ ] Unicode text input (via clipboard or an IME helper)
- [ ] Screen recording (`screenrecord`) returned as a resource
- [ ] Streamable HTTP transport for remote device farms
- [ ] Release workflow publishing the JAR to GitHub Releases

## License

[MIT](LICENSE)
