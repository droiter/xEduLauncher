package com.ccbridge.child_launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 所有持久化状态由原生侧统一持有，Dart 仅通过 MethodChannel 读写。
 * 这样计时服务(GuardService)与 UI 用的是同一份数据，不会出现两套 SharedPreferences 不一致。
 */
object Store {
    const val FILE = "child_launcher_prefs"

    private const val K_PASSWORD = "password"
    private const val K_SETTINGS_PASSWORD = "settings_password"
    private const val K_CHALLENGE_TYPE = "challenge_type" // none | mul | add | password
    private const val K_CH_ON_HOME = "challenge_on_home"
    private const val K_CH_ON_LAUNCH = "challenge_on_launch"
    private const val K_ALLOWED = "allowed_packages"
    private const val K_NO_CHALLENGE = "no_challenge_packages"
    private const val K_FRONT_GUARD = "front_guard_enabled"
    private const val K_PARENT_FREE_UNTIL = "parent_free_until"
    private const val K_SETTINGS_FREE_MIN = "settings_free_minutes"
    private const val K_DAILY_LIMIT_MIN = "daily_limit_minutes"
    private const val K_SINGLE_USE_MIN = "single_use_minutes"
    private const val K_GRACE_MIN = "grace_minutes"
    private const val K_OPEN_LIMIT = "open_limit"
    private const val K_USED_SECONDS = "used_seconds"
    private const val K_EXTRA_SECONDS = "extra_seconds"
    private const val K_OPEN_COUNT = "open_count"
    private const val K_STAT_DATE = "stat_date"
    private const val K_LAST_RESUME = "last_resume"
    private const val K_GUARD_ENABLED = "guard_enabled"
    private const val K_LAST_FOREIGN = "last_foreign_pkg"
    private const val K_BOUNCE_AT = "guard_bounce_at"
    private const val K_CURRENT_FG = "current_foreground_pkg"
    private const val K_SELF_FG_AT = "self_foreground_at"
    private const val K_OTHER_FG_AT = "other_foreground_at"
    private const val K_FILE_SERVER = "file_server_enabled"
    private const val K_APPROVED_IPS = "file_server_approved_ips"

    const val DEFAULT_PASSWORD = "123456"

    /** 文件传输服务默认端口；被占用时由 HttpGateway 往后顺延 */
    const val DEFAULT_FILE_PORT = 8080

    /** 单次使用时长缺省值：家长不设也有 5 分钟 */
    const val DEFAULT_SINGLE_USE_MIN = 5

    /** 距上次进入桌面超过该毫秒数，才把本次进入算作一次新的“打开” */
    private const val NEW_OPEN_GAP_MS = 120_000L

    /** 守护弹回桌面后，这段时间内的「回到桌面」不再算孩子按的 Home */
    private const val GUARD_BOUNCE_WINDOW_MS = 2_500L

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ---------- 基础读写 ----------

    private fun str(ctx: Context, k: String, d: String) = p(ctx).getString(k, d) ?: d
    private fun bool(ctx: Context, k: String, d: Boolean) = p(ctx).getBoolean(k, d)
    private fun num(ctx: Context, k: String, d: Int) = p(ctx).getInt(k, d)

    /** 跨天则清零当日统计 */
    fun rollDate(ctx: Context) {
        val prefs = p(ctx)
        val d = prefs.getString(K_STAT_DATE, "")
        if (d != today()) {
            prefs.edit()
                .putString(K_STAT_DATE, today())
                .putInt(K_USED_SECONDS, 0)
                .putInt(K_EXTRA_SECONDS, 0)
                .putInt(K_OPEN_COUNT, 0)
                .apply()
        }
    }

    // ---------- 各项配置 ----------

    fun password(ctx: Context) = str(ctx, K_PASSWORD, DEFAULT_PASSWORD)

    /** 未单独设置时沿用家长控制密码，家长不改也不会被关在系统设置外面 */
    fun settingsPassword(ctx: Context): String {
        val own = str(ctx, K_SETTINGS_PASSWORD, "")
        return if (own.isBlank()) password(ctx) else own
    }

