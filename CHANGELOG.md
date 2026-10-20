# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-20

First public release.

### Added

- MCP server over stdio, built on the MCP Kotlin SDK 0.15.0 and shipped as a single JAR.
- Read-only tools: `list_devices`, `get_logcat`, `get_crash_log`, `take_screenshot`,
  `dump_ui_hierarchy`, `list_packages`, `get_current_activity`.
- Action tools: `launch_app`, `tap`, `swipe`, `input_text`, `press_key`.
- Destructive tools: `install_apk` (`adb install -r -t`), `clear_app_data` (`pm clear`).
- `debug_crash` prompt: collects the crash log, a screenshot and the UI hierarchy, then explains the cause.
- Safety: no generic shell tool, argument-list process execution, single-quoting for the device shell,
  validation of package names, keys, coordinates and APK files, timeouts and output caps.
- Protocol support for every handshake-era version (`2024-11-05` to `2025-11-25`), verified with the
  MCP Inspector CLI.

[0.1.0]: https://github.com/ezanetta/adb-buddy-mcp/releases/tag/v0.1.0
