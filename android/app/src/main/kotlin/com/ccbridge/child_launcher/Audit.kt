package com.ccbridge.child_launcher

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/**
 * 行为审计：把程序**对这台设备做过的每一个动作**按时间顺序记一条。
 *
 * 和 [Diag] 的分工：[Diag] 记的是「程序内部在想什么」（判定现场、放行原因、异常），
 * 又多又碎，是给定位问题用的；这里只记**动作本身**——拦了哪个应用、弹了哪个框、
 * 写了哪个文件、起了哪个服务——条数少、一眼能扫完，用来回答「程序有没有做不该做的事」。
 *
 * 两处去处，和 [Diag] 一样：内存环形缓冲（[MAX] 条，自检报告里当场显示）+ 文件
 * `files/logs/audit.log`（可通过文件传输服务用浏览器下载下来发给我）。不联网、不落别处。
 */
object Audit {
    /** 内存里留多少条。动作比日志稀疏得多，300 条通常够覆盖好几天的现场 */
    private const val MAX = 300

    /** 单个审计文件的上限，超了轮转成 .1（老的覆盖掉） */
    private const val FILE_MAX = 256 * 1024L

    private const val FILE_NAME = "audit.log"

    // ---------- 动作类别（显示在每行开头的方括号里，也是报告里的分类计数） ----------

    /** 把非白名单应用弹回桌面 */
    const val BOUNCE = "拦截"

    /** 退掉「最近任务」那一屏 */
    const val TASK_KILL = "任务键"

    /** 桌面打开一个白名单应用 */
    const val LAUNCH = "打开应用"

    /** 挑战没答对，把孩子送回他刚才在用的应用 */
    const val RETURN = "送回应用"

    /** 主动把孩子送回桌面（乘法答错等） */
    const val HOME = "回桌面"

    /** 顶部「本次剩余」浮层加上/移除 */
    const val OVERLAY = "浮层"

    /** 弹挑战框（乘法/密码），以及通知 Flutter 弹框 */
    const val CHALLENGE = "挑战框"

    /** 拉起密码页，以及密码输对/输错 */
    const val LOCK = "密码页"

    /** 家长改了配置 */
    const val CONFIG = "改配置"

    /** 家长外出放行的开始/结束 */
    const val FREE = "家长放行"

    /** 前台服务/文件传输服务的起停 */
    const val SERVICE = "服务"

    /** 浏览器连上来的请求与结局 */
    const val HTTP = "文件传输"

    /** 往设备上写文件（上传落 Download/ 或私有 uploads/） */
    const val FILE = "写文件"

    /** 生成自检报告 */
    const val REPORT = "自检"

    /** 开机 */
    const val BOOT = "开机"

    /** 打开系统页面（家长外出办事） */
    const val SYSTEM = "系统页"

    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var app: Context? = null

    @Volatile
    private var diskBroken = false

    private var sinceRotateCheck = 0

    /** 由 [Diag.attach] 一并接上：两个日志共用同一个 application context */
    fun attach(ctx: Context) {
        app = ctx.applicationContext
    }

    /**
     * 记一个动作。[kind] 用上面的常量，[target] 是动作作用在谁身上（包名/IP/文件名，
     * 没有具体对象就传空串），[detail] 一句话说清做了什么——报告里给家长看的就是这三段。
     *
     * 只有一个三参数版本，**不提供两个参数的便利重载**：那会让 `record(K, "X")` 的含义
     * 变成「X 是 target 还是 detail」全凭数参数个数，写错一处就静默记错字段。
     */
    @Synchronized
    fun record(kind: String, target: String, detail: String) {
        val t = if (target.isBlank()) "—" else target
        val line = "${fmt.format(Date())} [$kind] $t — $detail"
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        append("${fileFmt.format(Date())} [$kind] $t — $detail")
    }

    @Synchronized
    fun size(): Int = buf.size

    @Synchronized
    private fun snapshot(): List<String> = buf.toList()

    /** 审计文件；和运行日志同在 files/logs/ 下 */
    fun file(ctx: Context): File = File(Diag.logDir(ctx), FILE_NAME)

    private fun append(line: String) {
        if (diskBroken) return
        val ctx = app ?: return
        try {
            val dir = Diag.logDir(ctx)
            if (!dir.exists() && !dir.mkdirs()) {
                diskBroken = true
                return
            }
            val f = File(dir, FILE_NAME)
            if (++sinceRotateCheck >= 50) {
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
     * 自检报告里的一小节：先给各类动作的计数（一眼看出程序这一阵都在干什么），
     * 再按时间顺序列明细。
     */
    fun report(ctx: Context): String {
        val lines = snapshot()
        val sb = StringBuilder()
        sb.appendLine("记录条数：${lines.size}（内存里最多留 $MAX 条，文件里更全）")
        sb.appendLine("审计文件：${file(ctx).absolutePath}")
        sb.appendLine("看这一节的用法：先看计数有没有奇怪的类别，再顺着明细看那个动作前后发生了什么")
        sb.appendLine()
        val counts = LinkedHashMap<String, Int>()
        for (l in lines) {
            val k = l.substringAfter('[', "?").substringBefore(']')
            counts[k] = (counts[k] ?: 0) + 1
        }
        sb.appendLine(
            "各类动作次数：" + if (counts.isEmpty()) "（还没记过）"
            else counts.entries.joinToString("、") { "${it.key} ${it.value}" }
        )
        sb.appendLine()
        sb.appendLine("动作明细（从旧到新）：")
        sb.append(if (lines.isEmpty()) "（暂无）" else lines.joinToString("\n"))
        return sb.toString()
    }
}