    fun hasOwnSettingsPassword(ctx: Context) = str(ctx, K_SETTINGS_PASSWORD, "").isNotBlank()

    fun challengeType(ctx: Context) = str(ctx, K_CHALLENGE_TYPE, "mul")
    fun challengeOnHome(ctx: Context) = bool(ctx, K_CH_ON_HOME, true)
    fun challengeOnLaunch(ctx: Context) = bool(ctx, K_CH_ON_LAUNCH, true)
    fun guardEnabled(ctx: Context) = bool(ctx, K_GUARD_ENABLED, false)

    fun allowed(ctx: Context): List<String> =
        (p(ctx).getStringSet(K_ALLOWED, emptySet()) ?: emptySet()).toList().sorted()

    /** 白名单里「点开就进、不弹挑战」的那部分应用 */
    fun noChallenge(ctx: Context): Set<String> =
        p(ctx).getStringSet(K_NO_CHALLENGE, emptySet()) ?: emptySet()

    fun frontGuard(ctx: Context) = bool(ctx, K_FRONT_GUARD, false)
    fun settingsFreeMin(ctx: Context) = num(ctx, K_SETTINGS_FREE_MIN, 10)

    fun dailyLimitMin(ctx: Context) = num(ctx, K_DAILY_LIMIT_MIN, 0)

    /**
     * 单次使用时长上限（分钟），0 = 不限。算的是「连续待在同一个白名单应用里」的时长，
     * 由无障碍守护按窗口切换计时，到点弹一道一位数乘法：答对清零重新计时，答错送回桌面。
     */
    fun singleUseMin(ctx: Context) = num(ctx, K_SINGLE_USE_MIN, DEFAULT_SINGLE_USE_MIN)
    fun graceMin(ctx: Context) = num(ctx, K_GRACE_MIN, 10)
    fun openLimit(ctx: Context) = num(ctx, K_OPEN_LIMIT, 0)
    fun usedSeconds(ctx: Context) = num(ctx, K_USED_SECONDS, 0)
    fun extraSeconds(ctx: Context) = num(ctx, K_EXTRA_SECONDS, 0)
    fun openCount(ctx: Context) = num(ctx, K_OPEN_COUNT, 0)

    fun addUsedSeconds(ctx: Context, n: Int) {
        if (n <= 0) return
        val prefs = p(ctx)
        prefs.edit().putInt(K_USED_SECONDS, prefs.getInt(K_USED_SECONDS, 0) + n).apply()
    }

    /**
     * 密码通过后追加宽限时间。
     * 同时刷新 last_resume：解锁过程通常要花上一两分钟，若不刷新，
     * 紧接着的 onResume 会被当成一次新的“打开”，从而立刻再次触发次数锁屏。
     */
    fun grantGrace(ctx: Context) {
        val prefs = p(ctx)
        prefs.edit()
            .putInt(K_EXTRA_SECONDS, prefs.getInt(K_EXTRA_SECONDS, 0) + graceMin(ctx) * 60)
            .putLong(K_LAST_RESUME, SystemClock.elapsedRealtime())
            .apply()
    }

    fun resetOpenCount(ctx: Context) {
        p(ctx).edit()
            .putInt(K_OPEN_COUNT, 0)
            .putLong(K_LAST_RESUME, SystemClock.elapsedRealtime())
            .apply()
    }

    fun resetStats(ctx: Context) {
        p(ctx).edit()
            .putInt(K_USED_SECONDS, 0)
            .putInt(K_EXTRA_SECONDS, 0)
            .putInt(K_OPEN_COUNT, 0)
            .putString(K_STAT_DATE, today())
            .apply()
    }

    // ---------- 打开次数统计 ----------

