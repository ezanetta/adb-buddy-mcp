package io.github.ezanetta.adbbuddy.tools

import io.github.ezanetta.adbbuddy.adb.AdbClient
import io.github.ezanetta.adbbuddy.validate.Validators
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerAppTools(adb: AdbClient) {
    adbTool(
        name = "list_packages",
        description = "List installed third-party (user) app packages, " +
            "optionally filtered by a case-insensitive substring.",
        annotations = Annotations.readOnly("List packages"),
        schema = schema {
            string("filter", "Only packages whose name contains this text, e.g. example.")
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val filter = args.optionalString("filter")
        val packages = adb.thirdPartyPackages(serial).filter {
            filter == null || it.contains(filter, ignoreCase = true)
        }
        val scope = if (filter == null) "third-party packages" else "third-party packages matching '$filter'"
        textResult(
            if (packages.isEmpty()) {
                "No $scope on $serial."
            } else {
                "${packages.size} $scope on $serial:\n" + packages.joinToString("\n")
            },
        )
    }

    adbTool(
        name = "get_current_activity",
        description = "Get the app package and activity currently in the foreground.",
        annotations = Annotations.readOnly("Get current activity"),
        schema = schema(),
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val activity = adb.resumedActivity(serial)
        textResult(
            if (activity == null) {
                "No resumed activity on $serial (the screen may be off or locked)."
            } else {
                "Foreground on $serial: package ${activity.packageName}, activity ${activity.fullClassName}"
            },
        )
    }

    adbTool(
        name = "launch_app",
        description = "Launch an installed app by package name through its launcher activity (like tapping its icon).",
        annotations = Annotations.action("Launch app", idempotent = true),
        schema = schema {
            string("package", "App package name, e.g. com.example.app.", required = true)
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val packageName = Validators.packageName(args.string("package"))
        val component = adb.launchApp(serial, packageName)
        textResult("Launched ${component.flattened} on $serial. Call dump_ui_hierarchy to see the screen.")
    }

    adbTool(
        name = "install_apk",
        description = "Install or update an app from a .apk file on this computer (adb install -r -t). " +
            "Replaces an existing install of the same app and keeps its data. Debug/test-only builds are allowed.",
        annotations = Annotations.destructive("Install APK"),
        schema = schema {
            string(
                "path",
                "Path to a local .apk file, absolute or relative to the server's working directory.",
                required = true,
            )
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val apk = Validators.apkFile(args.string("path"))
        adb.install(serial, apk)
        textResult("Installed ${apk.name} on $serial.")
    }

    adbTool(
        name = "clear_app_data",
        description = "Delete all data of an app (pm clear): databases, preferences, files, caches and granted " +
            "runtime permissions. The app is stopped. This cannot be undone.",
        annotations = Annotations.destructive("Clear app data"),
        schema = schema {
            string("package", "App package name, e.g. com.example.app.", required = true)
        },
    ) { args ->
        val serial = adb.resolveSerial(args.serial)
        val packageName = Validators.packageName(args.string("package"))
        adb.clearAppData(serial, packageName)
        textResult("Cleared all data for $packageName on $serial.")
    }
}
