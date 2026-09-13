package com.ccbridge.child_launcher

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FlutterActivity() {

    private var channel: MethodChannel? = null

    /** 本次进程是否为「按 Home 键/开机进入桌面」而启动 */
    private var launchedAsHome = false

    /** 上一次观察到的默认桌面状态，只在变化时写日志，免得刷屏 */
    private var lastDefault: Boolean? = null

    /** 角色弹框发起的时刻（elapsedRealtime），0 表示当前没有进行中的尝试 */
    private var roleAskedAt = 0L

    /** 上一次「默认桌面」尝试的结局，供自检报告引用 */
    private var lastHomeOutcome: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        launchedAsHome = intent?.categories?.contains(Intent.CATEGORY_HOME) == true
        Diag.log(
            "act",
            "onCreate action=${intent?.action ?: "-"} " +
                "categories=${intent?.categories?.joinToString("|") ?: "-"} home=$launchedAsHome",
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
        setIntent(intent)
        val isHome = intent.categories?.contains(Intent.CATEGORY_HOME) == true
        Diag.log(
            "act",
            "onNewIntent categories=${intent.categories?.joinToString("|") ?: "-"} home=$isHome",
        )
        // 按 Home 键回到桌面时，通知 Flutter 弹挑战框
        if (isHome) channel?.invokeMethod("onHomeKey", null)
    }

    override fun onResume() {
        super.onResume()
        val nowDefault = Store.isDefaultLauncher(this)
        if (nowDefault != lastDefault) {
            Diag.log(
                "home",
                "默认桌面状态 ${lastDefault ?: "（首次）"} → $nowDefault，当前系统桌面=${defaultLauncherLabel()}",
            )
            lastDefault = nowDefault
        }
        Store.onLauncherResume(this)
        // 超时 / 超次数 → 拉起密码锁屏
        Store.gateReason(this)?.let {
            Diag.log("gate", "命中限制 $it，拉起密码页")
            Store.showLock(this, it)
        }
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
                m["coldStartHome"] = launchedAsHome
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
            "launchApp" -> result.success(launchApp(args as String))
            "openHomeSettings" -> result.success(requestDefaultHome())
            "launcherDiag" -> {
                Diag.log("diag", "家长打开了桌面自检")
                result.success(launcherDiag())
            }
            "openSystemSettings" -> {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                result.success(true)
            }
            "requestOverlay" -> {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        sb.appendLine("── 运行日志（共 ${Diag.size()} 条，从旧到新）──")
        sb.append(Diag.dump())
        return sb.toString()
    }

    private fun defaultLauncherLabel(): String =
        resolve(homeIntent())?.loadLabel(packageManager)?.toString() ?: "未知"

    companion object {
        const val CHANNEL = "child_launcher/native"
        private const val REQ_HOME_ROLE = 201
    }
}
