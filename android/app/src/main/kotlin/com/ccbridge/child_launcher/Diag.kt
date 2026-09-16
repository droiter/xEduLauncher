package com.ccbridge.child_launcher

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 运行日志。两处去处：
 *
 * · 内存环形缓冲（[MAX] 条）——给「家长设置 → 桌面自检」当场显示、一键复制；
 * · 文件 `files/logs/child_launcher.log`——家长只有真机能复现的问题，光看当场那份不够，
 *   落到文件里才能通过「文件传输服务」用浏览器下载下来发给我。超过 [FILE_MAX] 自动轮转一份 .1。
 *
 * 除了这两处不做别的：不联网、不上传、不读取任何屏幕内容。
 */
object Diag {
    private const val MAX = 400

    /** 单个日志文件的上限，超了就轮转成 .1（老的覆盖掉） */
    private const val FILE_MAX = 512 * 1024L

    private const val FILE_NAME = "child_launcher.log"

    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 应用上下文。只存 application context，不会泄漏 Activity */
    @Volatile
    private var app: Context? = null

    /** 落盘失败（目录不可写之类）就放弃，别每条日志都去撞一次异常 */
    @Volatile
    private var diskBroken = false

    private var sinceRotateCheck = 0

    fun attach(ctx: Context) {
        app = ctx.applicationContext
        // 行为审计和运行日志共用同一个 application context。接在这一处就够了：
        // 全项目只在 Activity/Service/Receiver 起来时调 Diag.attach，两处一起接上，
        // 免得将来新增一个服务只接了 Diag、审计文件却从这一刻起断在那
        Audit.attach(ctx)
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        append("${fileFmt.format(Date())} [$tag] $msg")
    }

    @Synchronized
    fun size(): Int = buf.size

    @Synchronized
    fun dump(): String =
        if (buf.isEmpty()) "（暂无日志）" else buf.joinToString("\n")

    // ---------- 落盘 ----------

    /** 日志目录；文件传输服务也把这一层当下载根目录的一部分 */
    fun logDir(ctx: Context): File = File(Store.filesRoot(ctx), "logs")

    /** 当前日志文件（可能还不存在） */
    fun logFile(ctx: Context): File = File(logDir(ctx), FILE_NAME)

    private fun append(line: String) {
        if (diskBroken) return
        val ctx = app ?: return
        try {
            val dir = logDir(ctx)
            if (!dir.exists() && !dir.mkdirs()) {
                diskBroken = true
                return
            }
            val f = File(dir, FILE_NAME)
            if (++sinceRotateCheck >= 100) {
                sinceRotateCheck = 0
                if (f.length() > FILE_MAX) {
                    val old = File(dir, "$FILE_NAME.1")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
            }
            FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()) }
        } catch (_: Exception) {
            diskBroken = true
        }
    }

    /**
     * 把整份报告另存一份带时间戳的文件，供家长事后下载。
     * 返回保存到的文件（失败返回 null）。报告末尾会把路径写进去，家长照着找就行。
     */
    fun writeReport(ctx: Context, text: String): File? = try {
        val dir = logDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        val name = "自检报告-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        val f = File(dir, name)
        f.writeText(text)
        f
    } catch (_: Exception) {
        null
    }

    // ---------- 环境 ----------

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