    /**
     * 进入桌面时调用。只有距上次进入超过 NEW_OPEN_GAP_MS 才计一次，
     * 避免在桌面内反复按 Home 被重复计数。
     */
    fun onLauncherResume(ctx: Context) {
        rollDate(ctx)
        val prefs = p(ctx)
        val now = SystemClock.elapsedRealtime()
        val last = prefs.getLong(K_LAST_RESUME, 0L)
        if (last == 0L || now - last > NEW_OPEN_GAP_MS) {
            prefs.edit().putInt(K_OPEN_COUNT, prefs.getInt(K_OPEN_COUNT, 0) + 1).apply()
        }
        prefs.edit().putLong(K_LAST_RESUME, now).apply()
    }

    /** 开机：计一次打开，并刷新时间戳防止紧接着的 onResume 重复计数 */
    fun onBoot(ctx: Context) {
        rollDate(ctx)
        val prefs = p(ctx)
        prefs.edit()
            .putInt(K_OPEN_COUNT, prefs.getInt(K_OPEN_COUNT, 0) + 1)
            .putLong(K_LAST_RESUME, SystemClock.elapsedRealtime())
            // 重启后 elapsedRealtime 归零，重启前记的前台时刻再也对不上谁的先谁后，清掉免得判反
            .putLong(K_SELF_FG_AT, 0L)
            .putLong(K_OTHER_FG_AT, 0L)
            .apply()
    }

    // ---------- 门禁判定 ----------

    /** 返回 "time" / "count" / null */
    fun gateReason(ctx: Context): String? {
        rollDate(ctx)
        val limit = dailyLimitMin(ctx) * 60
        if (limit > 0 && usedSeconds(ctx) >= limit + extraSeconds(ctx)) return "time"
        val ol = openLimit(ctx)
        if (ol > 0 && openCount(ctx) >= ol) return "count"
        return null
    }

