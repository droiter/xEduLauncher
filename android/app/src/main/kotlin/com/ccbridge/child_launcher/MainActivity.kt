package com.ccbridge.child_launcher

import android.Manifest
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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

    /**
     * 上一次离开前台，是不是被本应用自己的**家长页面**（密码页、同意页）盖住的。
     *
     * 这两个页面一关，人就直接回到桌面，那不是「他从应用里逃回来」。光靠无障碍窗口链
     * 看不出这件事：密码页一开就抢焦点，键盘（honeyboard 之类）跟着顶上来，链子上记的
     * 是键盘——既不等于「本应用自己的页面」，也不在白名单里，于是兜底判据 leftFg
     * （MainActivity 离开前台 >1.2 秒）就把他判成逃回桌面（2026-09-16 真机：输完密码
     * 回桌面白弹一道乘法题，答错还会被送进两小时前用过的应用）。
     */
    private var coveredByOwnPage = false

    /**
     * 本 Activity 此刻是否在前台。只作为判定的兜底依据之一。
     * 同一个值也往 [onScreen] 里写一份给无障碍服务用（同一个进程，见 [GuardAccessibilityService.killTaskScreen]）
     */
    private var inForeground = false

    /** 上一次观察到的默认桌面状态，只在变化时写日志，免得刷屏 */
    private var lastDefault: Boolean? = null

    /** 角色弹框发起的时刻（elapsedRealtime），0 表示当前没有进行中的尝试 */
    private var roleAskedAt = 0L

    /** 上一次「默认桌面」尝试的结局，供自检报告引用 */
    private var lastHomeOutcome: String? = null

    /** 上一次「回到桌面」的判定现场，供自检报告引用 */
    private var lastHomeDecision: String? = null

    /**
     * 开关屏。注册在这里（而不是无障碍服务里）：桌面判「要不要弹挑战框」的兜底证据在
     * 家长没开无障碍时也要管用，而且开盖那一下的现场只有 MainActivity 看得见。
     * 屏幕熄灭/点亮都记一笔（见 [Store.screenOffAt] 那段注释），屏幕状态也顺手给守护用
     * （开关屏瞬间系统推的窗口事件不是孩子按的任务键，见 GuardAccessibilityService）。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Store.noteScreenOff(ctx)
                    Diag.log("home", "屏幕熄灭：熄屏之前记下的现场不再算「他从别处回到桌面」")
                }
                Intent.ACTION_SCREEN_ON -> {
                    Store.noteScreenOn(ctx)
                    Diag.log("home", "屏幕点亮")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.attach(this)
        try {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
            )
        } catch (e: Exception) {
            // 注册不上也照常跑：少的是「熄屏不算离开」这条保护，不该连桌面都起不来
            Diag.log("home", "注册开关屏广播失败：${e.javaClass.simpleName}: ${e.message}")
        }
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
        liveChannel = ch
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
     * 同时还要「没放太久」：一段陈年证据（比如放了一夜）同样说明不了他刚从应用里回来，
     * 见 [EVIDENCE_MAX_MS]。
     */
    private fun judgeAppearance(): Pair<Boolean, String> {
        val now = SystemClock.elapsedRealtime()
        // 熄屏期间（连同熄屏前后那一下）记下的现场一律作废：合上盖子放一会儿再打开，
        // 中间那段时间孩子什么都没做，可 onStop/onPause 记下的「桌面离开屏幕」、以及
        // 「别的应用露过头」看上去都像「他刚从别处回来」——2026-09-21 真机 08:11:45
        // 就是这么白弹了一道「按返回键回到桌面」（合盖 14 分钟）。熄屏发生在哪一刻由
        // [screenReceiver] 记，[SCREEN_OFF_GRACE_MS] 是给「离开前台」那一下留的余量：
        // 它有时落在熄屏广播之后几毫秒（那也算熄屏造成的）
        val screenOff = Store.screenOffAt(this).takeIf { it in 1..now } ?: 0L
        fun live(at: Long): Long =
            if (screenOff > 0 && at in 1..(screenOff + SCREEN_OFF_GRACE_MS)) 0L else at

        val self = live(Store.selfForegroundAt(this))
        val other = live(Store.otherForegroundAt(this))
        val front = Store.currentForeground(this) ?: "-"
        val before = GuardAccessibilityService.beforeLauncher(packageName)
        val allowedList = Store.allowed(this)
        val leftScreen = live(leftScreenAt)
        val leftFgAt = live(leftForegroundAt)
        val leftFg = if (leftFgAt == 0L) -1L else now - leftFgAt
        val viaHome = viaHomeIntent

        val verdict = when {
            // 守护自己刚把他弹回来的那次（孩子开了非白名单应用）不算他按的 Home，拦回桌面就完事
            Store.guardBounceRecent(this) -> false to "守护刚把他弹回桌面"
            // 「最近任务」那一屏刚露过头（按任务键带出来的）。那一屏会把桌面顶成 onStop/onPause，
            // 于是 leftScreen / leftFg 看上去像「他刚从别处回来」——可他只是按了任务键，什么都没逃。
            // 这条要排在所有兜底证据前面：它证伪的正是那几条兜底证据的假设
            GuardAccessibilityService.taskScreenRecent() -> false to "刚露头的是「最近任务」那一屏（按任务键带出来的）"
            // 桌面这一下离开屏幕/离开前台，正是被那一屏压出来的（按时刻对区间，比上一条结实：
            // 孩子连按任务键时那一屏能停留好几秒，光看「最近露过头」会漏）
            GuardAccessibilityService.desktopLeftBecauseOfTaskScreen(leftScreen) ||
                GuardAccessibilityService.desktopLeftBecauseOfTaskScreen(leftFgAt) ->
                false to "桌面这次离开屏幕是被「最近任务」那一屏压的（按任务键带出来的）"
            // 家长刚从系统设置那趟回来（放行还没收回）：这一下是他自己按的 Home，
            // 不该让他再做一道题，否则家长外出办事回来还得答题才能用桌面
            Store.parentFreeActive(this) -> false to "家长刚在放行期里（去过系统设置之类）"
            // 这一下露面是本应用自己的家长页面（密码页/同意页）关掉造成的。要排在所有
            // 兜底证据前面：兜底只看「Activity 离开前台多久」，而这两个页面必然把
            // MainActivity 压下去好几秒，一关就成了「离开前台 >1.2 秒」的假证据
            coveredByOwnPage -> false to "刚盖在上面的是本应用自己的家长页面（密码页/同意页）"
            // 「」= 无障碍看见桌面之前是本应用自己的页面（密码页/乘法挑战页/同意页盖在上面），
            // 那不是从应用里逃出来的——这条要排在兜底证据前面，否则锁屏页一关就误判成逃回桌面
            before != null && before.isEmpty() -> false to "刚才盖在上面的是本应用自己的页面"
            before != null && before in allowedList ->
                true to "无障碍看见桌面之前是 $before（孩子白名单里的应用）"
            // 兜底证据既要「放了一会儿」（FRESH_MS：这一下露面自己造成的现场变化不算数），
            // 也要「没放太久」（EVIDENCE_MAX_MS）。以前只卡了下限，于是 2026-09-16 的真机日志里
            // 出现过：孩子熬夜放了一夜、屏幕一亮桌面回到最前，系统拿 **6.8 小时前**「别的应用
            // 露过头」当证据弹了一道题，顺带把他那一轮计时也结束了。证据老到这个份上什么都说明不了
            other > self && now - other <= EVIDENCE_MAX_MS ->
                true to "最后露头的是别的应用（$front，${ago(now, other)}前）"
            leftScreen != 0L && now - leftScreen in FRESH_MS..EVIDENCE_MAX_MS ->
                true to "桌面这次回来前离开过屏幕（${ago(now, leftScreen)}前）"
            leftFg in FRESH_MS..EVIDENCE_MAX_MS -> true to "Activity 已离开前台 ${leftFg}ms"
            else -> false to "没拿到「从别处回来」的证据（最前=$front，桌面之前=${before ?: "（看不出）"}，" +
                "离开前台 ${leftFg}ms）" + if (screenOff > 0) "，熄屏前后的现场已作废" else ""
        }
        Diag.log(
            "home",
            "露面判定（${if (viaHome) "按 Home 键" else "没有 Home intent（按返回键退出应用之类）"}）：" +
                "桌面最后在前 ${ago(now, self)}前、别的应用 ${ago(now, other)}前、" +
                "桌面之前=${before ?: "（看不出）"}、leftScreen=${ago(now, leftScreen)}前、" +
                "leftFg=${leftFg}ms、ownPage=$coveredByOwnPage、最前=$front、" +
                "任务屏=${GuardAccessibilityService.taskScreenAgo()}、" +
                "熄屏=${if (screenOff == 0L) "没记过" else "${now - screenOff}ms 前"} → " +
                "${if (verdict.first) "弹挑战" else "不打扰"}",
        )
        lastHomeDecision =
            "桌面这次露面：${verdict.second} → ${if (verdict.first) "弹挑战" else "不打扰"}"
        // 留最近几条（不只最后一条）：owner 反馈「弹了个不该弹的框」时，只看最后一条往往
        // 已经翻篇了，看不出是刚才哪一下。带上时刻，能直接和孩子的动作对上
        recentHomeDecisions.addLast(
            "${clock.format(Date())} ${if (verdict.first) "弹挑战框" else "不打扰"}" +
                "（${if (viaHome) "按 Home 键" else "没有 Home intent"}）—— ${verdict.second}"
        )
        while (recentHomeDecisions.size > HOME_DECISION_KEEP) recentHomeDecisions.removeFirst()
        // 判定要弹框才算「程序做了个动作」。不弹的那些不记进审计——它们本身就是「什么都没做」，
        // 记进去会把审计列表灌满，反而看不出真正的动作。依据留在运行日志的 [home] 行里
        if (verdict.first) {
            Audit.record(Audit.CHALLENGE, "", "判定「他从应用里回到桌面」→ 弹挑战框（依据：${verdict.second}）")
        }
        consumeHomeEvidence()
        return verdict
    }

    /**
     * 把「该弹挑战框了」这件事通知 Flutter 侧。
     *
     * channel 为 null = Flutter 引擎还没起来（桌面刚被冷启动、界面还没挂上）。这一下以前是
     * **静默丢掉**的：孩子按了键，屏幕上什么都不弹，日志里也查不到为什么——只能看到判定说
     * 「弹挑战」，然后就没下文了。至少留一条，说清是谁把它吞掉的。
     */
    private fun notifyDart(method: String, what: String) {
        val ch = channel
        if (ch == null) {
            Diag.log("home", "$what：Flutter 通道还没就绪，这一下通知丢了（界面不会弹框）")
            return
        }
        ch.invokeMethod(method, null)
    }

    /**
     * 家长改了什么配置：只列真的变了的项，一行写完。写进行为审计是因为这属于
     * 「程序改了设备上的状态」，而且「白名单里某个应用怎么没了」这类疑问全靠这一行回答。
     */
    private fun logConfigChange(before: Map<String, Any>, after: Map<String, Any>) {
        val skip = setOf("usedSeconds", "extraSeconds", "openCount", "fileServerUrl")
        val changed = after.keys
            .filter { it !in skip && before[it] != after[it] }
            .joinToString("、") { "$it: ${before[it]} → ${after[it]}" }
        if (changed.isEmpty()) return
        Diag.log("cfg", "配置变更：$changed")
        Audit.record(Audit.CONFIG, "", changed)
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
        coveredByOwnPage = false
        Store.noteForeground(this, packageName, countAsApp = false)
    }

    /** 「多久以前」的可读文本，给日志用 */
    private fun ago(now: Long, at: Long): String =
        if (at == 0L) "（从没记过）" else "${now - at}ms"

    override fun onResume() {
        super.onResume()
        inForeground = true
        onScreen = true
        // 这一下露面是不是「刚退掉最近任务那一屏」造成的？三星手势导航下，从多任务视图按返回
        // 会落到桌面上，孩子并不是想回桌面——那一下返回键是本应用发的。取到了就直接把他送回
        // 刚才那个应用，不弹挑战框（家长要的是「任务键＝留在当前应用」）。
        val taskReturn = GuardAccessibilityService.consumeTaskReturn()
        // 刚才那一下露面是本应用自己发的返回键造成的（孩子站在桌面上按任务键，或者开关屏时
        // 系统推的那个过渡窗口）：这一下在 onStop/onPause 里留下的现场同样不是「他刚从别处回来」。
        // 没有可送回去的应用时（站在桌面上按任务键，会话早停了）taskReturn 就是 null，
        // 以前这种直接掉进兜底判据 → 孩子只按了任务键，却收到一道「你按了返回键」的题
        // （2026-09-21 真机 07:39:35）
        val ownBack = taskReturn == null && GuardAccessibilityService.taskKillRecent()
        if (ownBack) Diag.log("home", "这一次露面是守护自己发的返回键造成的（刚退掉「最近任务」那一屏），不判、不弹框")
        // 桌面这一次露面要不要弹挑战框。判定必须赶在下面清现场之前——那时读到的才是
        // 「这一次露面之前」的状态。每一次露面只判一次：孩子按 Home 让系统把桌面重新拉起来、
        // 可桌面本来就在最前面时（没有 onPause/onResume 那一轮）根本走不到这里，
        // 系统真把它重新拉起来的那种也已经在这一次判过了，不会再翻出上一次用过的应用弹题
        val escape = if (taskReturn != null || ownBack || appearanceJudged) null else judgeAppearance()
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
            if (viaHome) notifyDart("onHomeKey", "按 Home 键从应用回到桌面")
            else notifyDart("onBackEscape", "按返回键退出应用回到桌面")
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
        val gated = Store.gateReason(this)?.also {
            Diag.log("gate", "命中限制 $it，拉起密码页")
            Store.showLock(this, it)
        }
        // 退掉「最近任务」那一屏之后落到桌面的这一下：把他送回刚才那个应用，不弹挑战框。
        // 顺序放在门禁后面——时长/次数用完时该出现的是密码页，不能把他又塞回应用里
        if (taskReturn != null && gated == null) {
            consumeHomeEvidence()
            Diag.log("home", "退掉「最近任务」那一屏后落到桌面 → 送回 $taskReturn（不弹挑战框）")
            returnToApp(taskReturn, "刚退掉「最近任务」那一屏")
        }
        // 有设备在等「同意」而同意页当时没能弹出来（后台启动被系统拦了），这里补一次
        HttpGateway.onLauncherResume(this)
        // 进程可能是被系统重启的，服务该开没开的话在这里补上
        applyFileServer()
    }

    override fun onPause() {
        inForeground = false
        onScreen = false
        // 屏幕已经灭着的话，这一下离开前台不是「他去了别处」——合盖时系统就是这么停掉桌面的。
        // 记下来只会在开盖那一下变成「桌面离开屏幕 X 分钟」的假证据（见 [Store.screenOffAt]）
        leftForegroundAt = if (screenIsOff()) 0L else SystemClock.elapsedRealtime()
        // 盖上来的是本应用自己的家长页面（密码页/同意页）吗？是的话这一次离开前台不算
        // 「他离开桌面去了别处」，见 coveredByOwnPage。**不包含乘法挑战页**：那一页是从
        // 孩子正在用的应用上弹出来的，它上面的 Home 就是「从应用里逃回来」，照旧要判。
        // 这两个 showing 标记是在 startActivity **之前**就立起来的（Store.showLock /
        // HttpGateway.askConsent）：onPause 跑在对方 onCreate 之前，等它们自己置位读到的是 false
        coveredByOwnPage = LockActivity.showing || HttpConsentActivity.showing
        // 桌面要离开前台了：下一次回到最前面算一次新露面，得重新判（见 judgeAppearance）。
        // 孩子站在桌面上按 Home 不经过这里，所以那一下不会被当成一次新露面
        appearanceJudged = false
        super.onPause()
    }

    override fun onStop() {
        // 桌面被别的应用整个盖住了（不只是被弹框遮一下）。再回到桌面时，
        // 这就是「他刚从别处回来」的硬证据；自己切回来那次会在 onResume 里作废。
        // 屏幕灭着停掉的不算（合盖、按电源键），见 [screenIsOff]
        leftScreenAt = if (screenIsOff()) 0L else SystemClock.elapsedRealtime()
        super.onStop()
    }

    /** 屏幕此刻是不是灭着（合盖/按电源键导致的离开前台都长这样） */
    private fun screenIsOff(): Boolean =
        !(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

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
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // 没注册上（onCreate 里注册失败过）：无所谓，别在销毁路径上再抛一次
        }
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
                val before = Store.configMap(this)
                Store.applyConfig(this, args as Map<String, Any?>)
                applyGuardState()
                applyFileServer()
                val after = Store.configMap(this)
                result.success(after)
                logConfigChange(before, after)
            }
            "listApps" -> result.success(listApps())
            @Suppress("UNCHECKED_CAST")
            "appIcons" -> result.success(appIcons(args as List<String>))
            "launchApp" -> result.success(launchApp(args as String))
            "returnToLastApp" -> result.success(returnToLastApp())
            "openHomeSettings" -> result.success(requestDefaultHome())
            "launcherDiag" -> {
                Diag.log("diag", "家长打开了桌面自检")
                Audit.record(Audit.REPORT, "", "生成桌面自检报告（含行为审计）并存成文件")
                result.success(launcherDiag())
            }
            // 清空历史日志。清完不回报告：报告一生成又会写一份文件、还多两条记录，
            // 家长看到「刚清完 logs/ 里就有东西」会以为没清干净——他要的是从此刻起重新记
            "launcherDiagClear" -> result.success(Diag.clearAll(this))
            // 桌面顶部那行实时状态用。界面上「无障碍还开着吗」这件事，以前只有自检报告里能查到
            "accessibilityStatus" -> result.success(accessibilityStatusMap(this))
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
                Audit.record(Audit.CONFIG, "", "家长清空了今日统计（已用时长/次数归零）")
            }
            "setGuard" -> {
                val before = Store.configMap(this)
                Store.applyConfig(this, mapOf("guardEnabled" to (args as Boolean)))
                applyGuardState()
                val after = Store.configMap(this)
                result.success(after)
                logConfigChange(before, after)
            }
            "setFileServer" -> {
                val before = Store.configMap(this)
                Store.applyConfig(this, mapOf("fileServerOn" to (args as Boolean)))
                applyFileServer()
                val after = Store.configMap(this)
                result.success(after)
                logConfigChange(before, after)
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
        Audit.record(Audit.SYSTEM, what, "家长离开桌面前去这个系统页面（前台守护暂让路）")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Diag.log("act", "打开$what 失败：${e.javaClass.simpleName}: ${e.message}")
            Audit.record(Audit.SYSTEM, what, "打不开：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun applyGuardState() {
        val i = Intent(this, GuardService::class.java)
        if (Store.guardEnabled(this)) {
            ContextCompat.startForegroundService(this, i)
            Audit.record(Audit.SERVICE, "计时守护", "拉起前台服务（每日时长统计/超时锁屏）")
        } else {
            stopService(i)
            Audit.record(Audit.SERVICE, "计时守护", "停掉前台服务")
        }
    }

    /** 文件传输服务的开关同样落到服务上：开着就拉起前台服务，关掉就停 */
    private fun applyFileServer() {
        val i = Intent(this, FileServerService::class.java)
        if (Store.fileServerOn(this)) {
            try {
                ContextCompat.startForegroundService(this, i)
                Audit.record(Audit.SERVICE, "文件传输", "拉起前台服务（等浏览器连过来）")
            } catch (e: Exception) {
                Diag.log("http", "拉起文件传输服务失败：${e.javaClass.simpleName}: ${e.message}")
                Audit.record(Audit.SERVICE, "文件传输", "拉不起来：${e.javaClass.simpleName}: ${e.message}")
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
            startActivity(i)
            Audit.record(Audit.LAUNCH, pkg, "从桌面打开这个应用（挑战已通过）")
            true
        } catch (e: Exception) {
            Diag.log("act", "打开 $pkg 失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 按 Home 键的挑战没答对（答错后关掉、或按了取消）：把孩子送回他刚才在用的那个应用，
     * 桌面得答对才进得去。「刚才在用哪个」由前台守护记着；记不到、或者那个应用已经不在白名单
     * （送回去也会被守护立刻弹回来），就只能让他留在桌面。
     */
    private fun returnToLastApp(): Boolean = returnToApp(Store.lastForeign(this), "挑战没过")

    /**
     * 把孩子送回 [pkg] 接着用。[why] 会原样写进日志和审计：是「挑战没过」送回去的，
     * 还是「刚退掉最近任务那一屏」送回去的——两者在日志里要能分开。
     */
    private fun returnToApp(pkg: String?, why: String): Boolean {
        if (pkg == null) {
            Diag.log("home", "$why，但没有「刚才在用哪个应用」的记录，只能留在桌面")
            return false
        }
        if (Store.frontGuard(this) && pkg !in Store.allowed(this)) {
            Diag.log("home", "$why，但 $pkg 已不在白名单，送回去也会被守护弹回来，留在桌面")
            return false
        }
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            Diag.log("home", "$why，但 $pkg 没有启动入口（已卸载？），留在桌面")
            return false
        }
        // 不带 RESET_TASK_IF_NEEDED：要的是把他放回原来那一屏，不是重启这个应用
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(i)
            Diag.log("home", "$why，把孩子送回 $pkg")
            Audit.record(Audit.RETURN, pkg, "$why，把孩子送回去接着用（不带 RESET_TASK）")
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
        // 这一行是「守护怎么突然不生效了」的答案所在：1.0.16 的日志里这条路翻过车，
        // 判成「不是默认桌面」之后整个前台守护集体停摆，而上面那一句当时还写着 true
        sb.appendLine("各判据看到的结果：${Store.defaultLauncherDetail(this)}")
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
        sb.appendLine("管控此刻在生效吗：${GuardAccessibilityService.guardStateText(this)}")
        sb.appendLine("「按任务键退掉最近任务」那一屏：${GuardAccessibilityService.taskKillStateText(this)}")
        sb.appendLine("开关已打开：${Store.frontGuard(this)}")
        sb.appendLine("无障碍服务已在系统里启用：${Store.accessibilityOn(this)}")
        sb.appendLine("家长放行中（暂不拦截）：${Store.parentFreeActive(this)}")
        sb.appendLine("放行时长设置：${Store.settingsFreeMin(this)} 分钟")
        sb.appendLine("非白名单应用一露头就被送回桌面；「最近任务」那一屏直接退掉，孩子留在当前应用")
        sb.append(GuardAccessibilityService.taskKillReport())
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
        sb.appendLine("「最近任务」那一屏最后一次露头：${GuardAccessibilityService.taskScreenAgo()}")
        sb.appendLine("最近几次「桌面露面」判定（从旧到新，弹了不该弹的框就照着时刻对孩子的动作）：")
        if (recentHomeDecisions.isEmpty()) {
            sb.appendLine("  （本次运行还没有过判定）")
        } else {
            recentHomeDecisions.forEach { sb.appendLine("  · $it") }
        }
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
        sb.appendLine("── 行为审计（程序对这台设备做过的每个动作）──")
        sb.append(Audit.report(this))
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
            "行为审计同时追加在：${Audit.file(this).absolutePath}\n" +
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
         * 桌面此刻是不是就在屏幕上（onResume/onPause 维护）。
         * 无障碍服务和桌面在同一个进程里，守护退「最近任务」那一屏之前问这一句，
         * 免得把系统那个原厂桌面露的一下窗口当成「孩子按了任务键」，见 GuardAccessibilityService.killTaskScreen。
         */
        @Volatile
        var onScreen = false

        /**
         * 「这一下按 Home 造成的现场变化」都在这么新以内，不作数。按 Home 会把桌面拉到最前、
         * 把 Activity 暂停/重建，这些变化就发生在毫秒前；只有比这更早就成立的状态才算「他本来就在桌面上」。
         */
        private const val FRESH_MS = 1200L

        /**
         * 熄屏这一下前后留的余量：熄屏广播和「Activity 离开前台」是两个系统事件，
         * 谁先谁后不保证（真机日志里两者只差 22ms）。落在熄屏前后这个宽度里的现场都算熄屏造成的，
         * 见 [judgeAppearance] 里的 `live()`。
         */
        private const val SCREEN_OFF_GRACE_MS = 3_000L

        /**
         * 兜底证据的有效期上限。超过这个时长的「他离开过屏幕 / 离开过前台 / 别的应用露过头」
         * 说明不了「他刚从应用里回来」——中间可能是息屏过夜、重启、家长用了一阵，
         * 拿它弹题纯属误伤（真机日志里漂到过 6.8 小时）。
         */
        private const val EVIDENCE_MAX_MS = 30 * 60_000L

        /** 图标统一编码成这么大，够桌面磁贴用，又不至于把通道塞爆 */
        private const val ICON_PX = 128

        /**
         * 最新一次建起来的界面通道。无障碍服务是进程级的东西，它连上/断开时手里没有
         * Activity 实例，只能从静态处拿到通道把状态推回界面。
         */
        private var liveChannel: MethodChannel? = null

        /**
         * 无障碍此刻的实况，桌面顶部那行状态照这个显示。故意分成两件事：
         * `enabled` 是系统「无障碍」列表里的开关（家长能直接看到的那个），
         * `running` 是服务实例真的活着——开关开着 ≠ 在跑，装新版、强行停止、
         * 厂商省电休眠都会把服务杀掉而不改那个开关（第 24 条那种"限时没生效"就是这么来的）。
         */
        fun accessibilityStatusMap(ctx: Context): Map<String, Any> = mapOf(
            "enabled" to Store.accessibilityOn(ctx),
            "running" to GuardAccessibilityService.isRunning(),
        )

        /** 服务连上/断开的那一下立刻推给界面，不用等下一次刷新（这就是"实时"的来源） */
        fun pushAccessibilityStatus(ctx: Context) {
            val ch = liveChannel ?: return
            try {
                ch.invokeMethod("onAccessibilityChanged", accessibilityStatusMap(ctx))
            } catch (e: Exception) {
                Diag.log("guard", "无障碍状态推给界面失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }

        /** Activity 最后一次离开前台的时刻。进程级：Activity 被重建时，那是上一个实例留下的 */
        private var leftForegroundAt = 0L

        /** 自检报告里留最近几次「桌面露面」的判定，见 [judgeAppearance] */
        private const val HOME_DECISION_KEEP = 6

        /** 上面那几行判定各自发生的时刻（时:分:秒），只用于人看，不参与任何判断 */
        private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val recentHomeDecisions = ArrayDeque<String>()

        /** 桌面这次回到最前面之前真的离开过屏幕（onStop）的时刻，0 = 没有 */
        private var leftScreenAt = 0L
    }
}
