# Test fixtures

Where each fixture comes from. "Captured" files are real output from an Android 17 (API 37)
emulator (`sdk_gphone16k_arm64`), trimmed where noted. "Synthetic" files were written by hand in
the exact format adb produces, to cover cases that are hard to capture on demand.

| File | Origin | Notes |
| --- | --- | --- |
| `ui_settings.xml` | captured | `uiautomator dump`, Settings home screen |
| `ui_launcher.xml` | captured | `uiautomator dump`, Pixel launcher |
| `ui_search_results.xml` | captured | Settings search with text typed through `input_text` |
| `getprop_emulator.txt` | captured, trimmed | subset of `getprop`; timezone replaced with `Etc/UTC`; last line made malformed on purpose |
| `dumpsys_window_displays.txt` | captured, trimmed | first lines of `dumpsys window displays` |
| `dumpsys_activities_api37.txt` | captured, trimmed | `dumpsys activity activities` lines around the resumed activity |
| `dumpsys_activities_api28.txt` | synthetic | Android 9 format (`mResumedActivity:`) |
| `devices_mixed.txt` | synthetic | every device state, including `no permissions` and daemon noise |
| `devices_empty.txt` | synthetic | no devices |
| `logcat_main.txt` | synthetic | first line captured; tags with colons, all levels, a malformed line |
| `logcat_crash_buffer.txt` | synthetic | Java crashes incl. secondary process, interleaved lines, native tombstone |
| `logcat_system_anr.txt` | synthetic | ANR block from `system_server` with unrelated lines on another thread |
| `pm_list_packages.txt` | synthetic | unsorted output with a blank line |
| `ui_login_form.xml` | synthetic | rotated screen, password field, checkbox, disabled row, zero-area node |
| `ui_xxe.xml` | synthetic | DOCTYPE with an external entity; must be rejected |
