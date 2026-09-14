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

    /** 本进程是否为「按 Home 键/开机进入桌面」而冷启动（进程内 Activity 重建不算，见 onCreate） */
    private var launchedAsHome = false

    /** 本 Activity 此刻是否在前台。按 Home 时靠它区分「孩子从别的应用逃回桌面」和「本来就站在桌面上按的」 */
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
        // 只有本进程第一次创建 Activity 才算「按 Home 冷启动」。同一个进程里 Activity 被重建
        // （ROM 重排桌面、系统回收后再拉起）时 intent 里照样带着 CATEGORY_HOME，孩子明明就站在
        // 桌面上，却会被当成「刚从别的应用逃回来」——那就是误弹的挑战框。
        val cold = !processStarted
        processStarted = true
        launchedAsHome = cold && intent?.categories?.contains(Intent.CATEGORY_HOME) == true
        Diag.log(
            "act",
            "onCreate action=${intent?.action ?: "-"} " +
                "categories=${intent?.categories?.joinToString("|") ?: "-"} " +
                "冷启动=$cold home=$launchedAsHome",
        )
        super.onCreate(savedInstanceState)
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
        // 特意不把带 CATEGORY_HOME 的 intent 存下来：存了之后 Activity 一旦被重建，
        // onCreate 就会把那次重建误判成「按 Home 冷启动」，又给站在桌面上的孩子弹一道题
        if (!isHome) setIntent(intent)
        // 已经在桌面上时按 Home，只是系统再叫一次桌面，不该拿挑战框去打扰孩子；
        // 只有这一下之前桌面在后台（孩子从别的应用逃回来）才弹，那才是这个开关要拦的。
        // 守护自己弹回来的那次（孩子开了非白名单应用）也不算，拦回桌面就完事，别再加一道题
        val front = desktopInFront()
        val bounced = Store.guardBounceRecent(this)
        val escape = isHome && !front && !bounced
        lastHomeDecision =
            "onNewIntent home=$isHome 桌面已在前台=$front 守护刚弹回=$bounced " +
                "→ ${if (escape) "弹挑战" else "不打扰"}"
        Diag.log("act", "$lastHomeDecision （Activity=$inForeground 无障碍看到的当前前台=${fg() ?: "-"}）")
        if (escape) channel?.invokeMethod("onHomeKey", null)
    }

    private fun fg(): String? = Store.currentForeground(this)

    /**
     * 「这一下回到桌面」之前，桌面是不是本来就在最前面——是就别弹挑战框。
     * Activity 自己的前后台状态之外，再看一眼无障碍守护记下的「最前面那个窗口是谁」：
     * 走冷启动路径、或 ROM 把桌面 Activity 重排重建时，前后台标志已经不可靠，这一路能兜住。
     */
    private fun desktopInFront(): Boolean = inForeground || desktopWasInFront()

    /** 只认无障碍那一路：冷启动时 inForeground 一定是 true（刚 onResume 过），单靠它会把冷启动全放过 */
    private fun desktopWasInFront(): Boolean =
        Store.accessibilityOn(this) && Store.currentForeground(this) == packageName

    override fun onResume() {
        super.onResume()
        inForeground = true
        val nowDefault = Store.isDefaultLauncher(this)
        if (nowDefault != lastDefault) {
            Diag.log(
                "home",
                "默认桌面状态 ${lastDefault ?: "（首次）"} → $nowDefault，当前系统桌面=${defaultLauncherLabel()}",
            )
            lastDefault = nowDefault
        }
        Store.onLauncherResume(this)
        // 家长已经从系统设置那边回来了，前台守护立刻恢复管控
        Store.clearParentFree(this)
        // 超时 / 超次数 → 拉起密码锁屏
        Store.gateReason(this)?.let {
            Diag.log("gate", "命中限制 $it，拉起密码页")
            Store.showLock(this, it)
        }
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
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
                // 冷启动也可能是守护弹回来的（进程被杀过），或者本来就是系统重排桌面——
                // 孩子一直站在桌面上，这两种都不该弹挑战框
                var coldHome = false
                if (launchedAsHome) {
                    val front = desktopWasInFront()
                    val bounced = Store.guardBounceRecent(this)
                    coldHome = !bounced && !front
                    lastHomeDecision =
                        "冷启动 桌面已在前台=$front 守护刚弹回=$bounced " +
                            "→ ${if (coldHome) "弹挑战" else "不打扰"}"
                    Diag.log("home", "$lastHomeDecision")
                }
                m["coldStartHome"] = coldHome
                launchedAsHome = false
                result.success(m)
            }
            @Suppress("UNCHECKED_CAST")
            "updateConfig" -> {
                Store.applyConfig(this, args as Map<String, Any?>)
                applyGuardState()
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
        sb.appendLine("无障碍看到的当前前台：${fg() ?: "（还没记录）"}")
        sb.appendLine("非白名单应用一露头就被送回桌面；「最近任务」那一屏直接退掉，孩子留在当前应用")
        sb.appendLine("最近几次拦截见下面日志里的 [guard] 行")
        sb.appendLine()
        sb.appendLine("── 白名单（★ = 点开直接进，不弹挑战）──")
        val noCh = Store.noChallenge(this)
        val allowedList = Store.allowed(this)
        sb.appendLine(
            if (allowedList.isEmpty()) "  （空，孩子只能看到家长设置）"
            else allowedList.joinToString("\n") { p -> (if (p in noCh) "  ★ " else "  · ") + p }
        )
        sb.appendLine("挑战总开关：按 Home（从别的应用逃回来时）${Store.challengeOnHome(this)} / 启动应用 ${Store.challengeOnLaunch(this)}")
        sb.appendLine("按 Home 挑战没过会送回的应用：${Store.lastForeign(this) ?: "（还没记录）"}")
        sb.appendLine("上一次「回到桌面」的判定：${lastHomeDecision ?: "（本次运行还没回到过桌面）"}")
        sb.appendLine()
        sb.appendLine("── 运行日志（共 ${Diag.size()} 条，从旧到新）──")
        sb.append(Diag.dump())
        return sb.toString()
    }

    private fun defaultLauncherLabel(): String =
        resolve(homeIntent())?.loadLabel(packageManager)?.toString() ?: "未知"

    companion object {
        const val CHANNEL = "child_launcher/native"
        private const val REQ_HOME_ROLE = 201

        /** 进程级：只有本进程第一次创建 Activity 才算「按 Home 冷启动」，之后重建都不算 */
        private var processStarted = false

        /** 图标统一编码成这么大，够桌面磁贴用，又不至于把通道塞爆 */
        private const val ICON_PX = 128
    }
}
