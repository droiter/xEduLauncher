package com.ccbridge.child_launcher

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FlutterActivity() {

    private var channel: MethodChannel? = null

    /**
     * 本 Activity 是被「按 Home / 开机」的 intent 拉起来时的判定结果（是否要弹挑战框，以及依据）。
     * 判定必须趁 onCreate 做——那时 Activity 还没进前台，看到的正是「这一下之前」的现场；
     * Dart 稍后才来调 config 取走它（见 dispatch "config"）。
     */
    private var pendingHome: Pair<Boolean, String>? = null

    /**
     * 桌面这一次「露面」是否已经判过（见 [judgeAppearance]）。露面 = 桌面重新出现在最前面，
     * 包括从应用里按返回键退出来（系统连 intent 都不发，只有 onResume 这条路能看见）。
     * 判过就不再判：孩子站在桌面上按 Home、系统把桌面重新拉起来时，不该把上一次用过的应用
     * 翻出来再弹一道题（1.0.10 真机反馈：按返回键回到桌面后，再按 Home 又弹一次对话框）。
     */
    private var appearanceJudged = false

    /** 这一次露面是不是「按 Home 键」造成的（onNewIntent 记下，onResume 用掉） */
    private var viaHomeIntent = false

    /** 本 Activity 此刻是否在前台。只作为判定的兜底依据之一 */
    private var inForeground = false

    /** 上一次观察到的默认桌面状态，只在变化时写日志，免得刷屏 */
    private var lastDefault: Boolean? = null

    /** 角色弹框发起的时刻（elapsedRealtime），0 表示当前没有进行中的尝试 */
    private var roleAskedAt = 0L

    /** 上一次「默认桌面」尝试的结局，供自检报告引用 */
    private var lastHomeOutcome: String? = null

    /** 上一次「回到桌面」的判定现场，供自检报告引用 */
    private var lastHomeDecision: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.attach(this)
        // Activity 被 ROM 重建、或进程被杀后按 Home 冷启动，intent 里都带着 CATEGORY_HOME。
        // 这两种都可能是「孩子本来就站在桌面上」，也可能是「他从别的应用按 Home 逃回来」，
        // 所以照常判一次（判据见 judgeAppearance），结果留给 Dart 的 config 取走。
        val fromHome = intent?.categories?.contains(Intent.CATEGORY_HOME) == true
        viaHomeIntent = fromHome
        // 这一次露面由这里一并判掉：紧接着的 onResume 不再重判（同一个现场判两次会弹两个框）
        appearanceJudged = true
        pendingHome = if (fromHome) judgeAppearance() else null
        Diag.log(
            "act",
            "onCreate action=${intent?.action ?: "-"} " +
                "categories=${intent?.categories?.joinToString("|") ?: "-"} " +
                "home=$fromHome 判定=${pendingHome?.second ?: "-"}",
        )
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val ch = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel = ch
        ch.setMethodCallHandler { call, result -> handle(call.method, call.arguments, result) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isHome = intent.categories?.contains(Intent.CATEGORY_HOME) == true
        if (!isHome) {
            setIntent(intent)
            return
        }
        // 特意不把带 CATEGORY_HOME 的 intent 存下来：存了之后 Activity 一旦被重建，
        // 那次重建就会拿着这份旧 intent 再判一次（见上面那句 setIntent 只对非 HOME 生效）。
        //
        // 判定本身不在这里做，留给紧接着的 onResume：那里的现场一模一样（onNewIntent 早于 onResume，
        // 读到的仍是「这一下之前」的状态），而且能把「按返回键退出应用」那次一并判掉——
        // 那次系统根本不发 intent，只有 onResume 这条路看得见。这里只记一笔「这一下是 Home」，
        // 好在判定和日志里说清楚是哪一种。
        viaHomeIntent = true
    }

    private fun fg(): String? = Store.currentForeground(this)

    /**
     * 桌面这一次露面该不该弹挑战框。默认不弹——桌面是孩子的家，他站在家里按 Home 什么都不该发生。
     * 只有拿到「他是从别处回来的」的正面证据才弹，证据按可靠程度排：
     *
     *  ① 无障碍守护记的窗口链（最结实）：桌面露头之前最前面的是谁。是孩子白名单里的应用，
     *     就是他刚从那个应用里退出来——按 Home 也好、按返回键退出应用也好，在这儿看是一回事；
     *     是本应用自己的页面（密码页、乘法挑战页、同意页），那就不是从应用里逃出来的；
     *  ② 兜底（家长没开无障碍时）：最后露头的是别的应用 / 桌面这次回来前离开过屏幕 /
     *     Activity 已经离开前台一段时间了。
     *
     * 兜底那几条都要求证据「放了一会儿」：这一下露面本身就会把桌面拉到最前、把 Activity
     * 暂停/重建，那些变化都在毫秒之前，是这一下自己造成的，不算数——[FRESH_MS] 就是这条线。
     */
    private fun judgeAppearance(): Pair<Boolean, String> {
        val now = SystemClock.elapsedRealtime()
        val self = Store.selfForegroundAt(this)
        val other = Store.otherForegroundAt(this)
        val front = Store.currentForeground(this) ?: "-"
        val before = GuardAccessibilityService.beforeLauncher(packageName)
        val allowedList = Store.allowed(this)
        val leftScreen = leftScreenAt
        val leftFg = if (leftForegroundAt == 0L) -1L else now - leftForegroundAt
        val viaHome = viaHomeIntent

        val verdict = when {
            // 守护自己刚把他弹回来的那次（孩子开了非白名单应用）不算他按的 Home，拦回桌面就完事
            Store.guardBounceRecent(this) -> false to "守护刚把他弹回桌面"
            // 家长刚从系统设置那趟回来（放行还没收回）：这一下是他自己按的 Home，
            // 不该让他再做一道题，否则家长外出办事回来还得答题才能用桌面
            Store.parentFreeActive(this) -> false to "家长刚在放行期里（去过系统设置之类）"
            // 「」= 无障碍看见桌面之前是本应用自己的页面（密码页/乘法挑战页/同意页盖在上面），
            // 那不是从应用里逃出来的——这条要排在兜底证据前面，否则锁屏页一关就误判成逃回桌面
            before != null && before.isEmpty() -> false to "刚才盖在上面的是本应用自己的页面"
            before != null && before in allowedList ->
                true to "无障碍看见桌面之前是 $before（孩子白名单里的应用）"
            other > self -> true to "最后露头的是别的应用（$front，${ago(now, other)}前）"
            leftScreen != 0L && now - leftScreen >= FRESH_MS ->
                true to "桌面这次回来前离开过屏幕（${ago(now, leftScreen)}前）"
            leftFg >= FRESH_MS -> true to "Activity 已离开前台 ${leftFg}ms"
            else -> false to "没拿到「从别处回来」的证据（最前=$front，桌面之前=${before ?: "（看不出）"}，" +
                "离开前台 ${leftFg}ms）"
        }
        Diag.log(
            "home",
            "露面判定（${if (viaHome) "按 Home 键" else "没有 Home intent（按返回键退出应用之类）"}）：" +
                "桌面最后在前 ${ago(now, self)}前、别的应用 ${ago(now, other)}前、" +
                "桌面之前=${before ?: "（看不出）"}、leftScreen=${ago(now, leftScreen)}前、" +
                "leftFg=${leftFg}ms、最前=$front → ${if (verdict.first) "弹挑战" else "不打扰"}",
        )
        lastHomeDecision =
            "桌面这次露面：${verdict.second} → ${if (verdict.first) "弹挑战" else "不打扰"}"
        consumeHomeEvidence()
        return verdict
    }

    /**
     * 判定即消费：这一次露面已经有结论了，现场立刻归位成「他此刻就站在桌面上」。
     *
     * 不消费的话同一批证据会一直挂着——孩子答对题回到桌面后，每按一次 Home 都会再弹一道
     * （1.0.9 真机反馈：在别的应用按 home 答对后，以后按 home 都弹对话框）。证据全部作废，
     * 并把「桌面此刻在最前面」记下来，下一次判定自然就是「他站在桌面上」。
     */
    private fun consumeHomeEvidence() {
        leftScreenAt = 0L
        leftForegroundAt = 0L
        Store.noteForeground(this, packageName, countAsApp = false)
    }

    /** 「多久以前」的可读文本，给日志用 */
    private fun ago(now: Long, at: Long): String =
        if (at == 0L) "（从没记过）" else "${now - at}ms"

    override fun onResume() {
        super.onResume()
        inForeground = true
        // 桌面这一次露面要不要弹挑战框。判定必须赶在下面清现场之前——那时读到的才是
        // 「这一次露面之前」的状态。每一次露面只判一次：孩子按 Home 让系统把桌面重新拉起来、
        // 可桌面本来就在最前面时（没有 onPause/onResume 那一轮）根本走不到这里，
        // 系统真把它重新拉起来的那种也已经在这一次判过了，不会再翻出上一次用过的应用弹题
        val escape = if (appearanceJudged) null else judgeAppearance()
        appearanceJudged = true
        val viaHome = viaHomeIntent
        viaHomeIntent = false
        // 桌面又到前台了：之前那份「他离开过屏幕 / Activity 离开前台」的证据一律作废。
        // 他是从这个状态开始待在桌面上的，接下来再按 Home 就是「站在桌面上按的」。
        leftScreenAt = 0L
        leftForegroundAt = 0L
        // 上面这两条一律清掉是安全的：该不该弹挑战框在上面就判完了，读到的还是
        // 「这一次露面之前」的现场，不会被这里的归零抹掉
        // 桌面此刻确实在最前面，自己记一笔：不依赖无障碍服务有没有把这一下报上来
        Store.noteForeground(this, packageName, countAsApp = false)
        if (escape != null && escape.first) {
            // 从别处逃回桌面。按 Home 的那次走 onHomeKey（老路），
            // 按返回键退出应用的那次系统连 intent 都不发，走 onBackEscape
            if (viaHome) channel?.invokeMethod("onHomeKey", null)
            else channel?.invokeMethod("onBackEscape", null)
        }
        val nowDefault = Store.isDefaultLauncher(this)
        if (nowDefault != lastDefault) {
            Diag.log(
                "home",
                "默认桌面状态 ${lastDefault ?: "（首次）"} → $nowDefault，当前系统桌面=${defaultLauncherLabel()}",
            )
            lastDefault = nowDefault
        }
        Store.onLauncherResume(this)
        // 桌面在前台了 = 孩子离开了刚才那个应用，单次时长的会话到此为止
        GuardAccessibilityService.onDesktopShown()
        // 家长已经从系统设置那边回来了，前台守护立刻恢复管控
        Store.clearParentFree(this)
        // 超时 / 超次数 → 拉起密码锁屏
        Store.gateReason(this)?.let {
            Diag.log("gate", "命中限制 $it，拉起密码页")
            Store.showLock(this, it)
        }
        // 有设备在等「同意」而同意页当时没能弹出来（后台启动被系统拦了），这里补一次
        HttpGateway.onLauncherResume(this)
        // 进程可能是被系统重启的，服务该开没开的话在这里补上
        applyFileServer()
    }

    override fun onPause() {
        inForeground = false
        leftForegroundAt = SystemClock.elapsedRealtime()
        // 桌面要离开前台了：下一次回到最前面算一次新露面，得重新判（见 judgeAppearance）。
        // 孩子站在桌面上按 Home 不经过这里，所以那一下不会被当成一次新露面
        appearanceJudged = false
        super.onPause()
    }

    override fun onStop() {
        // 桌面被别的应用整个盖住了（不只是被弹框遮一下）。再回到桌面时，
        // 这就是「他刚从别处回来」的硬证据；自己切回来那次会在 onResume 里作废
        leftScreenAt = SystemClock.elapsedRealtime()
        super.onStop()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_HOME_ROLE) return

        val ms = if (roleAskedAt == 0L) -1L else SystemClock.elapsedRealtime() - roleAskedAt
        roleAskedAt = 0L
        val isDefault = Store.isDefaultLauncher(this)
        Diag.log(
            "home",
            "角色弹框返回 resultCode=$resultCode，用时 ${ms}ms，现在默认桌面=$isDefault",
        )

        if (resultCode == RESULT_OK || isDefault) {
            lastHomeOutcome = "成功（resultCode=$resultCode，${ms}ms）"
            notifyHome(mapOf("code" to "already", "detail" to "已成功设为默认桌面", "ms" to ms))
            return
        }

        // 角色弹框是 Dialog 主题，真人从头看完再点按钮不可能 1 秒内回来。
        // 秒回只有一种解释：系统压根没把弹框渲染出来，直接把这个角色请求拒了
        // （荣耀 MagicOS 的官方政策就是禁止第三方桌面，见自检报告里的 ROM 字段）。
        val instant = ms in 0..1200
        val outcome =
            if (instant) "被系统秒拒（${ms}ms，弹框没渲染出来）" else "家长自己取消了（${ms}ms）"
        Diag.log("home", "角色未授予：$outcome")

        if (!instant) {
            lastHomeOutcome = outcome
            notifyHome(mapOf("code" to "role_canceled", "detail" to outcome, "ms" to ms))
            return
        }

        // 家长什么都没看到就被拒了，必须给个去处：直接打开系统的桌面/默认应用设置页
        val fb = openHomeSettingsPage()
        lastHomeOutcome = "$outcome；已兜底打开 ${fb["detail"]}"
        notifyHome(
            mapOf(
                "code" to "role_blocked",
                "detail" to lastHomeOutcome as String,
                "ms" to ms,
                "fallback" to fb["code"] as String,
            )
        )
    }

    /** 把「默认桌面」尝试的结局回报给 Dart——它在系统弹框挡着的时候是收不到的 */
    private fun notifyHome(payload: Map<String, Any>) {
        try {
            channel?.invokeMethod("onHomeResult", HashMap(payload))
        } catch (e: Exception) {
            Diag.log("home", "回报 onHomeResult 失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onDestroy() {
        Diag.log("act", "onDestroy")
        channel = null
        super.onDestroy()
    }

    // ---------- MethodChannel 分发 ----------

    private fun handle(method: String, args: Any?, result: MethodChannel.Result) {
        try {
            dispatch(method, args, result)
        } catch (e: Exception) {
            // 不吞：原样抛回 Dart，让它能在界面上显示出来，而不是永远 await 下去
            Diag.log("err", "$method 抛异常：${e.javaClass.simpleName}: ${e.message}")
            try {
                result.error("native_error", "${e.javaClass.simpleName}: ${e.message}", null)
            } catch (_: Exception) {
                // result 已经回复过了
            }
        }
    }

    private fun dispatch(method: String, args: Any?, result: MethodChannel.Result) {
        when (method) {
            "config" -> {
                val m = HashMap<String, Any>(Store.configMap(this))
                // onCreate 那次「按 Home / 开机把桌面拉起来」的判定（冷启动与 Activity 重建都走这里）。
                // 判据、日志、lastHomeDecision 都已经由 judgeAppearance 处理过，这里只把它交给 Dart：
                // Dart 侧据此决定要不要在界面起来后补一个挑战框
                val home = pendingHome
                pendingHome = null
                m["coldStartHome"] = home?.first == true
                result.success(m)
            }
            @Suppress("UNCHECKED_CAST")
            "updateConfig" -> {
                Store.applyConfig(this, args as Map<String, Any?>)
                applyGuardState()
                applyFileServer()
                result.success(Store.configMap(this))
            }
            "listApps" -> result.success(listApps())
            @Suppress("UNCHECKED_CAST")
            "appIcons" -> result.success(appIcons(args as List<String>))
            "launchApp" -> result.success(launchApp(args as String))
            "returnToLastApp" -> result.success(returnToLastApp())
            "openHomeSettings" -> result.success(requestDefaultHome())
            "launcherDiag" -> {
                Diag.log("diag", "家长打开了桌面自检")
                result.success(launcherDiag())
            }
            "openSystemSettings" -> {
                leaveLauncherFor(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "系统设置",
                )
                result.success(true)
            }
            "openAccessibilitySettings" -> {
                leaveLauncherFor(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "无障碍设置",
                )
                result.success(true)
            }
            "requestOverlay" -> {
                leaveLauncherFor(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "悬浮窗权限页",
                )
                result.success(true)
            }
            "requestNotification" -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                    )
                }
                result.success(true)
            }
            "verifyPassword" -> result.success(args == Store.password(this))
            "verifySettingsPassword" -> result.success(args == Store.settingsPassword(this))
            "showLock" -> {
                Store.showLock(this, args as? String ?: "time")
                result.success(true)
            }
            "resetStats" -> {
                Store.resetStats(this)
                result.success(Store.configMap(this))
            }
            "setGuard" -> {
                Store.applyConfig(this, mapOf("guardEnabled" to (args as Boolean)))
                applyGuardState()
                result.success(Store.configMap(this))
            }
            "setFileServer" -> {
                Store.applyConfig(this, mapOf("fileServerOn" to (args as Boolean)))
                applyFileServer()
                result.success(Store.configMap(this))
            }
            // Dart 侧的关键决策也写进同一份日志文件，事后一起下载下来看
            "diagLog" -> {
                Diag.log("dart", args as? String ?: "")
                result.success(true)
            }
            "defaultLauncherName" -> result.success(defaultLauncherLabel())
            else -> result.notImplemented()
        }
    }

    /**
     * 家长（输过第二个密码）要离开桌面去系统页办事：先给前台守护开一段放行时间，
     * 否则刚打开设置页就会被无障碍服务弹回桌面。回到桌面时自动收回。
     */
    private fun leaveLauncherFor(intent: Intent, what: String) {
        Store.grantParentFree(this)
        Diag.log("act", "家长外出去$what，前台守护暂让路")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Diag.log("act", "打开$what 失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun applyGuardState() {
        val i = Intent(this, GuardService::class.java)
        if (Store.guardEnabled(this)) {
            ContextCompat.startForegroundService(this, i)
        } else {
            stopService(i)
        }
    }

    /** 文件传输服务的开关同样落到服务上：开着就拉起前台服务，关掉就停 */
    private fun applyFileServer() {
        val i = Intent(this, FileServerService::class.java)
        if (Store.fileServerOn(this)) {
            try {
                ContextCompat.startForegroundService(this, i)
            } catch (e: Exception) {
                Diag.log("http", "拉起文件传输服务失败：${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            stopService(i)
        }
    }

    // ---------- 应用列表与启动 ----------

    private fun query(intent: Intent, flags: Int): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, flags)
        }

    private fun resolve(intent: Intent): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }

    private fun launchableIntent() =
        Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

    private fun homeIntent() =
        Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_HOME) }

    private fun listApps(): List<Map<String, Any>> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<Map<String, Any>>()
        for (ri in query(launchableIntent(), 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) continue
            out.add(
                mapOf(
                    "package" to pkg,
                    "label" to ri.loadLabel(pm).toString(),
                )
            )
        }
        out.sortBy { it["label"] as String }
        return out
    }

    private fun launchApp(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(i); true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 按 Home 键的挑战没答对（答错后关掉、或按了取消）：把孩子送回他刚才在用的那个应用，
     * 桌面得答对才进得去。「刚才在用哪个」由前台守护记着；记不到、或者那个应用已经不在白名单
     * （送回去也会被守护立刻弹回来），就只能让他留在桌面。
     */
    private fun returnToLastApp(): Boolean {
        val pkg = Store.lastForeign(this)
        if (pkg == null) {
            Diag.log("home", "挑战没过，但没有「刚才在用哪个应用」的记录，只能留在桌面")
            return false
        }
        if (Store.frontGuard(this) && pkg !in Store.allowed(this)) {
            Diag.log("home", "挑战没过，但 $pkg 已不在白名单，送回去也会被守护弹回来，留在桌面")
            return false
        }
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            Diag.log("home", "挑战没过，但 $pkg 没有启动入口（已卸载？），留在桌面")
            return false
        }
        // 不带 RESET_TASK_IF_NEEDED：要的是把他放回原来那一屏，不是重启这个应用
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            Diag.log("home", "挑战没过，把孩子送回 $pkg")
            true
        } catch (e: Exception) {
            Diag.log("home", "送回 $pkg 失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ---------- 应用图标 ----------

    /** 编码好的图标按包名缓存；null = 这个包确实取不到图标，记住免得每次重问 */
    private val iconCache = HashMap<String, ByteArray?>()

    /**
     * 一批包名的图标（PNG 字节），只处理没缓存过的。桌面每次回前台都要刷新一遍列表，
     * 所以这里不跟着 [listApps] 一起给全部应用——Dart 侧只要白名单那几个，列表页要的时候才全要。
     */
    private fun appIcons(packages: List<String>): Map<String, ByteArray> {
        if (packages.any { !iconCache.containsKey(it) }) {
            val byPkg = HashMap<String, ResolveInfo>()
            for (ri in query(launchableIntent(), 0)) {
                byPkg.putIfAbsent(ri.activityInfo.packageName, ri)
            }
            for (pkg in packages) {
                if (iconCache.containsKey(pkg)) continue
                iconCache[pkg] = try {
                    byPkg[pkg]?.let { encodePng(it.loadIcon(packageManager)) }
                } catch (e: Exception) {
                    Diag.log("icon", "取 $pkg 的图标失败：${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
        }
        val out = HashMap<String, ByteArray>()
        for (pkg in packages) iconCache[pkg]?.let { out[pkg] = it }
        return out
    }

    /**
     * 把图标画进正方形位图再压成 PNG。自适应图标也走这条路：Drawable 自己按系统遮罩绘制，
     * 缩到 [ICON_PX] 之后一张只剩几 KB，可以直接塞进 MethodChannel。
     */
    private fun encodePng(icon: Drawable): ByteArray {
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, ICON_PX, ICON_PX)
        icon.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ---------- 默认桌面 ----------

    /**
     * 成为默认桌面。Android 10+ 优先走 RoleManager——它由系统弹出「是否设为默认桌面」
     * 对话框，不依赖各家 ROM 设置页里那份（可能被裁剪过的）列表。
     * 返回 {code, detail}，code ∈ role / already / settings / none，detail 是这一步的现场。
     * 每一步都写进 Diag，家长遇到「点了没反应」时可以从自检里拷出来。
     */
    private fun requestDefaultHome(): Map<String, Any> {
        Diag.log("home", "———— 家长点了「默认桌面」————")
        if (Store.isDefaultLauncher(this)) {
            Diag.log("home", "判定已经是默认桌面，不弹任何框")
            lastHomeOutcome = "本来就是默认桌面"
            return mapOf("code" to "already", "detail" to "系统已把本应用解析为 Home")
        }
        Diag.log("home", "当前不是默认桌面，系统桌面=${defaultLauncherLabel()}")

        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm == null) {
                Diag.log("home", "拿不到 RoleManager（系统服务缺失），改走设置页")
            } else {
                val available = rm.isRoleAvailable(RoleManager.ROLE_HOME)
                val held = rm.isRoleHeld(RoleManager.ROLE_HOME)
                Diag.log("home", "ROLE_HOME available=$available held=$held")
                if (available && !held) {
                    val request = try {
                        rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    } catch (e: Exception) {
                        Diag.log("home", "createRequestRoleIntent 抛异常：${e.javaClass.simpleName}: ${e.message}")
                        null
                    }
                    val who = request?.let { componentOf(it) }
                    Diag.log("home", "角色弹框 intent 解析到：${who ?: "（没有任何 Activity 能处理）"}")
                    if (request != null && who != null) {
                        try {
                            // 角色弹框由系统另一个包渲染，前台守护要放行，否则家长刚点就被弹回桌面
                            Store.grantParentFree(this)
                            roleAskedAt = SystemClock.elapsedRealtime()
                            startActivityForResult(request, REQ_HOME_ROLE)
                            Diag.log("home", "已 startActivityForResult，等系统弹框（$who）")
                            return mapOf("code" to "role", "detail" to who)
                        } catch (e: Exception) {
                            Diag.log("home", "startActivityForResult 失败：${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
            }
        } else {
            Diag.log("home", "API ${Build.VERSION.SDK_INT} < 29，系统没有 ROLE_HOME，直接走设置页")
        }
        return openHomeSettingsPage()
    }

    /** 依次尝试几个系统设置入口，全部不可用才返回 none */
    private fun openHomeSettingsPage(): Map<String, Any> {
        lastHomeOutcome = "没走成角色弹框，改为打开系统设置页"
        val pages = listOf(
            "ACTION_HOME_SETTINGS" to Intent(Settings.ACTION_HOME_SETTINGS),
            "ACTION_MANAGE_DEFAULT_APPS_SETTINGS" to Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            "ACTION_SETTINGS" to Intent(Settings.ACTION_SETTINGS),
        )
        for ((label, i) in pages) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val who = componentOf(i)
            if (who == null) {
                Diag.log("home", "$label 没有 Activity 能处理，跳过")
                continue
            }
            try {
                Store.grantParentFree(this)
                startActivity(i)
                Diag.log("home", "$label 已打开 $who")
                return mapOf("code" to "settings", "detail" to "$label → $who")
            } catch (e: Exception) {
                Diag.log("home", "$label 解析到 $who 但打开失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Diag.log("home", "三个设置入口全不可用 → none")
        return mapOf("code" to "none", "detail" to "三个系统设置入口都打不开")
    }

    /** "包名/Activity名"，查不到返回 null */
    private fun componentOf(i: Intent): String? = try {
        resolve(i)?.let { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
    } catch (e: Exception) {
        Diag.log("home", "解析 ${i.action} 时异常：${e.javaClass.simpleName}: ${e.message}")
        null
    }

    // ---------- 桌面自检报告 ----------

    private fun launcherDiag(): String {
        val pm = packageManager
        val home = homeIntent()
        // Settings 的「默认应用」页用的就是 MATCH_DEFAULT_ONLY 这一份结果
        val defaultOnly = query(home, PackageManager.MATCH_DEFAULT_ONLY)
        val allHome = query(home, 0)
        val selfDefaultOnly = defaultOnly.any { it.activityInfo.packageName == packageName }
        val selfAll = allHome.any { it.activityInfo.packageName == packageName }

        fun render(list: List<ResolveInfo>): String =
            if (list.isEmpty()) "  （空）"
            else list.joinToString("\n") { ri ->
                val ai = ri.activityInfo
                val mark = if (ai.packageName == packageName) "  ← 本应用" else ""
                "  · ${ai.packageName}/${ai.name}（${ri.loadLabel(pm)}）enabled=${ai.enabled}$mark"
            }

        fun page(i: Intent): String = componentOf(i) ?: "否（没有 Activity 能处理）"

        val enabled = try {
            pm.getPackageInfo(packageName, 0).applicationInfo?.enabled?.toString() ?: "?"
        } catch (_: Exception) {
            "?"
        }

        val sb = StringBuilder()
        sb.appendLine("═══ 儿童桌面 桌面自检 ═══")
        sb.appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine(Diag.env(this))
        sb.appendLine("应用已启用：$enabled")
        sb.appendLine()
        sb.appendLine("── 默认桌面现状 ──")
        sb.appendLine("本应用是默认桌面：${Store.isDefaultLauncher(this)}")
        sb.appendLine("系统当前解析到的桌面：${page(home)}")
        sb.appendLine()
        sb.appendLine("── 系统认不认本应用是桌面候选 ──")
        sb.appendLine("带 DEFAULT 过滤（系统「默认应用」列表就是按这个查的）：${if (selfDefaultOnly) "认 ✓" else "不认 ✗"}")
        sb.appendLine("不带过滤（能查到只说明装上了并声明了 HOME）：${if (selfAll) "认 ✓" else "不认 ✗"}")
        sb.appendLine()
        sb.appendLine("── 系统上所有桌面（带 DEFAULT 过滤）──")
        sb.appendLine(render(defaultOnly))
        sb.appendLine("── 系统上所有桌面（不带过滤）──")
        sb.appendLine(render(allHome))
        sb.appendLine()
        sb.appendLine("── 设置入口可用性 ──")
        sb.appendLine("系统「主屏幕应用」页：${page(Intent(Settings.ACTION_HOME_SETTINGS))}")
        sb.appendLine("系统「默认应用」页：${page(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))}")
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            sb.appendLine("ROLE_HOME 可用：${rm?.isRoleAvailable(RoleManager.ROLE_HOME) ?: false}")
            sb.appendLine("ROLE_HOME 已持有：${rm?.isRoleHeld(RoleManager.ROLE_HOME) ?: false}")
            val request = try {
                rm?.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } catch (_: Exception) {
                null
            }
            sb.appendLine(
                "角色弹框 Intent 解析到（只说明有这个 Activity，不代表系统真会弹）：" +
                    if (request == null) "否（没有 Activity 能处理）" else page(request)
            )
        } else {
            sb.appendLine("API ${Build.VERSION.SDK_INT} < 29：系统不提供 ROLE_HOME")
        }
        sb.appendLine("上次「默认桌面」尝试：${lastHomeOutcome ?: "（本次运行还没点过）"}")
        sb.appendLine()
        sb.appendLine("── 前台守护（拦非白名单应用 + 拒绝任务键）──")
        sb.appendLine("开关已打开：${Store.frontGuard(this)}")
        sb.appendLine("无障碍服务已在系统里启用：${Store.accessibilityOn(this)}")
        sb.appendLine("家长放行中（暂不拦截）：${Store.parentFreeActive(this)}")
        sb.appendLine("放行时长设置：${Store.settingsFreeMin(this)} 分钟")
        sb.appendLine("非白名单应用一露头就被送回桌面；「最近任务」那一屏直接退掉，孩子留在当前应用")
        sb.appendLine("放行的系统「选文件」界面（孩子从应用里点「选视频」必经这一屏，不当换应用处理）：")
        sb.append(GuardAccessibilityService.pickerReport())
        sb.appendLine("最近几次拦截见下面日志里的 [guard] 行")
        sb.appendLine()
        sb.appendLine("── 单次使用时长（每个白名单应用各算一次）──")
        sb.append(GuardAccessibilityService.sessionReport(this))
        sb.appendLine()
        sb.appendLine("── 从应用回到桌面的判定现场（按 Home 键 / 按返回键退出应用都算）──")
        sb.appendLine("挑战开关：回到桌面时（从别的应用逃回来）${Store.challengeOnHome(this)} / 启动应用 ${Store.challengeOnLaunch(this)}")
        sb.appendLine("无障碍看到的当前前台：${fg() ?: "（还没记录）"}")
        sb.appendLine("桌面自己最后一次在最前面：${agoOf(Store.selfForegroundAt(this))}")
        sb.appendLine("别的应用最后一次在最前面：${agoOf(Store.otherForegroundAt(this))}")
        sb.appendLine("回到桌面挑战没过会送回的应用：${Store.lastForeign(this) ?: "（还没记录）"}")
        sb.appendLine("上一次「桌面露面」的判定：${lastHomeDecision ?: "（本次运行还没回到过桌面）"}")
        sb.appendLine(
            "桌面离开屏幕 / 离开前台的记录：leftScreen=${agoOf(leftScreenAt)}、leftFg=${agoOf(leftForegroundAt)}" +
                "（每次判定都会消费掉，消费后显示「还没记录」是正常的）"
        )
        sb.appendLine("【最结实的那路证据】无障碍记的窗口链：")
        sb.append(GuardAccessibilityService.frontReport(this))
        sb.appendLine()
        sb.appendLine("── 白名单（★ = 点开直接进，不弹挑战）──")
        val noCh = Store.noChallenge(this)
        val allowedList = Store.allowed(this)
        sb.appendLine(
            if (allowedList.isEmpty()) "  （空，孩子只能看到家长设置）"
            else allowedList.joinToString("\n") { p -> (if (p in noCh) "  ★ " else "  · ") + p }
        )
        sb.appendLine()
        sb.appendLine("── 文件传输（浏览器连本机下载日志 / 上传文件）──")
        sb.append(HttpGateway.report(this))
        sb.appendLine()
        sb.appendLine("── 运行日志（共 ${Diag.size()} 条，从旧到新）──")
        sb.append(Diag.dump())

        // 同一份报告也存成文件：家长光看这一屏不方便，开了文件传输就能用浏览器下下来发给我
        val text = sb.toString()
        val saved = Diag.writeReport(this, text)
        if (saved == null) return text
        return text +
            "\n\n═══ 这一份已存成文件 ═══\n${saved.absolutePath}\n" +
            "运行日志同时追加在：${Diag.logFile(this).absolutePath}\n" +
            "在「文件传输」里用浏览器打开 ${HttpGateway.urlOrEmpty(this).ifEmpty { "（服务还没开）" }} 就能下载。\n"
    }

    /** 「多久以前」的可读文本，0 = 从没记过 */
    private fun agoOf(at: Long): String {
        if (at == 0L) return "（还没记录）"
        val s = (SystemClock.elapsedRealtime() - at) / 1000
        return when {
            s < 0 -> "（时钟比记录还早，重启过？）"
            s < 60 -> "${s} 秒前"
            s < 3600 -> "${s / 60} 分钟前"
            else -> "${s / 3600} 小时前"
        }
    }

    private fun defaultLauncherLabel(): String =
        resolve(homeIntent())?.loadLabel(packageManager)?.toString() ?: "未知"

    companion object {
        const val CHANNEL = "child_launcher/native"
        private const val REQ_HOME_ROLE = 201

        /**
         * 「这一下按 Home 造成的现场变化」都在这么新以内，不作数。按 Home 会把桌面拉到最前、
         * 把 Activity 暂停/重建，这些变化就发生在毫秒前；只有比这更早就成立的状态才算「他本来就在桌面上」。
         */
        private const val FRESH_MS = 1200L

        /** 图标统一编码成这么大，够桌面磁贴用，又不至于把通道塞爆 */
        private const val ICON_PX = 128

        /** Activity 最后一次离开前台的时刻。进程级：Activity 被重建时，那是上一个实例留下的 */
        private var leftForegroundAt = 0L

        /** 桌面这次回到最前面之前真的离开过屏幕（onStop）的时刻，0 = 没有 */
        private var leftScreenAt = 0L
    }
}
