package dev.zakocoder.app.screentest

/** Device scope never delegates the host's approval UI or Android install/permission decisions. */
internal fun protectedDeviceScreenPackage(packageName: String, hostPackage: String, permissionController: String?): Boolean =
    packageName.isBlank() || packageName == hostPackage || packageName == permissionController ||
        packageName == "android" || packageName == "com.android.systemui" ||
        packageName.endsWith(".permissioncontroller") || packageName.endsWith(".packageinstaller")

/** Resolve the device's permission-management activity through public PackageManager APIs. */
@Suppress("DEPRECATION")
internal fun resolvedPermissionController(context: android.content.Context): String? =
    context.packageManager.resolveActivity(android.content.Intent("android.intent.action.MANAGE_PERMISSIONS"),
        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
