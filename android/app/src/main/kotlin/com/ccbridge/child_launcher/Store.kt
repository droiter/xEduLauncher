package com.ccbridge.child_launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
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
    private const val K_CHALLENGE_TYPE = "challenge_type" // none | mul | add | password
    private const val K_CH_ON_HOME = "challenge_on_home"
    private const val K_CH_ON_LAUNCH = "challenge_on_launch"
    private const val K_CH_ON_BACK = "challenge_on_back"
    private const val K_ALLOWED = "allowed_packages"
    private const val K_DAILY_LIMIT_MIN = "daily_limit_minutes"
    private const val K_GRACE_MIN = "grace_minutes"
    private const val K_OPEN_LIMIT = "open_limit"
    private const val K_USED_SECONDS = "used_seconds"
    private const val K_EXTRA_SECONDS = "extra_seconds"
    private const val K_OPEN_COUNT = "open_count"
    private const val K_STAT_DATE = "stat_date"
    private const val K_LAST_RESUME = "last_resume"
    private const val K_GUARD_ENABLED = "guard_enabled"

    const val DEFAULT_PASSWORD = "123456"

    /** 距上次进入桌面超过该毫秒数，才把本次进入算作一次新的“打开” */
    private const val NEW_OPEN_GAP_MS = 120_000L

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
    fun challengeType(ctx: Context) = str(ctx, K_CHALLENGE_TYPE, "mul")
    fun challengeOnHome(ctx: Context) = bool(ctx, K_CH_ON_HOME, true)
    fun challengeOnLaunch(ctx: Context) = bool(ctx, K_CH_ON_LAUNCH, true)
    fun challengeOnBack(ctx: Context) = bool(ctx, K_CH_ON_BACK, true)
    fun guardEnabled(ctx: Context) = bool(ctx, K_GUARD_ENABLED, false)

    fun allowed(ctx: Context): List<String> =
        (p(ctx).getStringSet(K_ALLOWED, emptySet()) ?: emptySet()).toList().sorted()

    fun dailyLimitMin(ctx: Context) = num(ctx, K_DAILY_LIMIT_MIN, 0)
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

    // ---------- 与 Dart 交换的配置快照 ----------

    fun configMap(ctx: Context): Map<String, Any> {
        rollDate(ctx)
        return mapOf(
            "password" to password(ctx),
            "challengeType" to challengeType(ctx),
            "chOnHome" to challengeOnHome(ctx),
            "chOnLaunch" to challengeOnLaunch(ctx),
            "chOnBack" to challengeOnBack(ctx),
            "allowed" to allowed(ctx),
            "dailyLimitMin" to dailyLimitMin(ctx),
            "graceMin" to graceMin(ctx),
            "openLimit" to openLimit(ctx),
            "usedSeconds" to usedSeconds(ctx),
            "extraSeconds" to extraSeconds(ctx),
            "openCount" to openCount(ctx),
            "isDefaultLauncher" to isDefaultLauncher(ctx),
            "hasOverlay" to hasOverlay(ctx),
            "guardEnabled" to guardEnabled(ctx),
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun applyConfig(ctx: Context, m: Map<String, Any?>) {
        val e = p(ctx).edit()
        (m["password"] as? String)?.let { if (it.isNotBlank()) e.putString(K_PASSWORD, it) }
        (m["challengeType"] as? String)?.let { e.putString(K_CHALLENGE_TYPE, it) }
        (m["chOnHome"] as? Boolean)?.let { e.putBoolean(K_CH_ON_HOME, it) }
        (m["chOnLaunch"] as? Boolean)?.let { e.putBoolean(K_CH_ON_LAUNCH, it) }
        (m["chOnBack"] as? Boolean)?.let { e.putBoolean(K_CH_ON_BACK, it) }
        (m["allowed"] as? List<String>)?.let { e.putStringSet(K_ALLOWED, it.toSet()) }
        (m["dailyLimitMin"] as? Number)?.let { e.putInt(K_DAILY_LIMIT_MIN, it.toInt()) }
        (m["graceMin"] as? Number)?.let { e.putInt(K_GRACE_MIN, it.toInt()) }
        (m["openLimit"] as? Number)?.let { e.putInt(K_OPEN_LIMIT, it.toInt()) }
        (m["guardEnabled"] as? Boolean)?.let { e.putBoolean(K_GUARD_ENABLED, it) }
        e.apply()
    }
}
