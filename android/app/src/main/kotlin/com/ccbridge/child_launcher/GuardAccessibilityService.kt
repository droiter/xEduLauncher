package com.ccbridge.child_launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView

/**
 * 前台守护，管这几件事：
 *
 * 1. 非白名单应用露头（从通知点开、按任务键切回一个后台还在跑的应用）——立刻把桌面拉回来，
 *    孩子就没法借着「已经开着的应用」绕过管控。
 * 2. 「最近任务」那一屏——直接退掉，让孩子留在原来那个界面里。送回桌面等于换个方式逃出当前应用，
 *    而且一按任务键就回桌面本身也不是家长要的（他要的是「拒绝，但不换界面」）。
 * 3. 单次使用时长——孩子在某个白名单应用里连续待满家长设定的分钟数，就弹一道一位数乘法
 *    （见 [sessionTick]）：答对清零重新计时，答错送回桌面。
 * 4. 记窗口链，供桌面判断「这一次回到桌面是不是从应用里逃出来的」：孩子按返回键退出应用时，
 *    系统根本不发任何 intent 给桌面，桌面只是被重新 resume——只有这里看得见「桌面之前是谁」。
 *    见 [beforeLauncher]。
 * 5. 系统「选文件」那一屏不是拦的对象，是放行的对象：孩子从白名单应用里点「选视频」必经
 *    DocumentsUI / 各家自带的文件管理器，弹回桌面等于把这个功能废掉。见 [pickerPackages]。
 * 6. 屏幕顶部一行小字，实时显示这次还剩多久（见 [addOverlay]）。它同时是「这个服务还活着」的
 *    指示灯：小字没了，就说明无障碍被系统关掉了，限时和前台守护都已经不生效。
 *
 * 只监听窗口切换事件（typeWindowStateChanged），不读取任何窗口内容，
 * 也不需要 canRetrieveWindowContent，系统设置页里给家长的说明就是这个用途。
 */
class GuardAccessibilityService : AccessibilityService() {

    /** 上一次「把非白名单应用弹回桌面」的时刻，避免同一秒里连环弹造成闪屏 */
    private var lastBounceAt = 0L

    /**
     * 上一次「退掉最近任务那一屏」的时刻。**单独一个计数器**：
     * 以前两种拦截共用一个节流，孩子刚被弹回桌面又马上按任务键时，那一下会被当「连环弹」丢掉，
     * 于是任务列表就留在屏幕上了——这正是「按任务键有时候还是能够看到任务列表」的来源。
     */
    private var lastTaskKillAt = 0L

    /** 系统界面里必须放行的部分：状态栏/通知面板、权限弹框、系统本身 */
    private val exempt = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** 系统上除本应用之外的桌面，只查一次：手势导航下「最近任务」由默认桌面渲染 */
    private var otherHomes: Set<String>? = null

    /** 「选文件」那一屏的宿主包，只查一次，见 [pickerPackages] */
    private var pickers: Set<String>? = null

    /** 上一个被放行的选择器包名：同一个包反复报窗口事件时日志只写一行 */
    private var lastPickerLogged: String? = null

    /** 上一条「放行为什么」的键和时刻，见 [logThrottled] */
    private var lastAllowKey: String? = null
    private var lastAllowAt = 0L

    /** 屏幕顶部那行「本次剩余 m:ss」，没加上时为 null */
    private var overlay: TextView? = null

    /** 浮层的结局，写进自检报告——加不上时家长能一眼看到为什么 */
    private var overlayNote = "还没试过"

    /** 此刻最前面的窗口属于哪个包（每次窗口切换都更新） */
    private var frontPkg: String? = null

    /** 上一个最前面的窗口属于哪个包。桌面露头时它就是「他刚离开的那个应用」 */
    private var prevFrontPkg: String? = null

    /** 桌面（本应用的 MainActivity）最后一次真正露头的时刻，0 = 本次运行还没见过 */
    private var launcherFrontAt = 0L

    /** 那一次露头之前最前面的是谁（null = 更早的没记上） */
    private var beforeLauncherPkg: String? = null

