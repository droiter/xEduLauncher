package com.ccbridge.child_launcher

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 内存环形日志。只为「家长设置 → 桌面自检」保留现场：不落盘、不联网、重启即清空。
 */
object Diag {
    private const val MAX = 400
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        buf.addLast("${fmt.format(Date())} [$tag] $msg")
        while (buf.size > MAX) buf.removeFirst()
    }

    @Synchronized
    fun size(): Int = buf.size

    @Synchronized
    fun dump(): String =
        if (buf.isEmpty()) "（暂无日志）" else buf.joinToString("\n")

    /** 版本与设备环境，作为报告开头 */
    fun env(ctx: Context): String {
        val pm = ctx.packageManager
        val version = try {
            val pi = pm.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${pi.versionCode})"
        } catch (_: Exception) {
            "?"
        }
        val installer = try {
            val n = if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(ctx.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(ctx.packageName)
            }
            n ?: "（无，多半是直接装的 APK）"
        } catch (_: Exception) {
            "?"
        }
        return listOf(
            "版本：$version",
            "包名：${ctx.packageName}",
            "设备：${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}（${Build.DEVICE}）",
            "系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            "ABI：${Build.SUPPORTED_ABIS.joinToString(",")}",
            "安装来源：$installer",
        ).joinToString("\n")
    }
}
