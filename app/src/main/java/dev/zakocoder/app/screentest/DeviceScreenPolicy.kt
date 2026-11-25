package dev.zakocoder.app.screentest

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.concurrent.ConcurrentHashMap

private val protectedSettingsPrefixes = listOf(
    "com.android.settings",
    "com.google.android.settings",
    "com.samsung.android.settings",
    "com.sec.android.app.settings",
    "com.miui.settings",
    "com.miui.securitycenter",
    "com.oplus.settings",
    "com.oplus.safecenter",
    "com.coloros.settings",
    "com.coloros.safecenter",
    "com.oppo.settings",
    "com.oppo.safecenter",
    "com.oneplus.settings",
    "com.oneplus.security",
    "com.vivo.settings",
    "com.vivo.abe",
    "com.iqoo.settings",
    "com.iqoo.secure",
    "com.huawei.settings",
    "com.huawei.systemmanager",
    "com.hihonor.settings",
    "com.hihonor.systemmanager",
    "com.transsion.settings",
    "com.meizu.settings",
    "com.meizu.safe",
    "com.zui.settings",
    "com.lenovo.settings",
    "com.motorola.settings",
    "com.lge.settings",
    "com.sonyericsson.settings",
    "com.htc.settings",
    "com.asus.settings",
    "com.realme.settings",
    "com.realme.securitycheck",
)

private val resolvedSettingsPackages = ConcurrentHashMap.newKeySet<String>()

private fun isProtectedSettingsPackage(packageName: String): Boolean =
    packageName in resolvedSettingsPackages ||
        protectedSettingsPrefixes.any { packageName == it || packageName.startsWith("$it.") }

/** Device scope never delegates the host's approval UI or Android install/permission decisions. */
internal fun protectedDeviceScreenPackage(packageName: String, hostPackage: String, permissionController: String?): Boolean =
    packageName.isBlank() || packageName == hostPackage || packageName == permissionController ||
        isProtectedSettingsPackage(packageName) ||
        packageName == "android" || packageName == "com.android.systemui" ||
        packageName.endsWith(".permissioncontroller") || packageName.endsWith(".packageinstaller")

/** Resolve permission management and remember exact Settings providers used by vendor redirects. */
@Suppress("DEPRECATION")
internal fun resolvedPermissionController(context: Context): String? {
    val packageManager = context.packageManager
    listOf(
        Settings.ACTION_SETTINGS,
        Settings.ACTION_APPLICATION_SETTINGS,
        Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
        "android.intent.action.MANAGE_PERMISSIONS",
        "android.intent.action.MANAGE_SPECIAL_APP_ACCESSES",
    ).forEach { action ->
        runCatching {
            packageManager.resolveActivity(Intent(action), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.let(resolvedSettingsPackages::add)
    }
    return packageManager.resolveActivity(Intent("android.intent.action.MANAGE_PERMISSIONS"),
        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
}
