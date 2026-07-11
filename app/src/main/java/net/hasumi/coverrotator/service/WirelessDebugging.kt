package net.hasumi.coverrotator.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * android.permission.WRITE_SECURE_SETTINGS が付与されていれば、ワイヤレスデバッグを自動でONにする。
 * この権限は通常インストールでは付与されず、`adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`
 * で一度だけ手動付与する必要がある。Shizukuのバインダーと異なり、pm grantでの付与は再起動後も保持されるため、
 * 一度付与しておけばBOOT_COMPLETED時にアプリ側で自動的にワイヤレスデバッグを有効化できる。
 * 変更後のポート番号自体はGraphのmDNS自動検出(connectAuto)が拾う。
 */
object WirelessDebugging {

    private const val SETTING_KEY = "adb_wifi_enabled"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean =
        try {
            Settings.Global.getInt(context.contentResolver, SETTING_KEY, 0) == 1
        } catch (_: Exception) {
            false
        }

    /** @return 権限があり、有効化を試みられたか(既にONだった場合も含む) */
    fun tryEnable(context: Context): Boolean {
        if (!hasPermission(context)) return false
        return try {
            Settings.Global.putInt(context.contentResolver, SETTING_KEY, 1)
            true
        } catch (e: Exception) {
            android.util.Log.w("WirelessDebugging", "failed to enable $SETTING_KEY", e)
            false
        }
    }

    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
}