    fun showLock(ctx: Context, reason: String) {
        if (LockActivity.showing) return
        val i = Intent(ctx, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LockActivity.EXTRA_REASON, reason)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            // 无悬浮窗权限时后台启动 Activity 会被系统拦截，忽略即可
        }
    }

    /** 单次使用时长到点：在孩子正用着的那个应用之上弹出乘法挑战页 */
    fun showSessionChallenge(ctx: Context, pkg: String) {
        if (SessionChallengeActivity.showing) return
        val i = Intent(ctx, SessionChallengeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(SessionChallengeActivity.EXTRA_PKG, pkg)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            // 后台启动 Activity 需要悬浮窗权限，没有的话只能放弃这一次挑战（计时已停，不会再连环弹）
            Diag.log("session", "弹乘法挑战页失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------- Home 键挑战的现场 ----------

    /**
     * 孩子此刻真正在用的应用（前台守护在窗口切换时记）。他在这个应用里按 Home 想回桌面、
     * 挑战又没答对时，桌面把他送回这里——桌面要答对才进得去。开机时清掉，免得送进昨天的应用。
     */
    fun noteForeign(ctx: Context, pkg: String) {
        p(ctx).edit().putString(K_LAST_FOREIGN, pkg).apply()
    }

    fun lastForeign(ctx: Context): String? =
        p(ctx).getString(K_LAST_FOREIGN, "")?.takeIf { it.isNotBlank() }

    fun clearLastForeign(ctx: Context) {
        p(ctx).edit().putString(K_LAST_FOREIGN, "").apply()
    }

    /**
     * 此刻出现在最前面的窗口属于哪个包（前台守护在窗口切换时记，包括本应用自己的桌面）。
     * 同时按「是不是本应用」把时刻分开记：桌面判「这一次露面是不是从应用里逃出来的」时，
     * 除了无障碍那条窗口链（首选，见 GuardAccessibilityService.beforeLauncher），还要靠这两个
     * 时刻谁更晚来兜底——比只比包名结实，输入法/状态栏/系统桌面这类一闪而过的窗口不会把结论带偏。
     * [countAsApp] 由守护判定：只有孩子真在用的应用才算「别的应用」，见 GuardAccessibilityService。
     */
    fun noteForeground(ctx: Context, pkg: String, countAsApp: Boolean) {
        val e = p(ctx).edit().putString(K_CURRENT_FG, pkg)
        if (pkg == ctx.packageName) {
            e.putLong(K_SELF_FG_AT, SystemClock.elapsedRealtime())
        } else if (countAsApp) {
            e.putLong(K_OTHER_FG_AT, SystemClock.elapsedRealtime())
        }
        e.apply()
    }

    fun currentForeground(ctx: Context): String? =
        p(ctx).getString(K_CURRENT_FG, "")?.takeIf { it.isNotBlank() }

    /** 本应用（桌面/密码页）最后一次出现在最前面的时刻 */
    fun selfForegroundAt(ctx: Context): Long = p(ctx).getLong(K_SELF_FG_AT, 0L)

    /** 孩子用的别的应用最后一次出现在最前面的时刻 */
    fun otherForegroundAt(ctx: Context): Long = p(ctx).getLong(K_OTHER_FG_AT, 0L)

    /** 前台守护刚用 GLOBAL_ACTION_HOME 把孩子弹回桌面（防他打开非白名单应用），记下时刻 */
    fun noteGuardBounce(ctx: Context) {
        p(ctx).edit().putLong(K_BOUNCE_AT, SystemClock.elapsedRealtime()).apply()
    }

    /**
     * 这次「回到桌面」是不是守护自己刚弹的。是的话别弹挑战框：孩子是打开了非白名单应用被拦回来的，
     * 不是按 Home 逃回桌面，弹框只会让他白做一道题。
     */
    fun guardBounceRecent(ctx: Context): Boolean =
        SystemClock.elapsedRealtime() - p(ctx).getLong(K_BOUNCE_AT, 0L) < GUARD_BOUNCE_WINDOW_MS

    // ---------- 文件传输服务 ----------

    /**
     * 家长在设置里打开的文件传输服务：手机浏览器连上就能下载日志、上传视频。
     * 每个新连上来的设备都要在手机上点一次「同意」（见 HttpConsentActivity）。
     */
    fun fileServerOn(ctx: Context) = bool(ctx, K_FILE_SERVER, false)

    /** 已同意过的设备 IP（关掉服务时清空，下次开启重新问） */
    fun approvedIps(ctx: Context): Set<String> =
        p(ctx).getStringSet(K_APPROVED_IPS, emptySet()) ?: emptySet()

    fun approveIp(ctx: Context, ip: String) {
        p(ctx).edit().putStringSet(K_APPROVED_IPS, approvedIps(ctx) + ip).apply()
        Diag.log("http", "家长同意了 $ip 的连接")
    }

    fun revokeIps(ctx: Context) {
        p(ctx).edit().putStringSet(K_APPROVED_IPS, emptySet()).apply()
    }

    /** 文件传输服务和日志共用的根目录：/sdcard/Android/data/<包名>/files */
    fun filesRoot(ctx: Context): File =
        ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "external")

    // ---------- 家长外出放行 ----------

    /**
     * 家长拿着第二个密码去系统设置里办事期间，前台守护必须让路，
     * 否则刚打开设置页就会被弹回桌面。回到桌面（MainActivity.onResume）即收回。
     * 同时留一个超时兜底：万一家长把手机停在设置页不管了，到点自动恢复管控。
     */
    fun grantParentFree(ctx: Context) {
        val until = SystemClock.elapsedRealtime() + settingsFreeMin(ctx) * 60_000L
        p(ctx).edit().putLong(K_PARENT_FREE_UNTIL, until).apply()
        Diag.log("guard", "家长放行开始，${settingsFreeMin(ctx)} 分钟内不拦截前台应用")
    }

    fun clearParentFree(ctx: Context) {
        if (p(ctx).getLong(K_PARENT_FREE_UNTIL, 0L) != 0L) {
            p(ctx).edit().putLong(K_PARENT_FREE_UNTIL, 0L).apply()
            Diag.log("guard", "家长放行结束")
        }
    }

    fun parentFreeActive(ctx: Context): Boolean =
        p(ctx).getLong(K_PARENT_FREE_UNTIL, 0L) > SystemClock.elapsedRealtime()

    // ---------- 系统能力 ----------

    fun isDefaultLauncher(ctx: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.resolveActivity(home, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.resolveActivity(home, 0)
        }
        return res?.activityInfo?.packageName == ctx.packageName
    }

    fun hasOverlay(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 23) android.provider.Settings.canDrawOverlays(ctx) else true

    /** 本应用的无障碍服务是否已在系统里被打开 */
    fun accessibilityOn(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName }
    } catch (_: Exception) {
        false
    }

    // ---------- 与 Dart 交换的配置快照 ----------

    fun configMap(ctx: Context): Map<String, Any> {
        rollDate(ctx)
        return mapOf(
            "password" to password(ctx),
            "settingsPassword" to settingsPassword(ctx),
            "settingsPwCustom" to hasOwnSettingsPassword(ctx),
            "challengeType" to challengeType(ctx),
            "chOnHome" to challengeOnHome(ctx),
            "chOnLaunch" to challengeOnLaunch(ctx),
            "allowed" to allowed(ctx),
            "noChallenge" to noChallenge(ctx).sorted(),
            "dailyLimitMin" to dailyLimitMin(ctx),
            "singleUseMin" to singleUseMin(ctx),
            "graceMin" to graceMin(ctx),
            "openLimit" to openLimit(ctx),
            "usedSeconds" to usedSeconds(ctx),
            "extraSeconds" to extraSeconds(ctx),
            "openCount" to openCount(ctx),
            "isDefaultLauncher" to isDefaultLauncher(ctx),
            "hasOverlay" to hasOverlay(ctx),
            "guardEnabled" to guardEnabled(ctx),
            "frontGuard" to frontGuard(ctx),
            "accessibilityOn" to accessibilityOn(ctx),
            "settingsFreeMin" to settingsFreeMin(ctx),
            "fileServerOn" to fileServerOn(ctx),
            "fileServerUrl" to HttpGateway.urlOrEmpty(ctx),
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun applyConfig(ctx: Context, m: Map<String, Any?>) {
        val e = p(ctx).edit()
        (m["password"] as? String)?.let { if (it.isNotBlank()) e.putString(K_PASSWORD, it) }
        (m["settingsPassword"] as? String)?.let {
            // 空串 = 恢复成「沿用家长密码」
            e.putString(K_SETTINGS_PASSWORD, it.trim())
        }
        (m["challengeType"] as? String)?.let { e.putString(K_CHALLENGE_TYPE, it) }
        (m["chOnHome"] as? Boolean)?.let { e.putBoolean(K_CH_ON_HOME, it) }
        (m["chOnLaunch"] as? Boolean)?.let { e.putBoolean(K_CH_ON_LAUNCH, it) }
        (m["allowed"] as? List<String>)?.let { e.putStringSet(K_ALLOWED, it.toSet()) }
        (m["noChallenge"] as? List<String>)?.let {
            // 只保留还在白名单里的包，避免删掉应用后留下孤儿配置
            val keep = (m["allowed"] as? List<String>)?.toSet()
            e.putStringSet(
                K_NO_CHALLENGE,
                if (keep == null) it.toSet() else it.filter { p -> p in keep }.toSet(),
            )
        }
        (m["dailyLimitMin"] as? Number)?.let { e.putInt(K_DAILY_LIMIT_MIN, it.toInt()) }
        (m["singleUseMin"] as? Number)?.let { e.putInt(K_SINGLE_USE_MIN, it.toInt().coerceIn(0, 120)) }
        (m["graceMin"] as? Number)?.let { e.putInt(K_GRACE_MIN, it.toInt()) }
        (m["openLimit"] as? Number)?.let { e.putInt(K_OPEN_LIMIT, it.toInt()) }
        (m["guardEnabled"] as? Boolean)?.let { e.putBoolean(K_GUARD_ENABLED, it) }
        (m["frontGuard"] as? Boolean)?.let { e.putBoolean(K_FRONT_GUARD, it) }
        (m["settingsFreeMin"] as? Number)?.let { e.putInt(K_SETTINGS_FREE_MIN, it.toInt()) }
        (m["fileServerOn"] as? Boolean)?.let { e.putBoolean(K_FILE_SERVER, it) }
        e.apply()
    }
}