    /**
     * 最后一个出现在最前面的**应用**窗口。本应用自己的页面、输入法、状态栏、别的桌面都不算。
     * 单次时长的走表只看它：孩子敲一下键盘、桌面弹个框，都不是「他离开了这个应用」。
     */
    private var frontAppPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 本服务可能是这个进程里第一个起来的（开机后系统直接绑定无障碍服务，还没人打开桌面），
        // 不接一下的话 Diag 不知道往哪写文件，从这一刻到桌面被打开之间的日志就只剩内存里一份，
        // 家长过后来下载日志文件时看不到——而要看的问题恰恰都在这一段里
        Diag.attach(this)
        instance = this
        addOverlay()
        Diag.log("guard", "前台守护服务已连接（无障碍）")
        Audit.record(Audit.SERVICE, "前台守护", "无障碍服务已连接：开始拦非白名单应用、计时、看窗口链")
    }

    override fun onDestroy() {
        handler.removeCallbacks(sessionTick)
        sessionPkg = null
        challengeFor = null
        removeOverlay()
        if (instance === this) instance = null
        // 这条日志是「限时为什么又不生效」的答案所在：服务一没，单次计时、前台守护、
        // 任务键拦截、回到桌面挑战全都跟着停，而桌面上看不出来
        Diag.log("guard", "前台守护服务断开（限时/前台守护/任务键/回到桌面挑战随之全部失效）")
        Audit.record(Audit.SERVICE, "前台守护", "无障碍服务断开：限时/前台守护/任务键/回到桌面挑战全部失效")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 屏幕顶部那行小字是本服务自己加的浮层。它每秒改一次文字，要是把自己的窗口事件当成
        // 「孩子换了个应用」，单次计时会被自己打断，窗口链证据也会被它冲掉
        if (isOwnOverlay(e.windowId)) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""

        // 系统「选文件」那一屏（DocumentsUI、各家 ROM 自带的文件管理器、照片选择器）。
        // 孩子从白名单应用里点「选视频/选文件」时必经这一屏，守护把它当成「非白名单应用」
        // 弹回桌面，那个功能就等于废了（owner 2026-09-15 反馈）。
        // 整条链上都不认它：不记窗口链（桌面判「是不是从应用里逃出来」时看到的还是那个应用）、
        // 不结束单次计时、不弹回桌面——它不是「孩子换了个应用」，只是那个应用的一个界面
        if (pkg in pickerPackages()) {
            if (lastPickerLogged != pkg) {
                lastPickerLogged = pkg
                Diag.log("guard", "文件选择器 $pkg/${cls.substringAfterLast('.')} 放行（当界面看，不当换应用）")
            }
            Store.noteForeground(this, pkg, countAsApp = false)
            return
        }

        // 先把「谁在最前面」记下来，后面所有那些 return 都不能把这条链漏掉：
        // 桌面判断「孩子是不是从应用里退出来的」全靠它
        noteFrontWindow(pkg, cls)

        if (pkg == packageName) {
            // 自己的桌面、密码页、同意页：不拦，只记「本应用刚在最前面」。
            // 例外：手势导航下有的 ROM 把「最近任务」交给默认桌面（也就是本应用）渲染，
            // 那一屏的窗口同样属于本应用，不按住的话孩子一按任务键就看到任务列表了
            if (isRecentsClass(cls)) {
                killTaskScreen(pkg, cls)
                return
            }
            Store.noteForeground(this, pkg, countAsApp = false)
            return
        }

        val ime = inputMethodPackages()
        val dialer = defaultDialer()
        val foreign = isForeignApp(pkg, ime, dialer)
        // 记下此刻最前面的是谁：桌面判「这一次露面是不是从应用里逃出来的」时的兜底证据
        // （首选证据是上面那条窗口链，见 beforeLauncher）
        Store.noteForeground(this, pkg, countAsApp = foreign)
        if (foreign) Store.noteForeign(this, pkg)

        // 单次使用时长：只有白名单应用的窗口才算「孩子在用它」。输入法、电话、状态栏、
        // 别的桌面（露头＝他按了任务键）这些一闪而过的窗口不算换应用，会话不动
        val childApp = pkg in Store.allowed(this)
        // 只有「真是一个应用窗口」才更新这条：单次时长的走表靠它判断孩子还在不在那个应用里
        if (childApp || foreign) frontAppPkg = pkg
        if (childApp) {
            startSession(pkg)
        } else if (foreign) {
            endSession("切到了 $pkg")
        }

        // 「最近任务」优先处理，而且不跟下面那条防连环弹的节流共用计数器（见 lastTaskKillAt）。
        // 这一屏多停一帧孩子就多看一帧任务列表，宁可多退一次。
        // 白名单应用自己的窗口除外：「recents / overview」是通用词，孩子的应用里也可能有
        // 这么命名的页面，照退就是把他正看着的界面按掉了（1.0.9 之前没这事——那时这条判断
        // 排在白名单之后，移到这里是为了堵住系统界面那条路，别顺手把孩子的应用也搭进去）
        if (isTaskSwitchScreen(pkg, cls) && !childApp) {
            // 管控没在生效时这一屏退不掉，任务列表就留在屏幕上了——「按任务键有时候还能看到
            // 任务列表」多半问的就是这一行。以前这里是静默 return，日志里查不到任何线索
            val offTask = guardOffReason()
            if (offTask != null) {
                logThrottled(offTask, "任务键：这一屏本该退掉，但$offTask，只能放过")
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastTaskKillAt >= TASK_KILL_GAP_MS) {
                killTaskScreen(pkg, cls)
            } else {
                Diag.log(
                    "guard",
                    "任务键：${now - lastTaskKillAt}ms 前刚退过一次，这一下先跳过（$pkg/${cls.substringAfterLast('.')}）",
                )
            }
            return
        }

        // 管控没在生效的三种原因——以前这里（以及下面的白名单判断里）都是静默放行，
        // 于是「该拦的应用没拦」在日志里一个字都找不到。现在把包名和原因一起记下来
        val off = guardOffReason()
        if (off != null) {
            logThrottled(off, "放行 $pkg/${cls.substringAfterLast('.')}：$off")
            return
        }
        // 本来就不该拦的：系统界面、输入法、拨号器、家长勾过的白名单应用
        val routine = allowedReason(pkg, ime, dialer)
        if (routine != null) {
            logThrottled("$pkg|$routine", "放行 $pkg/${cls.substringAfterLast('.')}：$routine")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastBounceAt < BOUNCE_GAP_MS) {
            Diag.log("guard", "${now - lastBounceAt}ms 前刚弹过一次，这一下 $pkg 先放过")
            return
        }
        lastBounceAt = now

        Diag.log("guard", "拦截 $pkg/${cls.substringAfterLast('.')} → 回到桌面")
        Audit.record(Audit.BOUNCE, pkg, "不在白名单，把孩子弹回桌面（${cls.substringAfterLast('.')}）")
        // 这一下回桌面是本服务干的，不是孩子按的 Home：桌面那边记下来，别再弹挑战框
        Store.noteGuardBounce(this)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 同一件事 10 秒内只记一条。窗口事件一秒能来好几条，而放行原因翻来覆去就那几种，
     * 不节流的话 400 条的内存日志几分钟就被灌满，真正要看的现场反而被挤掉。
     * [key] 是「同一件事」的判据：具体到包的原因用「包名|原因」，全局原因（守护关着之类）
     * 直接用原因本身——它对每个包说的都是同一句话。
     */
    private fun logThrottled(key: String, msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (key == lastAllowKey && now - lastAllowAt < ALLOW_LOG_GAP_MS) return
        lastAllowKey = key
        lastAllowAt = now
        Diag.log("guard", msg)
    }

    /**
     * 记下这一次窗口切换。链子只在包真的换了的时候往前挪一格：同一个包反复报事件
     * （桌面里弹个 Flutter 对话框、孩子应用的页面自己刷新）不该把「上一个是谁」冲掉。
     */
    private fun noteFrontWindow(pkg: String, cls: String) {
        if (pkg == frontPkg) return
        prevFrontPkg = frontPkg
        frontPkg = pkg
        if (pkg != packageName) return
        // 本应用自己的页面不都算「桌面露头」：密码页、乘法挑战页、同意页盖在桌面上时，
        // 露头的是它们，不是桌面。这几个页面各自有 showing 标记，建页时就置上了
        if (isRecentsClass(cls) || overlayShowing()) return
        launcherFrontAt = SystemClock.elapsedRealtime()
        beforeLauncherPkg = prevFrontPkg
        Diag.log("guard", "桌面露头：之前最前面的是 ${prevFrontPkg ?: "（没记上）"}")
    }

    /** 本应用那几个「盖在桌面上」的页面有没有正在显示的 */
    private fun overlayShowing(): Boolean =
        LockActivity.showing || SessionChallengeActivity.showing || HttpConsentActivity.showing

    /**
     * 桌面这一次露头之前，最前面的是什么。返回值给 MainActivity 判「要不要弹挑战框」：
     *   null = 看不出（服务没在跑，或桌面本来就站在最前面，不是在这次才盖上来）；
     *   ""   = 是本应用自己的页面（密码页之类），不是从应用里逃出来的；
     *   其它 = 那个包的包名——孩子是不是用着它，由桌面拿白名单去比。
     */
    private fun windowBeforeLauncher(launcherPkg: String): String? {
        val f = frontPkg
        // 服务还没看见桌面露头：它记的前台还是那个应用，说明桌面是刚盖上去的
        if (f != null && f != launcherPkg) return f
        // 服务看见了：只认「刚露头」的那一下。站在桌面上好一会儿了的不翻旧账，
        // 否则孩子站在桌面按一次 Home 就会把上一次用过的应用翻出来弹题
        if (SystemClock.elapsedRealtime() - launcherFrontAt > LAUNCHER_FRESH_MS) return null
        val prev = beforeLauncherPkg ?: return null
        return if (prev == launcherPkg) "" else prev
    }

    /**
     * 管控此刻**没**在生效的原因；生效时返回 null。
     *
     * 这三条以前都是静默 return true，日志里看不出任何痕迹——「该拦的应用没拦」「按任务键
     * 还能看到任务列表」十有八九就撞在这上面（最常见的是无障碍被系统关掉、本应用没设成默认桌面，
     * 家长在自检报告里只能看到一句「开关已打开」，看不出其实整体没生效）。
     */
    private fun guardOffReason(): String? {
        if (!Store.frontGuard(this)) return "「前台守护」开关没打开"
        if (Store.parentFreeActive(this)) return "家长放行期内（去过系统设置还没回来）"
        if (!Store.isDefaultLauncher(this)) return "本应用不是系统默认桌面"
        return null
    }

    /** 退掉最近任务那一屏，让孩子留在当前应用里 */
    private fun killTaskScreen(pkg: String, cls: String) {
        lastTaskKillAt = SystemClock.elapsedRealtime()
        Diag.log("guard", "任务键：退掉 $pkg/${cls.substringAfterLast('.')}，留在当前应用")
        Audit.record(Audit.TASK_KILL, pkg, "退掉「最近任务」那一屏（${cls.substringAfterLast('.')}），留在当前应用")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun isRecentsClass(cls: String): Boolean {
        val c = cls.lowercase()
        return c.contains("recents") || c.contains("overview")
    }

    /**
     * 这一屏是不是「最近任务」。两种来源都要认：SystemUI 自带的最近任务页（三键导航），
     * 以及系统桌面——手势导航下多任务视图由默认桌面渲染，它一露头就说明孩子按了任务键。
     */
    private fun isTaskSwitchScreen(pkg: String, cls: String): Boolean {
        if (isRecentsClass(cls)) return true
        return pkg in otherHomeApps()
    }

    private fun otherHomeApps(): Set<String> {
        otherHomes?.let { return it }
        val s = try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0)
                .map { it.activityInfo.packageName }
                .filter { it != packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
        otherHomes = s
        return s
    }

    /**
     * 「选文件」那一屏的宿主包。
     *
     * 硬编码常见的几个，再按标准 Intent 反查一遍——ROM 自带的文件管理器叫什么名字各家不一样
     * （三星「我的文件」、小米「文件管理」…），照着包名写死迟早漏。
     * 反查要靠 [AndroidManifest] 里那几条 `<queries>`，否则 Android 11 起的包可见性过滤会让这里查不到。
     *
     * 刻意不收 ACTION_PICK：那个通常落到相册整应用上，放行等于让孩子从选择器逛进整个相册。
     */
    private fun pickerPackages(): Set<String> {
        pickers?.let { return it }
        val s = HashSet<String>()
        s.addAll(
            listOf(
                "com.android.documentsui",
                "com.google.android.documentsui",
                "com.android.providers.media.module",
                "com.google.android.providers.media.module",
                "com.sec.android.app.myfiles",
                "com.samsung.android.providers.media",
                "com.android.intentresolver",
            )
        )
        for (i in pickerIntents()) {
            val found = try {
                packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
            } catch (_: Exception) {
                emptyList()
            }
            s.addAll(found)
        }
        s.remove(packageName)
        pickers = s
        return s
    }

    private fun pickerIntents(): List<Intent> = listOf(
        Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
        // Android 13 的照片选择器（API 33 才有这个常量，直接写字符串省得管编译版本）
        Intent("android.provider.action.PICK_IMAGES"),
    )

    override fun onInterrupt() = Unit

    /**
     * 这一屏算不算「孩子真在用的应用」。它有两个用途：记成「他刚才在用哪个」
     * （Home 挑战没过时把他送回去），以及给桌面判断「他是从别处按 Home 回来的」。
     * 系统界面、输入法、电话、以及别的桌面（露头＝孩子按了任务键）都不算。
     */
    private fun isForeignApp(pkg: String, ime: Set<String>, dialer: String?): Boolean =
        pkg !in exempt && pkg !in ime && pkg != dialer && pkg !in otherHomeApps()

    /**
     * 「本来就不该拦」的原因，返回 null 表示该拦。（管控没生效那三条在 [guardOffReason] 里，
     * 调用方要先问那个——顺序换了但结论不变：两条路都是放行，只是记下来的理由不一样。）
     */
    private fun allowedReason(pkg: String, ime: Set<String>, dialer: String?): String? {
        // 系统界面（状态栏、下拉通知栏、权限弹框、音量条）一律放行；
        // 其中的「最近任务」那一屏在上面就单独处理掉了，走不到这里
        if (pkg in exempt) return "系统界面"
        if (pkg in ime) return "输入法"
        // 电话要放行：来电界面被弹回桌面，孩子就接不了电话了
        if (pkg == dialer) return "默认拨号器"
        if (pkg in Store.allowed(this)) return "白名单应用"
        return null
    }

    private fun defaultDialer(): String? = try {
        (getSystemService(TELECOM_SERVICE) as? android.telecom.TelecomManager)
            ?.defaultDialerPackage
    } catch (_: Exception) {
        null
    }

    private fun inputMethodPackages(): Set<String> = try {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.enabledInputMethodList.map { it.packageName }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    // ---------- 单次使用时长 ----------

    private val handler = Handler(Looper.getMainLooper())

    /** 孩子这一次「待在某个白名单应用里」的会话：在哪个包、已经用掉多少秒 */
    private var sessionPkg: String? = null
    private var sessionSeconds = 0

    /** 正在等答案的那次会话（挑战页弹着的时候表是停的），null = 没有在弹 */
    private var challengeFor: String? = null

    /** 上一次走表时屏幕是灭的。只在「灭→亮」「亮→灭」那一下写日志，不每秒刷 */
    private var pausedByScreenOff = false

    private val sessionTick = object : Runnable {
        override fun run() {
            if (tickSession()) handler.postDelayed(this, 1000L)
        }
    }

    /** 孩子打开了 [pkg]（或从别处切回来）：会话切到它，从 0 开始数 */
    private fun startSession(pkg: String) {
        // 挑战页还弹着的时候不去碰会话：底下那个应用这时也可能冒窗口事件
        if (sessionPkg == pkg || challengeFor != null) return
        sessionPkg = pkg
        sessionSeconds = 0
        pausedByScreenOff = false
        val limit = Store.singleUseMin(this)
        if (limit > 0) Diag.log("session", "$pkg 开始单次计时（上限 $limit 分钟）")
        arm()
        updateOverlay()
    }

    private fun endSession(why: String) {
        val pkg = sessionPkg ?: return
        val used = sessionSeconds
        sessionPkg = null
        sessionSeconds = 0
        pausedByScreenOff = false
        handler.removeCallbacks(sessionTick)
        Diag.log("session", "$pkg 的单次计时结束（$why），本次已用 ${used}s")
        updateOverlay()
    }

    /** 只在有会话、且家长没把上限设成「不限」时走表 */
    private fun arm() {
        handler.removeCallbacks(sessionTick)
        if (sessionPkg == null) return
        if (Store.singleUseMin(this) <= 0) return
        handler.postDelayed(sessionTick, 1000L)
    }

    /**
     * 每秒一次。只在这一秒孩子确实还在那个应用里、屏幕还亮着时才往上加——息屏、
     * 被密码锁屏页盖住、人已经回到桌面，都不算「他在用」。返回 true 表示继续走表。
     */
    private fun tickSession(): Boolean {
        val pkg = sessionPkg ?: return false
        if (Store.singleUseMin(this) <= 0) {
            endSession("家长把上限改成了不限")
            return false
        }
        // 只有前台换成了**别的应用**才算他离开了这个应用。输入法弹出来、状态栏拉开、桌面
        // 自己弹个框、任务键那一屏——这些也都是窗口事件，但都不是换应用，不该把他的计时清零。
        // 1.0.10 真机上就是这么一秒一断（日志里 xedu 每段计时只有 0~11 秒），
        // 于是 5 分钟的上限永远攒不满，这个功能等于没生效。
        // 「回了桌面」那条路由 MainActivity.onResume → onDesktopShown 管，不靠这里。
        val frontApp = frontAppPkg
        if (frontApp != null && frontApp != pkg) {
            endSession("前台换成了 $frontApp")
            return false
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            // 息屏不计时，但要说清楚「是屏幕灭了所以没走表」，别让家长看着一串空档去猜
            if (!pausedByScreenOff) {
                pausedByScreenOff = true
                Diag.log("session", "$pkg 的单次计时暂停：屏幕已熄灭（息屏不算使用）")
            }
            return true
        }
        if (pausedByScreenOff) {
            pausedByScreenOff = false
            Diag.log("session", "$pkg 的单次计时继续：屏幕已点亮，已用 ${sessionSeconds}s")
        }
        sessionSeconds++
        updateOverlay()
        // 心跳：家长拿不准「限时到底在不在走」时，日志里每 30 秒一行就是答案
        if (sessionSeconds % TICK_LOG_EVERY_S == 0) {
            Diag.log(
                "session",
                "$pkg 已连续使用 ${sessionSeconds}s / 上限 ${Store.singleUseMin(this) * 60}s" +
                    "（前台应用窗口=${frontAppPkg ?: "（没记上）"}）",
            )
        }
        if (sessionSeconds >= Store.singleUseMin(this) * 60) {
            fireChallenge(pkg)
            return false
        }
        return true
    }

    /** 到点：停表，弹一道一位数乘法。结局由挑战页回报，见 [answerChallenge] */
    private fun fireChallenge(pkg: String) {
        sessionSeconds = 0 // 先清零：答对就是从这里重新开始数
        challengeFor = pkg
        handler.removeCallbacks(sessionTick)
        Diag.log("session", "$pkg 连续用满 ${Store.singleUseMin(this)} 分钟 → 弹乘法挑战")
        Audit.record(Audit.CHALLENGE, pkg, "单次用满 ${Store.singleUseMin(this)} 分钟，弹乘法挑战")
        updateOverlay()
        Store.showSessionChallenge(this, pkg)
    }

    /** 挑战页的结局：答对＝原应用从 0 重新计时，孩子接着用；答错＝会话就此结束（人已被送回桌面） */
    private fun answerChallenge(ok: Boolean) {
        val pkg = challengeFor ?: return
        challengeFor = null
        if (ok) {
            sessionPkg = pkg
            sessionSeconds = 0
            Diag.log("session", "$pkg 挑战答对，单次时长清零重新计时")
            arm()
        } else {
            sessionPkg = null
            sessionSeconds = 0
            handler.removeCallbacks(sessionTick)
        }
        updateOverlay()
    }

    // ---------- 屏幕顶部的剩余时间浮层 ----------

    /**
     * 孩子在白名单应用里时，屏幕顶部一行小字显示这次还剩多久，每秒跟着走。
     *
     * 用 TYPE_ACCESSIBILITY_OVERLAY——无障碍服务自己的浮层类型，不需要「显示在其他应用上层」
     * 那个额外权限：这个功能本来就只在无障碍活着时才有意义，权限口径跟它对齐，少一个会静默
     * 失效的开关。个别 ROM 不认这个类型，再退回 TYPE_APPLICATION_OVERLAY（那条要悬浮窗权限）。
     */
    private fun addOverlay() {
        if (overlay != null) return
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(3), dp(12), dp(3))
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        for (type in listOf(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        )) {
            // 悬浮窗权限没给就别去撞那一下，省得日志里多一条没用的报错
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                !Settings.canDrawOverlays(this)
            ) {
                continue
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // 压着状态栏那排图标不好看，往下让出状态栏的高度
                y = statusBarHeight()
            }
            try {
                wm.addView(tv, params)
                overlay = tv
                overlayNote = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                    "已显示（无障碍浮层，免权限）"
                } else {
                    "已显示（走悬浮窗权限那条路）"
                }
                Diag.log("session", "顶部剩余时间浮层：$overlayNote")
                Audit.record(Audit.OVERLAY, "", "加上屏幕顶部「本次剩余」小字：$overlayNote")
                return
            } catch (e: Exception) {
                overlayNote = "${e.javaClass.simpleName}: ${e.message}"
                Diag.log("session", "顶部浮层没加上（type=$type）：$overlayNote")
            }
        }
        overlayNote = "加不上：$overlayNote"
    }

    private fun removeOverlay() {
        val tv = overlay ?: return
        overlay = null
        Audit.record(Audit.OVERLAY, "", "移除屏幕顶部那行小字（服务要停了）")
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(tv)
        } catch (_: Exception) {
            // 已经跟着进程一起没了
        }
    }

    /** 会话状态一变、以及走表每秒一次时刷一下 */
    private fun updateOverlay() {
        val tv = overlay ?: return
        val text = overlayText()
        if (text == null) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
            return
        }
        if (tv.text.toString() != text) tv.text = text
        if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
    }

    /** 这行小字该显示什么；null = 不显示（没开单次上限 / 没在会话里 / 家长放行中 / 本应用的页面压在上面） */
    private fun overlayText(): String? {
        val limit = Store.singleUseMin(this)
        if (limit <= 0) return null
        if (sessionPkg == null) return null
        if (challengeFor != null) return null // 挑战页弹着（或正要弹），表是停的
        if (Store.parentFreeActive(this)) return null
        if (LockActivity.showing || SessionChallengeActivity.showing || HttpConsentActivity.showing) {
            return null
        }
        val left = (limit * 60 - sessionSeconds).coerceAtLeast(0)
        return "本次剩余 ${left / 60}:${(left % 60).toString().padStart(2, '0')}"
    }

    /** 这个窗口 id 是不是本服务自己加的浮层 */
    private fun isOwnOverlay(id: Int): Boolean = try {
        windows.any { it.id == id && it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
    } catch (_: Exception) {
        false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    companion object {
        /** 两次「弹回桌面」之间的最小间隔：挨着弹会闪屏 */
        private const val BOUNCE_GAP_MS = 600L

        /** 两次「退掉任务列表」之间的最小间隔：太密会把孩子应用里的返回键也一起按掉 */
        private const val TASK_KILL_GAP_MS = 700L

        /** 同一条「放行/放过」日志的重复抑制窗口，见 logThrottled */
        private const val ALLOW_LOG_GAP_MS = 10_000L

        /** 单次计时走表时每隔多少秒留一行心跳，家长据此确认「表确实在走」 */
        private const val TICK_LOG_EVERY_S = 30

        /**
         * 桌面露头多久之内还算「刚露头」。超过这个时长说明孩子已经在桌面上站着了，
         * 那一次露头的现场就不该再被翻出来用（他站在桌面上按 Home 也不该弹框）
         */
        private const val LAUNCHER_FRESH_MS = 2_000L

        /** 当前活着的那个服务实例。计时状态就在它身上，挑战页和桌面通过下面几个口子找它 */
        @Volatile
        private var instance: GuardAccessibilityService? = null

        /** 乘法挑战页答完题后的结局 */
        fun onChallengeAnswered(ok: Boolean) {
            instance?.answerChallenge(ok)
        }

        /** 桌面回到前台：孩子已经离开了刚才那个应用，会话结束 */
        fun onDesktopShown() {
            instance?.endSession("回到桌面")
        }

        /** 桌面判「这一次露面要不要弹挑战框」的证据，见 [beforeLauncher] */
        fun beforeLauncher(launcherPkg: String): String? = instance?.windowBeforeLauncher(launcherPkg)

        /**
         * 自检报告里的一行：管控此刻到底在不在生效，不生效是被哪一条卡住的。
         * 「限时没生效」「该拦的没拦」这类反馈，先看这一行——比逐个开关猜快得多。
         */
        fun guardStateText(ctx: Context): String {
            val s = instance
                ?: return "✗ 没生效：无障碍服务没在运行（限时、前台守护、任务键、回到桌面挑战全都停着）"
            return s.guardOffReason()?.let { "✗ 没生效：$it" } ?: "✓ 生效中"
        }

        /** 自检报告里那一小节：这一次露头的证据链，家长贴日志时能一眼看出判反在哪 */
        fun frontReport(ctx: Context): String {
            val s = instance ?: return "无障碍服务没在运行，这段证据拿不到\n"
            val now = SystemClock.elapsedRealtime()
            val sb = StringBuilder()
            sb.appendLine("无障碍记的最前面窗口：${s.frontPkg ?: "（还没有）"}")
            sb.appendLine("它之前的那一个：${s.prevFrontPkg ?: "（还没有）"}")
            sb.appendLine(
                "桌面最后一次露头：${if (s.launcherFrontAt == 0L) "（本次运行还没有）"
                else "${now - s.launcherFrontAt}ms 前，之前最前面的是 ${s.beforeLauncherPkg ?: "（没记上）"}"}"
            )
            return sb.toString()
        }

        /** 自检报告里那一小节：放行了哪些「选文件」界面，孩子点「选视频」被弹回桌面时先看这里 */
        fun pickerReport(): String {
            val s = instance ?: return "无障碍服务没在运行，这段拿不到\n"
            val list = s.pickerPackages().sorted()
            if (list.isEmpty()) return "（一个都没查到，孩子点「选文件」会被当普通应用拦掉）\n"
            return list.joinToString("、") + "\n"
        }

        /** 自检报告里那一小节 */
        fun sessionReport(ctx: Context): String {
            val limit = Store.singleUseMin(ctx)
            val sb = StringBuilder()
            sb.appendLine("上限设置：${if (limit <= 0) "不限（功能关着）" else "$limit 分钟"}")
            val s = instance
            if (s == null) {
                sb.appendLine("当前会话：无障碍服务没在运行，不会计时")
                sb.appendLine("  → 去「家长设置 → 防绕过 → 无障碍权限」重新打开它，限时才可能生效")
                sb.appendLine("屏幕顶部剩余时间小字：不会出现（它由无障碍服务显示）")
                return sb.toString()
            }
            val pkg = s.sessionPkg
            sb.appendLine(
                when {
                    pkg == null -> "当前会话：（没在计时）"
                    limit <= 0 -> "当前会话：$pkg（上限已改成不限，下一秒就停）"
                    else -> "当前会话：$pkg 已用 ${s.sessionSeconds} 秒，还剩 ${limit * 60 - s.sessionSeconds} 秒"
                }
            )
            sb.appendLine("正在等答题：${s.challengeFor ?: "无"}")
            // 光看「view 不为 null」会说谎：会话一停这行小字就被藏起来（见 updateOverlay），
            // 但 view 还在、文字还留着上一次的「本次剩余 9:41」，报告里就写成「现在挂着 9:41」
            val tv = s.overlay
            sb.appendLine(
                "屏幕顶部剩余时间小字：${s.overlayNote}；" + when {
                    tv == null -> "现在没有"
                    tv.visibility != View.VISIBLE ->
                        "现在没显示（它里面还留着上次的字「${tv.text}」，没在会话里就收起来了）"
                    else -> "现在是「${tv.text}」"
                }
            )
            sb.appendLine("无障碍看到的当前前台：${Store.currentForeground(ctx) ?: "（还没记录）"}")
            // 这个值只在会话里跟当前应用比着用（见 tickSession），会话一停就没人清它。
            // 不写明白，报告里会出现「没在计时，却认着一个应用窗口」这种看着像 bug 的一行
            val frontApp = s.frontAppPkg
            sb.appendLine(
                "计时认的那个应用窗口：${frontApp ?: "（还没记录）"}" +
                    if (frontApp != null && s.sessionPkg == null) "（会话已结束，这个值是上一次留下的）" else ""
            )
            return sb.toString()
        }
    }
}
