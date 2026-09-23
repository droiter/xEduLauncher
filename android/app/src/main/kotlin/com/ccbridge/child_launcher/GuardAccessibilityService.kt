package com.ccbridge.child_launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.view.KeyEvent
import android.view.MotionEvent
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
 * 2. 「最近任务」那一屏——按孩子当时在哪分两种办（见 [killTaskScreen]）：孩子在白名单应用里
 *    就退掉那一屏、人还留在那个应用里；孩子站在桌面上就把儿童桌面叫回最前面顶掉那一屏
 *    （2026-09-21 owner 定的：与其盖一块挡板，不如直接把桌面带回来）。
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
     * 上一次「退掉最近任务那一屏」的时刻，以及退的是哪一屏。**单独一组状态**：
     * 以前两种拦截共用一个节流，孩子刚被弹回桌面又马上按任务键时，那一下会被当「连环弹」丢掉，
     * 于是任务列表就留在屏幕上了。现在只用来挡「同一屏的迟到事件」，不再拿它当按时间放行的闸门
     * （见 [handleTaskScreen]）。
     */
    private var lastTaskKillAt = 0L
    private var lastTaskKillPkg: String? = null
    private var lastTaskKillCls: String? = null

    /**
     * 「这一次露头的最近任务那一屏，已经退过了」。任何**别的**窗口露头都清掉它——
     * 清掉之后这一屏再露头，就说明孩子又按了一下任务键，得重新退。
     */
    private var taskScreenKilledAt = 0L

    /**
     * 「最近任务」那一屏**露过头**的时刻，不管这一下退没退成、也不管最后是谁按的。
     * 桌面判挑战框时要问这一句：那一屏只可能是任务键带出来的，孩子并没有「从应用里逃回桌面」。
     *
     * 和 [taskScreenKilledAt]／[lastTaskKillAt] 的区别是「有没有退成」：管控没生效、放进来的
     * 过渡窗口、返回键被吞掉的那几下都不会留下退掉的记录，可它们照样会把 MainActivity 顶成
     * onStop——桌面再露头时就拿到 leftScreen 这条假证据，白弹一道题
     * （2026-09-21 模拟器实测：从任务列表按 Home 回桌面，依据 leftScreen=1398ms 弹了框）
     */
    private var taskScreenSeenAt = 0L

    /** 自检报告里那行「任务键到底拦得怎么样」的计数，见 [taskKillReport] */
    private var taskSeen = 0
    private var taskKilled = 0
    private var taskRetried = 0
    private var taskSkippedEcho = 0
    private var taskSkippedOff = 0
    private var taskSkippedScreen = 0

    /** 别家桌面的**过渡窗口**露头、被认出「不是任务屏」而放过的次数，见 [isTransitionClass] */
    private var taskSkippedTransition = 0

    /**
     * 盖挡板遮住任务列表的次数（[showTaskBlocker]）。**不等于**「退掉」的次数：
     * 同一屏补退时会再盖一次，只是把时间往后顺延。
     */
    private var taskBlocked = 0

    /** 挡板加不上时的原因，写进自检报告 */
    private var taskBlockerNote = "还没试过"

    /** 挡板吃掉的点击数（[showTaskBlocker] 里那个触摸监听器记的） */
    private var taskBlockerTaps = 0

    /**
     * 「孩子不在应用里（站在桌面上）按任务键」那一路把儿童桌面叫回最前面的次数，见 [killTaskScreen]。
     * 和 [taskKilled] 的关系：后者是总次数，两者之差就是「孩子在应用里按的、发返回键留下的」那一路
     */
    private var taskHomeCalled = 0

    /** 任务键在**按键这一层**就被吃掉、任务列表根本没出现的次数，见 [onKeyEvent] */
    private var keySwallowed = 0

    /**
     * 按键过滤这一路**收到过**的按键总数、以及各种 keycode 各几次（最多记 8 种）。
     *
     * 为什么单独记：「吃掉 0 次」有两种完全不同的原因——系统一个按键事件都没送过来，
     * 或者送来的不是 KEYCODE_APP_SWITCH。2026-09-21 owner 报的是**三键导航**（导航栏上
     * 那个任务键），而导航栏按钮是系统界面自己渲染的视图，点它是触摸事件、不产生按键事件
     * ——和手势导航一样走不到这里。没有这个计数就只能猜，见 [taskKillReport]。
     */
    private var keyEventsTotal = 0
    private val keyCodesSeen = LinkedHashMap<Int, Int>()

    /** 本应用自己的窗口露头、但桌面其实没在最前面（浮层/密码页/挑战页）而被略过的次数 */
    private var ownWindowSkipped = 0

    /** 输入法/系统界面这类「不是换应用」的窗口被挡在窗口链外面的次数，见 [noteFrontWindow] */
    private var chainSkipped = 0

    /**
     * 上一次「退掉最近任务那一屏」时孩子正在用的应用，以及那一刻。桌面那边用
     * [takeTaskReturn] 取走：三星手势导航下退掉多任务视图会落到桌面上，那一下不是他想回桌面。
     */
    private var taskReturnPkg: String? = null
    private var taskReturnAt = 0L

    /**
     * 上一条「不是最近任务那一屏」的窗口事件是什么时候来的，见 [taskScreenStillUp]。
     * 处置过那一屏之后它要是没再动过，就说明那一下没生效、那一屏还赖在最前面。
     */
    private var lastOtherWindowAt = 0L

    /** 按键过滤申请到了没有（申请不到就只能继续靠退「最近任务」那一屏兜底） */
    private var keyFilterOn = false

    /**
     * 系统界面里必须放行的部分：状态栏/通知面板、系统本身，以及各家 ROM 自己弹上来的页面。
     * 权限弹框那一家**不在这里逐个列举**——见 [systemUiPkg]。
     */
    private val exempt = setOf(
        "android",
        "com.android.systemui",
        // 三星自己的组件：孩子正用着应用时，它会自己弹一个欢迎页（WelcomePageActivity）盖上来。
        // 两处真机日志：2026-09-20 12:26:17 孩子正在 xEdu 里（单次已用 386s）被它顶掉，
        // 计时暂停、人被弹回桌面，得自己重新点开应用；2026-09-22 19:31:09 又发生在平板浏览器上。
        // 它不是孩子能打开的应用（我们的桌面只列家长勾过的应用），是三星自己弹的——
        // 拦它除了把孩子从正在用的应用里踢出来，什么也没拦住。
        "com.samsung.android.onetouch",
    )

    /**
     * 这个包算不算「系统界面」（[isForeignApp] 不拦、[allowedReason] 放行的那一类）。
     *
     * 权限弹框**按包名后缀认，不逐个列举**：这套东西是 AOSP 的模块，各家 ROM 只是换个包名
     * 重新编译——AOSP 自己是 `com.android.permissioncontroller`、谷歌版是
     * `com.google.android.permissioncontroller`，三星整个换成了
     * `com.samsung.android.permissioncontroller`。原来只写了前两个包名，2026-09-22 的真机
     * 自检报告里就能看到后果：系统「默认应用」页正是三星那个包
     * （`…/com.android.permissioncontroller.role.ui.DefaultAppActivity`），家长从桌面上点开它，
     * 也被当成「不在白名单的应用」弹了回去；孩子那侧对应的就是应用弹出来的各种权限/角色弹框
     * ——弹框一出现就被弹回桌面，家长也好、孩子也好，谁都答不了那个框。
     * 换句话说：换个 OEM 就复发一次，所以这里认后缀。
     */
    private fun systemUiPkg(pkg: String): Boolean =
        pkg in exempt || pkg.endsWith(".permissioncontroller")

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

    /**
     * 「最近任务」那一屏露头时盖上去的挡板（[showTaskBlocker]）。null = 此刻没盖着。
     *
     * 为什么必须有它：那一屏是**系统自己弹出来的窗口**，我们只能事后动手（叫回桌面 /
     * 发返回键）轰它走，而那一下实测要几百毫秒才见效（连按两下时更久，见 [taskFollowUp]）。这几百毫秒里
     * 任务列表是**能点**的——2026-09-21 真机日志里孩子就是这么点开设置、浏览器、权限控制器的
     * （每条后面紧跟一条「拦截 …→ 回到桌面」，说明应用真的被打开过）。
     * 挡板把这半秒盖住：列表还在时孩子点不动上面任何东西。
     */
    private var taskBlocker: View? = null

    /** 这一轮「盯住那一屏」是从什么时候开始的（见 [taskFollowUp]）。0 = 没在盯 */
    private var taskFollowUpAt = 0L

    /** 此刻最前面的窗口属于哪个包（每次窗口切换都更新） */
    private var frontPkg: String? = null

    /** 上一个最前面的窗口属于哪个包。桌面露头时它就是「他刚离开的那个应用」 */
    private var prevFrontPkg: String? = null

    /** 桌面（本应用的 MainActivity）最后一次真正露头的时刻，0 = 本次运行还没见过 */
    private var launcherFrontAt = 0L

    /** 那一次露头之前最前面的是谁（null = 更早的没记上） */
    private var beforeLauncherPkg: String? = null

    /**
     * 最近几条窗口事件，只记「谁露的头」（包名末段/类名末段），不记内容。
     *
     * 为什么要有它：孩子正用着白名单应用、却被莫名其妙踢回桌面时，报告里过去只有「我们做了什么」
     * （拦了谁、退了哪一屏），看不到**那一瞬间到底是谁露的头**——而根因往往就在那一条里。
     * 2026-09-22 owner 报「文件管理器里长按文件，操作菜单出现的前后就被切回桌面」时，能拿到的
     * 线索就只有一句动作日志，看不出那个包是谁带出来的。有这个环，下一次报告一眼就能看出。
     */
    private val trail = ArrayDeque<String>()

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
        requestKeyFilter()
        Diag.log("guard", "前台守护服务已连接（无障碍）")
        Audit.record(Audit.SERVICE, "前台守护", "无障碍服务已连接：开始拦非白名单应用、计时、看窗口链")
        // 桌面顶部那行状态要立刻从红变绿，不能等家长下一次回桌面才发现
        MainActivity.pushAccessibilityStatus(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(sessionTick)
        handler.removeCallbacks(taskFollowUp)
        taskFollowUpAt = 0L
        removeTaskBlocker("服务要停了")
        sessionPkg = null
        challengeFor = null
        removeOverlay()
        if (instance === this) instance = null
        // 这条日志是「限时为什么又不生效」的答案所在：服务一没，单次计时、前台守护、
        // 任务键拦截、回到桌面挑战全都跟着停。桌面顶部那行状态也靠这次推送变红
        Diag.log("guard", "前台守护服务断开（限时/前台守护/任务键/回到桌面挑战随之全部失效）")
        Audit.record(Audit.SERVICE, "前台守护", "无障碍服务断开：限时/前台守护/任务键/回到桌面挑战全部失效")
        MainActivity.pushAccessibilityStatus(this)
        super.onDestroy()
    }

    /**
     * 申请「过滤按键」。申请到之后孩子按任务键那一下会先送到 [onKeyEvent]，我们可以直接吃掉它
     * ——**任务列表根本不会出现**，也就没有「看到的是一闪而过的列表还能点进去」这回事。
     *
     * 这一步是 2026-09-21 加的主防线：在这之前「按任务键」只能靠「等任务屏露头了再补发返回键」
     * 来收拾，孩子站在桌面上按那一下尤其糟（那时桌面还没 pause，见 [killTaskScreen]）。
     * 有的 ROM/版本不给第三方无障碍服务这个能力，申请失败或系统不认时 [onKeyEvent] 不会被调到，
     * 那时还有 [killTaskScreen] 那条老路兜底（自检报告里两条路的计数都列出来，家长能看出在用哪条）。
     */
    private fun requestKeyFilter() {
        keyFilterOn = try {
            val info = serviceInfo ?: return
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            true
        } catch (e: Exception) {
            Diag.log("guard", "申请「按键过滤」失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 任务键直接在按键这一层吃掉。**管控没在生效时不吞**（家长放行期内、守护开关关着、
     * 本应用不是默认桌面）——那几个时刻任务键本来就该照常能用。
     *
     * DOWN 和 UP 都要吃：只吃 DOWN 的话 UP 那一下照样会被系统当成一次完整按键。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 先记「收到过什么」，再决定吃不吃：只统计按下，不然一次按键会记成两条
        if (event.action == KeyEvent.ACTION_DOWN) {
            keyEventsTotal++
            if (keyCodesSeen.size < 8 || keyCodesSeen.containsKey(event.keyCode)) {
                keyCodesSeen[event.keyCode] = (keyCodesSeen[event.keyCode] ?: 0) + 1
            }
        }
        if (event.keyCode != KeyEvent.KEYCODE_APP_SWITCH) return false
        if (taskKillOffReason() != null) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            keySwallowed++
            logThrottled("keySwallow", "任务键：这一下在按键这一层就被吃掉了，任务列表不会出现")
            Audit.record(Audit.TASK_KILL, "任务键", "按下的那一刻就被吃掉（任务列表没有出现）")
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 屏幕顶部那行小字是本服务自己加的浮层。它每秒改一次文字，要是把自己的窗口事件当成
        // 「孩子换了个应用」，单次计时会被自己打断，窗口链证据也会被它冲掉
        if (isOwnOverlay(e.windowId)) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        // 先把「谁露的头」记进窗口环，后面所有 return（含选择器那条）都不影响它
        noteTrail(pkg, cls)

        // 系统「选文件」那一屏（DocumentsUI、各家 ROM 自带的文件管理器、照片选择器）。
        // 孩子从白名单应用里点「选视频/选文件」时必经这一屏，守护把它当成「非白名单应用」
        // 弹回桌面，那个功能就等于废了（owner 2026-09-15 反馈）。
        // 整条链上都不认它：不记窗口链（桌面判「是不是从应用里逃出来」时看到的还是那个应用）、
        // 不结束单次计时、不弹回桌面——它不是「孩子换了个应用」，只是那个应用的一个界面
        //
        // **只有「他确实是在白名单应用里点出来的」才算**（[frontPkg] 还是那个白名单应用）：
        // 2026-09-22 owner 报「把不受控的 app 带到前台还能随便用」，能走通的就这一条——
        // 这张名单里有「我的文件」「相册」「WPS」这种**完整应用**，孩子从任务列表里直接点开它们，
        // 一样落进这条分支里被永远放行（连单次计时都不算）。从任务列表点进去时 [frontPkg]
        // 是那一屏（或别的桌面），比不中白名单，于是照常被拦
        if (pkg in pickerPackages()) {
            if (frontPkg in Store.allowed(this)) {
                if (lastPickerLogged != pkg) {
                    lastPickerLogged = pkg
                    Diag.log("guard", "文件选择器 $pkg/${cls.substringAfterLast('.')} 放行（当界面看，不当换应用）")
                }
                Store.noteForeground(this, pkg, countAsApp = false)
                return
            }
            if (lastPickerLogged != pkg) {
                lastPickerLogged = pkg
                Diag.log(
                    "guard",
                    "文件选择器 $pkg/${cls.substringAfterLast('.')} 这次不放行：" +
                        "刚才最前面的不是白名单应用（是 ${frontPkg ?: "没记上"}），当成孩子自己点开的应用",
                )
            }
        }

        // 先把「谁在最前面」记下来，后面所有那些 return 都不能把这条链漏掉：
        // 桌面判断「孩子是不是从应用里退出来的」全靠它
        noteFrontWindow(pkg, cls)

        // 露头的**不是**「最近任务」那一屏，就说明那一屏已经走了（叫回桌面/返回键生效了、
        // 或者孩子自己退出去了）：把「这一屏已经处理过」的记号清掉，它下次再露头才算新的一下。
        // 这一句是「连按任务键时中间那几下不能放过」的关键，见 handleTaskScreen
        val taskScreen = isTaskSwitchScreen(pkg, cls)
        if (taskScreen) {
            taskScreenSeenAt = SystemClock.elapsedRealtime()
            // 那一屏**一露头**就先把屏幕盖住：接下来叫桌面回来 / 退掉那一屏都要几百毫秒，
            // 孩子恰恰是在那段里点开的设置/浏览器（2026-09-21 真机日志：每条「拦截 …」
            // 前面都是同秒的任务键事件）
            if (taskKillOffReason() == null && !screenJustFlipped(cls) && !overlayShowing()) {
                showTaskBlocker()
            }
        }
        // 本服务自己的窗口（挡板本身、顶部剩余时间小字、密码页/挑战页/同意页：pkg 是本应用
        // 而桌面没在最前面）不算「别的窗口接上来了」。2026-09-22 真机报告查出来的：挡板一加进来
        // 就会报一条 `pkg=本应用, cls=android.view.View` 的事件（日志里的「本应用窗口露头（View）」），
        // 下面那三行簿记没排掉它，后果是两条，而且都正好打在兜底那一路的要害上：
        //   ① lastOtherWindowAt 被顶到 killTaskScreen 刚刚落下的那一刀之后 →
        //      taskFollowUp 的判据 `lastOtherWindowAt > killedAt` 永远成立 → 补刀一次都不发。
        //      报告里就是铁证：这一屏露头 104 次、处理 94 次，补刀 **0 次**，整场没跑过。
        //      孩子连按两下、第二下把第一刀的退场动画取消掉时（作者自己在 taskFollowUp 的注释里
        //      写过这种情形「系统不会再发一条窗口事件」），再没有任何兜底 —— 挡板 2500ms 到点
        //      自己收掉，任务列表就留在屏幕上，孩子那几下点击（本次 43 下）等挡板一收就落到列表上。
        //      **2026-09-22 复查时的现场**：最后那一下干脆连动作都没发出去（被 handleTaskScreen
        //      当成「同一屏的迟到事件」放过），挡板到期一收，列表就完整露出来了。现在挡板收不收
        //      只看 [taskScreenStillUp]，时间不再是判据。
        //   ② taskScreenKilledAt 被清零 → 同一屏的迟到事件被当成新的一下（日志里每个「盖挡板」
        //      后面都跟着**两条**「退掉 …RecentsActivity」），多补的那一刀有落到孩子正在用的
        //      应用上的风险，见 handleTaskScreen 里那段「别对已经不在最前面的窗口再来一下」。
        // 判据带上 MainActivity.onScreen：桌面（本应用的 MainActivity）真的接上来时它是
        // 「那一屏走了」的正当证据，那一路不能一起排掉。
        val ownWindow = pkg == packageName && !MainActivity.onScreen
        if (!taskScreen && !ownWindow) {
            taskScreenKilledAt = 0L
            // 有别的窗口接上来了：见 taskFollowUp，这一刻起就不再怀疑那一屏还留在前面
            lastOtherWindowAt = SystemClock.elapsedRealtime()
            // 挡板的任务就是盖住那一屏。**真正的下一个界面**（孩子那个应用、桌面）露头才收，
            // 状态栏/输入法这种插进来的窗口不算——三星任务列表进出时系统界面会报窗口事件，
            // 照单全收的话挡板会在列表还在时就被收掉，那半秒又重新变得能点。
            // **本应用自己的窗口也不算**：挡板一起来就会报一条自己的窗口事件，不排掉的话
            // 挡板刚盖上就被自己收掉（2026-09-21 模拟器日志：「本应用窗口露头（View）」
            // 紧跟「挡板收掉（下一个界面露头了）」）。[isOwnOverlay] 挡不住它——那个函数
            // 靠 `windows`，而本服务没申请 canRetrieveWindowContent，拿到的一直是空表
            if (pkg != packageName && pkg != SYSTEM_UI_PKG && pkg !in inputMethodPackages()) {
                stopTaskFollowUp("下一个界面露头了")
            }
        }

        if (pkg == packageName) {
            // 自己的桌面、密码页、同意页：不拦，只记「本应用刚在最前面」。
            // 例外：手势导航下有的 ROM 把「最近任务」交给默认桌面（也就是本应用）渲染，
            // 那一屏的窗口同样属于本应用，不按住的话孩子一按任务键就看到任务列表了
            if (taskScreen) {
                // 管控没生效时这一屏也退不掉，得跟下面那条路一样说清原因
                val offTask = taskKillOffReason()
                if (offTask != null) {
                    taskSkippedOff++
                    logThrottled(offTask, "任务键：这一屏本该退掉，但$offTask，只能放过")
                } else {
                    handleTaskScreen(pkg, cls)
                }
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
            pauseSession("切到了 $pkg")
        }

        // 「最近任务」优先处理，而且不跟下面那条防连环弹的节流共用计数器（见 lastBounceAt）。
        // 这一屏多停一帧孩子就多看一帧任务列表，宁可多退一次。
        // 白名单应用自己的窗口除外：「recents / overview」是通用词，孩子的应用里也可能有
        // 这么命名的页面，照退就是把他正看着的界面按掉了（1.0.9 之前没这事——那时这条判断
        // 排在白名单之后，移到这里是为了堵住系统界面那条路，别顺手把孩子的应用也搭进去）
        if (taskScreen && !childApp) {
            // 管控没在生效时这一屏退不掉，任务列表就留在屏幕上了——「按任务键有时候还能看到
            // 任务列表」多半问的就是这一行。以前这里是静默 return，日志里查不到任何线索
            val offTask = taskKillOffReason()
            if (offTask != null) {
                taskSkippedOff++
                logThrottled(offTask, "任务键：这一屏本该退掉，但$offTask，只能放过")
                return
            }
            handleTaskScreen(pkg, cls)
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

        Diag.log("guard", "拦截 $pkg/${cls.substringAfterLast('.')} → 回到桌面；刚才的窗口：${trailText()}")
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
        // 本应用自己的窗口：只有桌面**真的在最前面**（MainActivity 是 resumed 的）才算一次
        // 「桌面露头」。屏幕上那行小字（无障碍浮层，会话里每秒改一次文字）和密码页/挑战页/同意页
        // 都是本应用自己的窗口，可它们盖在孩子的应用上时 MainActivity 是 paused 的——
        // 拿它们当「桌面露头」会把「桌面之前是哪个应用」这条最结实的证据整个冲掉，
        // 桌面就只能退到很旧的兜底证据（离开前台多久）上去判，白弹挑战框
        // （2026-09-21 真机 14:10:19 就是这么把 frontPkg 写成本应用的）
        if (pkg == packageName && !MainActivity.onScreen) {
            ownWindowSkipped++
            logThrottled(
                "ownWindowNotFront",
                "本应用窗口露头（${cls.substringAfterLast('.')}），但桌面没在最前面" +
                    "（浮层/密码页/挑战页），不当成「桌面露头」",
            )
            return
        }
        // 输入法、状态栏这些不是「孩子换了个应用」，可它们会插在孩子那个应用和桌面中间。
        // 让它们进链子的话，桌面露头时记下的「之前最前面的是谁」就成了输入法，
        // 桌面比不中白名单，只能退到「离开屏幕多久」那条兜底证据上去判——那正是白弹挑战框的来源。
        // 2026-09-21 真机日志里桌面露头的前一个窗口就是 com.samsung.android.honeyboard；
        // 模拟器上同一现象复现为 com.google.android.inputmethod.latin。
        if (pkg in inputMethodPackages() || pkg == SYSTEM_UI_PKG) {
            chainSkipped++
            logThrottled(
                "chainSkipNoneApp",
                "窗口链跳过 $pkg/${cls.substringAfterLast('.')}（输入法/系统界面，不是「孩子换应用」）",
            )
            return
        }
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
        if (!Store.interceptionOn(this)) return "「启动拦截」总闸没打开（也没在测试拦截中）"
        if (!Store.frontGuard(this)) return "「前台守护」开关没打开"
        if (Store.parentFreeActive(this)) return "家长放行期内（去过系统设置还没回来）"
        if (!Store.isDefaultLauncher(this)) return "本应用不是系统默认桌面"
        return null
    }

    /**
     * 「处置最近任务那一屏」这件事没在生效的原因。**故意不问「是不是默认桌面」**：
     * 孩子在应用里按的那一路发的是返回键，谁当桌面都成立——本应用没被设成默认桌面时，
     * 孩子照样不该看到任务列表。2026-09-16 的真机日志里，那条判据翻车时任务列表被白白放过了
     * 7 个多小时（日志原话：「任务键：这一屏本该退掉，但本应用不是系统默认桌面，只能放过」）。
     * 拦截非白名单应用那条路仍然要默认桌面，见 [guardOffReason]。
     */
    private fun taskKillOffReason(): String? {
        if (!Store.interceptionOn(this)) return "「启动拦截」总闸没打开（也没在测试拦截中）"
        if (!Store.frontGuard(this)) return "「前台守护」开关没打开"
        if (Store.parentFreeActive(this)) return "家长放行期内（去过系统设置还没回来）"
        return null
    }

    /**
     * 「最近任务」那一屏露头了，该不该处置。
     *
     * **判定不能只看时间**。1.0.18 之前这里是一道「两次处置之间必须隔 700ms」的节流：
     * 孩子（或家长测试时）连按任务键，按得比 700ms 快，中间那几下就整段跳过——而「跳过」
     * 的意思是**那一屏就这么留在他眼前**，留着的任务列表还能点进去。2026-09-17 的真机日志里
     * 9 秒内这一屏露头 13 次、退掉 7 次、跳过 6 次，跳过的时刻正好就是「还能看到任务列表」。
     *
     * 改成认「这一次露头」：处置过一次之后，只有等到**别的窗口**露头（说明那一屏确实走了，
     * 见 onAccessibilityEvent 里那句清记号）才算下一次。同一屏在这中间反复报事件一律当重复事件
     * ——那是同一个窗口在刷状态，不是孩子又按了一下，再补一刀反而会打到现在正用着的界面上
     * （把这个应用按退出了，这是老版本那条 700ms 当初要防的事）。
     */
    private fun handleTaskScreen(pkg: String, cls: String) {
        taskSeen++
        // 先盯上：下面无论走哪条路（包括「放过」），只要那一屏还在最前面，[taskFollowUp]
        // 都会接着管——这是「放过了就没人管、挡板到点自己收掉」那个洞的封条
        ensureTaskFollowUp()
        val now = SystemClock.elapsedRealtime()
        if (taskScreenKilledAt != 0L) {
            if (now - taskScreenKilledAt <= TASK_KILL_RETRY_MS) {
                taskSkippedEcho++
                logThrottled(
                    "taskScreenEcho",
                    "任务键：同一屏刚处理过（${now - taskScreenKilledAt}ms 前），重复事件放过",
                )
                return
            }
            // 处理了这么久这一屏还在 ⇒ 那一下没生效（系统正忙、或落到了别处），再来一次
            Diag.log("guard", "任务键：${now - taskScreenKilledAt}ms 前处理过但这一屏还在，再来一次")
        } else if (pkg == lastTaskKillPkg && cls == lastTaskKillCls && now - lastTaskKillAt < TASK_KILL_ECHO_MS) {
            // 那一屏走后别的窗口已经露过头（记号清了），紧接着又来一条同一屏的迟到事件。
            // 别对已经不在最前面的窗口再来一下——那一下会打到现在这个界面上。
            // **这条只对「孩子在应用里按的」那一支成立**（发的是返回键，打歪了会按掉他正看的界面）；
            // 「孩子站在桌面上按的」那一支发的是 HOME，落点由系统定、就是我们自己这个桌面，
            // 多按一下什么都不欠，可放过的代价是那一屏留在屏幕上（2026-09-22 真机报告：最后
            // 那一下正是被这条当迟到事件放过，之后没有任何人轰它走，2.5 秒后挡板自己收掉）
            if (sessionPkg?.takeIf { it in Store.allowed(this) } != null) {
                taskSkippedEcho++
                logThrottled("taskScreenEcho", "任务键：${now - lastTaskKillAt}ms 前刚处理过的同一屏迟到事件，放过")
                return
            }
            Diag.log("guard", "任务键：${now - lastTaskKillAt}ms 前刚处理过同一屏，但孩子不在应用里（发 HOME，没有落点风险），照办")
        }
        killTaskScreen(pkg, cls)
    }

    /**
     * 处理「最近任务」那一屏。两种现场分开办（[sessionPkg] 就是判据）：
     *
     * - **孩子站在桌面上按的**（会话早停了）：不跟那一屏纠缠，直接 [performGlobalAction] HOME
     *   把儿童桌面叫回最前面。2026-09-21 owner 的原话：「你有时间盖挡板，没有时间把儿童桌面
     *   重新带到前台吗」。以前这一路要发返回键，可返回键**落在哪由系统定**：桌面那一刻还没
     *   pause（真机实测 1.3~2 秒），于是得压后复核、补退、盖挡板，孩子就靠这几百毫秒点开了
     *   设置和浏览器。HOME 一步到位、没有落点问题——桌面本来就是他该待的地方，叫回来什么都不欠。
     * - **孩子在白名单应用里按的**：照旧发返回键，让他留在当前应用里（发 HOME 会把他踢出
     *   正在用的应用，那不是他要的）。
     *
     * 两种都要先盖挡板：叫桌面回来 / 退掉那一屏都要几百毫秒，那段时间里列表是能点的。
     * [isRetry] 是 [taskFollowUp] 补的那一下，不算新的一轮。
     */
    private fun killTaskScreen(pkg: String, cls: String, isRetry: Boolean = false) {
        // 本应用自己的整屏页面（密码页/乘法挑战页/同意页）正开着时一概不动手——
        // 这几个页面一起来，系统切任务时的过渡窗口也会被当成「最近任务」，而这一下动作
        // 落在哪由系统定、不是我们能挑的：密码页正在最前面，挨的就是它
        // （2026-09-20 模拟器实测：限时到点弹出的密码页 0.6 秒后自己消失，孩子按一下就到手了）。
        // 发 HOME 也一样：那会把密码页/挑战页整个收到后台，等于放他走
        if (overlayShowing()) {
            Diag.log(
                "guard",
                "任务键：$pkg/${cls.substringAfterLast('.')} 露头，但本应用自己的页面（密码页/挑战页/同意页）正开着，" +
                    "这一下不动手（怕打在自己页面上）",
            )
            return
        }
        // 开关屏那一下，原厂桌面的过渡窗口也会这么报上来——它不是孩子按的任务键。
        // 真机日志（2026-09-21）：29 次亮/灭屏里有 22 次紧跟一条「退掉 com.sec.android.app.launcher/FrameLayout」，
        // 每一次都是白动手，还把孩子从正在用的应用里踢回桌面：07:49:51 会话被暂停又恢复，
        // 07:56:00 那一下更是把「打开次数用完」的密码页直接顶了出来。屏幕状态刚变过就先放过这一下，
        // 真按了任务键的话那一屏还在，下一批窗口事件（几百毫秒内）照样处理。
        if (screenJustFlipped(cls)) {
            taskSkippedScreen++
            logThrottled(
                "taskScreenFlip",
                "任务键：$pkg/${cls.substringAfterLast('.')} 露头，但屏幕刚亮/刚灭，" +
                    "这一下多半是开关屏的过渡窗口，不动手",
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        lastTaskKillAt = now
        lastTaskKillPkg = pkg
        lastTaskKillCls = cls
        // 「这一次露头已经退过了」。别的窗口露头时清掉，见 onAccessibilityEvent
        taskScreenKilledAt = now
        if (isRetry) {
            taskRetried++
        } else {
            taskKilled++
        }
        // 孩子此刻还在白名单应用里吗？在的话这一下按的就不是「站在桌面上那个任务键」。
        // 认的是**会话**里那个应用，不是「最后一个应用窗口」：孩子站在桌面上按任务键时，
        // 会话已经停了（sessionPkg=null），那时就不该把他送进昨天用过的应用里
        val backToApp = sessionPkg?.takeIf { it in Store.allowed(this) }
        // 桌面那边看到这次的记录就知道这一下露面是守护造成的：不弹挑战框，该送回应用就送回
        taskReturnPkg = backToApp
        taskReturnAt = now
        // 轰它走之前先把屏幕盖住：下面那几百毫秒里列表还能点（见 [showTaskBlocker]）
        showTaskBlocker()
        // 盯住它：这一下要是不生效，[taskFollowUp] 会接着补，挡板一直盖到它真的走了为止
        ensureTaskFollowUp()
        if (backToApp == null) {
            if (!isRetry) {
                taskHomeCalled++
                Diag.log("guard", "任务键：$pkg/${cls.substringAfterLast('.')} 露头，孩子不在应用里，把儿童桌面叫回最前面；刚才的窗口：${trailText()}")
                Audit.record(Audit.TASK_KILL, pkg, "把儿童桌面叫回最前面（顶掉「最近任务」那一屏）")
            }
            performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            if (!isRetry) {
                Diag.log("guard", "任务键：退掉 $pkg/${cls.substringAfterLast('.')}，留在当前应用（$backToApp）；刚才的窗口：${trailText()}")
                Audit.record(Audit.TASK_KILL, pkg, "退掉「最近任务」那一屏（${cls.substringAfterLast('.')}），留在当前应用")
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * 那一屏现在还盖在屏幕上吗。判据是「我们最后一次看到的窗口事件还是不是它」：
     * 处理过那一屏之后只要有**别的**窗口接过前台（孩子那个应用回来了、桌面露头了……），
     * 就说明它已经走了；没人接过就说明它还赖在最前面。
     *
     * **为什么不能拿「时间到了」当它走了**：2026-09-22 owner 那份报告里的现场——
     * 孩子最后一次按任务键带出那一屏，那一下的事件被判成「同一屏的迟到事件」放过了（没动手），
     * 于是没有任何人去轰它走，挡板 2500ms 到点自己收掉，任务列表就完整露在屏幕上给孩子点了。
     * 时间只能用来表达「我们等不起」，表达不了「它已经走了」。
     */
    private fun taskScreenStillUp(): Boolean = taskScreenSeenAt > lastOtherWindowAt

    /** 开始盯住那一屏（已经在盯就不动，预算按第一次算起） */
    private fun ensureTaskFollowUp() {
        if (taskFollowUpAt != 0L) return
        taskFollowUpAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(taskFollowUp)
        handler.postDelayed(taskFollowUp, TASK_VERIFY_MS)
    }

    /** 不盯了：停掉循环，顺手把挡板收掉（挡板就是为这一轮盖的） */
    private fun stopTaskFollowUp(reason: String) {
        handler.removeCallbacks(taskFollowUp)
        taskFollowUpAt = 0L
        removeTaskBlocker(reason)
    }

    /**
     * 盯住那一屏：每隔 [TASK_VERIFY_MS] 看一眼，只要它还在最前面就再动一下、挡板继续盖着。
     *
     * 为什么要盯：①孩子连按两下时第二下会把第一下的退场动画取消掉，那一屏还在、可系统
     * **不会再发一条窗口事件**（同一个窗口没换过），光等事件永远不知道它还在；②上面提到的
     * 「那一下被当成重复事件放过」也属于没有任何人来轰它走。两种情况下只要没人管，那一屏
     * 就会一直留在屏幕上。
     *
     * 补刀**不再有次数上限**（原来 2 次）：只要还在盖着，就说明孩子还在那一屏上，停下才是错的。
     * 上限改成时间预算 [TASK_BLOCKER_MAX_MS]——我们的簿记万一过期（系统不再发窗口事件），
     * 也得有个头，不能把孩子一直困在灰屏上。
     */
    private val taskFollowUp: Runnable = Runnable {
        val startedAt = taskFollowUpAt
        if (startedAt == 0L) return@Runnable
        val now = SystemClock.elapsedRealtime()
        if (!taskScreenStillUp()) {
            stopTaskFollowUp("那一屏已经走了")
            return@Runnable
        }
        if (now - startedAt >= TASK_BLOCKER_MAX_MS) {
            Diag.log(
                "guard",
                "任务键：顶了 ${(now - startedAt) / 1000} 秒那一屏还在最前面（多半是我们的簿记过期了），" +
                    "先把挡板收了——它再露头会重新盖",
            )
            stopTaskFollowUp("顶到时间预算上限")
            return@Runnable
        }
        val pkg = lastTaskKillPkg
        val cls = lastTaskKillCls
        // 刚动过就等下一轮；本应用自己的页面正盖着时不动手（那一下会打在自家页面上）
        if (pkg != null && cls != null && now - lastTaskKillAt >= TASK_VERIFY_MS && !overlayShowing()) {
            Diag.log("guard", "任务键：${now - lastTaskKillAt}ms 过去 $pkg/${cls.substringAfterLast('.')} 还赖在最前面，再动一下")
            killTaskScreen(pkg, cls, isRetry = true)
        }
        handler.postDelayed(taskFollowUp, TASK_VERIFY_MS)
    }

    /**
     * 「退掉最近任务」之后那一下露面该把孩子送回哪个应用。**只认刚退掉那一两秒内的记录**：
     * 时间一过就当没有，免得他后来自己按 Home 也被送回应用里。
     */
    private fun takeTaskReturn(): String? {
        val pkg = taskReturnPkg ?: return null
        taskReturnPkg = null
        return if (SystemClock.elapsedRealtime() - taskReturnAt <= TASK_RETURN_WINDOW_MS) pkg else null
    }

    private fun isRecentsClass(cls: String): Boolean {
        val c = cls.lowercase()
        return c.contains("recents") || c.contains("overview")
    }

    /**
     * 屏幕是不是刚亮/刚灭（[SCREEN_FLIP_MS] 以内）。开关屏由桌面那边记（[Store.screenOnAt] /
     * [Store.screenOffAt]，同一个进程），这里只读。
     *
     * **类名里写着 Recents/Overview 的那一屏不算**（[isRecentsClass]）：开关屏时系统报上来的
     * 是原厂桌面的**过渡窗口**（三星是 `com.sec.android.app.launcher/FrameLayout`，见 2026-09-21
     * 那条「29 次亮灭屏有 22 次白退一次」），它不可能顶着个 RecentsActivity 的类名。
     * 而「开屏后两秒内真按了任务键」是真会发生的（2026-09-22 模拟器实测：开屏后 0.3 秒按任务键，
     * 这一屏是货真价实的 nexuslauncher/RecentsActivity），一刀切放过等于把真的那一屏也放过去了，
     * 那两秒里任务列表就在屏幕上。（[isTaskSwitchScreen] 另一条判据「包名是别的桌面」没有类名撑腰，
     * 照旧受这条限制。）
     */
    private fun screenJustFlipped(cls: String): Boolean {
        if (isRecentsClass(cls)) return false
        val now = SystemClock.elapsedRealtime()
        fun fresh(at: Long) = at in 1..now && now - at <= SCREEN_FLIP_MS
        return fresh(Store.screenOnAt(this)) || fresh(Store.screenOffAt(this))
    }

    /**
     * 这一屏是不是「最近任务」。两种来源都要认：SystemUI 自带的最近任务页（三键导航），
     * 以及系统桌面——手势导航下多任务视图由默认桌面渲染，它一露头就说明孩子按了任务键。
     *
     * **别家桌面的过渡窗口不算**（[isTransitionClass]）。这一屏真的是个界面：任务列表是桌面里
     * 的一个 Activity（三星报的是 `com.sec.android.app.launcher/RecentsActivity`，上面那条就认了），
     * 而系统给窗口切换动画/浮层报上来的是**光秃秃的容器类**（三星是 FrameLayout）。它和任务键
     * 没有半点关系：2026-09-22 那份真机自检报告里它就出现在密码页正开着的时候（日志原话
     * 「任务键：com.sec.android.app.launcher/FrameLayout 露头，但本应用自己的页面正开着」）。
     *
     * 而 owner 报的「文件管理器里长按文件、操作菜单出现的前后就被切回桌面」走的正是这条：
     * 菜单弹出/收起的窗口切换让三星桌面报了这么一个过渡窗口 → 被当成任务屏 → 孩子当时正在
     * 白名单应用里（会话还在）就发返回键退它，落在「我的文件」上就是先按掉菜单、再一下把它
     * 整个按退出，孩子就站在桌面上了；会话不在时发的更是 HOME，一步到桌面。
     */
    private fun isTaskSwitchScreen(pkg: String, cls: String): Boolean {
        if (isRecentsClass(cls)) return true
        if (pkg !in otherHomeApps()) return false
        if (isTransitionClass(cls)) {
            taskSkippedTransition++
            logThrottled(
                "transitionNotTaskScreen",
                "任务键：$pkg/${cls.substringAfterLast('.')} 是过渡窗口（窗口切换动画/浮层），不是「最近任务」那一屏，放过",
            )
            return false
        }
        return true
    }

    /**
     * 类名是不是「光秃秃的容器」——过渡窗口的招牌。系统的窗口切换动画、浮层窗口报上来的
     * 就是这几个框架类，而不是哪一个界面；真有界面时类名是个 Activity（`…RecentsActivity`），
     * 走不到这里。空类名同样当过渡窗口：拿不出「这是个界面」的证据，就不该照任务屏动刀
     */
    private fun isTransitionClass(cls: String): Boolean {
        val c = cls.lowercase()
        return c.isEmpty() || c in TRANSITION_CLASSES
    }

    /** 把这一次窗口事件记进 [trail]（本应用自己的窗口不记：浮层每秒刷一次会把真现场挤掉） */
    private fun noteTrail(pkg: String, cls: String) {
        if (pkg == packageName) return
        trail.addLast("${pkg.substringAfterLast('.')}/${cls.substringAfterLast('.')}")
        while (trail.size > TRAIL_MAX) trail.removeFirst()
    }

    /** [trail] 拼成报告/日志里那一行 */
    private fun trailText(): String =
        if (trail.isEmpty()) "（没记上）" else trail.joinToString(" → ")

    private fun otherHomeApps(): Set<String> {
        otherHomes?.let { return it }
        val s = try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0)
                .map { it.activityInfo.packageName }
                // 系统那个兜底桌面：每台机器上它都在 HOME 候选里，但它从来不是孩子看到的多任务视图，
                // 而它的包名是 com.android.settings——不排掉的话，**整个「设置」应用**的每个页面
                // 都会被当成任务屏按一下返回键（2026-09-17 日志里的
                // 「任务键：退掉 com.android.settings/DeepLinkHomepageActivity」就是这么来的，
                // 家长在设置页里会被莫名其妙按出去）
                .filter { it != packageName && it != FALLBACK_HOME_PKG }
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
        !systemUiPkg(pkg) && pkg !in ime && pkg != dialer && pkg !in otherHomeApps()

    /**
     * 「本来就不该拦」的原因，返回 null 表示该拦。（管控没生效那三条在 [guardOffReason] 里，
     * 调用方要先问那个——顺序换了但结论不变：两条路都是放行，只是记下来的理由不一样。）
     */
    private fun allowedReason(pkg: String, ime: Set<String>, dialer: String?): String? {
        // 系统界面（状态栏、下拉通知栏、权限弹框、音量条）一律放行；
        // 其中的「最近任务」那一屏在上面就单独处理掉了，走不到这里
        if (systemUiPkg(pkg)) return "系统界面"
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

    /** 孩子打开了 [pkg]（或从别处切回来）：会话切到它，接着上一次用剩下的时间往下数 */
    private fun startSession(pkg: String) {
        // 总闸关着（家长自己用平板，或者「测试拦截」到点了）：一个表都不走、一个题都不弹
        if (!Store.interceptionOn(this)) {
            if (sessionPkg != null) pauseSession("「启动拦截」总闸关着")
            return
        }
        // 挑战页其实已经不在屏幕上了（被系统回收/进程走过一遭）：把「在等答题」这个标记清掉，
        // 否则它会一直卡着，之后所有应用的计时都起不来
        if (challengeFor != null && !SessionChallengeActivity.showing) challengeFor = null
        // 挑战页还弹着的时候不去碰会话：底下那个应用这时也可能冒窗口事件
        if (sessionPkg == pkg || challengeFor != null) return
        // 直接从别的应用切过来的：把上一个应用的表停在这一刻（剩余保留）
        if (sessionPkg != null) pauseSession("切到了 $pkg")
        val limit = Store.singleUseMin(this)
        sessionPkg = pkg
        sessionSeconds = Store.sessionUsed(this, pkg)
        pausedByScreenOff = false
        // 这一次的额度上次就用光了（在题上没答对）：别给他从头数的机会，进来就把题再弹出来
        if (limit > 0 && sessionSeconds >= limit * 60) {
            Diag.log("session", "$pkg 这次的时长已经用光，一进来就弹题")
            fireChallenge(pkg)
            return
        }
        if (limit > 0) {
            Diag.log(
                "session",
                "$pkg 开始单次计时（上限 $limit 分钟，接着上次剩的 ${limit * 60 - sessionSeconds} 秒）",
            )
        }
        arm()
        updateOverlay()
    }

    /**
     * 孩子离开了这个应用（切到别的应用、回桌面、被密码页盖住）：**停表，但不作废**——
     * 已经用掉的秒数存起来，他再进来接着用剩下的。只有答对乘法题才清零重新给满，见 [answerChallenge]。
     * 2026-09-16 owner 反馈「切到后台再返回，剩余时间又复原了，限时等于没用」之后改成这样的。
     */
    private fun pauseSession(why: String) {
        val pkg = sessionPkg ?: return
        val used = sessionSeconds
        Store.setSessionUsed(this, pkg, used)
        sessionPkg = null
        sessionSeconds = 0
        pausedByScreenOff = false
        handler.removeCallbacks(sessionTick)
        val left = (Store.singleUseMin(this) * 60 - used).coerceAtLeast(0)
        Diag.log(
            "session",
            "$pkg 的单次计时暂停（$why），已用 ${used}s、还剩 ${left}s（再进来接着用）",
        )
        updateOverlay()
    }

    /** 只在有会话、且家长没把上限设成「不限」时走表 */
    private fun arm() {
        handler.removeCallbacks(sessionTick)
        if (sessionPkg == null) return
        if (!Store.interceptionOn(this)) return
        if (Store.singleUseMin(this) <= 0) return
        handler.postDelayed(sessionTick, 1000L)
    }

    /**
     * 每秒一次。只在这一秒孩子确实还在那个应用里、屏幕还亮着时才往上加——息屏、
     * 被密码锁屏页盖住、人已经回到桌面，都不算「他在用」。返回 true 表示继续走表。
     */
    private fun tickSession(): Boolean {
        val pkg = sessionPkg ?: return false
        // 走表当中家长把总闸关了（或者「测试拦截」到点了）：表停在这一刻，剩余留着
        if (!Store.interceptionOn(this)) {
            pauseSession("「启动拦截」总闸关着")
            return false
        }
        if (Store.singleUseMin(this) <= 0) {
            pauseSession("家长把上限改成了不限")
            return false
        }
        // 只有前台换成了**别的应用**才算他离开了这个应用。输入法弹出来、状态栏拉开、桌面
        // 自己弹个框、任务键那一屏——这些也都是窗口事件，但都不是换应用，不该动他的表。
        // 1.0.10 真机上就是这么一秒一断（日志里 xedu 每段计时只有 0~11 秒），
        // 于是上限永远攒不满，这个功能等于没生效。
        // 「回了桌面」那条路由 MainActivity.onResume → onDesktopShown 管，不靠这里。
        val frontApp = frontAppPkg
        if (frontApp != null && frontApp != pkg) {
            pauseSession("前台换成了 $frontApp")
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
        // 每几秒落一次盘：孩子在题上走开、或者进程被系统杀掉时，已用的时间不会白送回去
        if (sessionSeconds % PERSIST_EVERY_S == 0) Store.setSessionUsed(this, pkg, sessionSeconds)
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
        val limit = Store.singleUseMin(this)
        // 「这次已经用满」先落盘：孩子在题上按 Home 走开（或进程被杀）时，
        // 再进来还得是没额度，而不是白捡一整轮
        if (limit > 0) Store.setSessionUsed(this, pkg, limit * 60)
        challengeFor = pkg
        handler.removeCallbacks(sessionTick)
        Diag.log("session", "$pkg 连续用满 $limit 分钟 → 弹乘法挑战")
        Audit.record(Audit.CHALLENGE, pkg, "单次用满 $limit 分钟，弹乘法挑战")
        updateOverlay()
        Store.showSessionChallenge(this, pkg)
    }

    /**
     * 挑战页的结局：答对＝把这个应用的时长清零（重新给满），孩子接着用；
     * 答错＝这一次的额度就算用光了，他人已被送回桌面，再点开这个应用会立刻再弹同一道题。
     */
    private fun answerChallenge(ok: Boolean) {
        val pkg = challengeFor ?: return
        challengeFor = null
        if (ok) {
            Store.clearSessionUsed(this, pkg)
            sessionPkg = pkg
            sessionSeconds = 0
            Diag.log("session", "$pkg 挑战答对，单次时长清零重新计时（重新给满 ${Store.singleUseMin(this)} 分钟）")
            arm()
        } else {
            val limit = Store.singleUseMin(this)
            if (limit > 0) Store.setSessionUsed(this, pkg, limit * 60)
            sessionPkg = null
            sessionSeconds = 0
            handler.removeCallbacks(sessionTick)
            Diag.log("session", "$pkg 挑战没答对，这次的时长算用光（再进来还会弹题）")
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

    /**
     * 把「最近任务」那一屏整个盖住。
     *
     * 为什么要盖：那一屏是系统自己弹出来的窗口，我们唯一的办法是事后动手（把桌面叫回最前面 /
     * 发返回键），而那一下要几百毫秒才生效（`taskFollowUp` 的补刀就是这么来的）。这几百毫秒里列表是能点的——
     * 2026-09-21 真机日志里孩子点开了设置、浏览器、权限控制器各一次，每条后面都紧跟
     * 「拦截 …→ 回到桌面」，应用是真的被打开过。盖上之后那半秒里点哪儿都没反应。
     *
     * 颜色取得跟桌面背景一样是浅色：三星 One UI 的任务列表本来也是浅底，孩子看到的是
     * 「那一屏闪了一下、什么都没发生」，不是一块突兀的黑屏。
     *
     * 它只是**挡住手**，不参与任何判定：不推进窗口链（[isOwnOverlay] 会把自己的窗口事件跳掉）、
     * 不影响 [MainActivity.onScreen]、也不挡全局动作（返回键是系统直接执行的，不走窗口焦点）。
     */
    private fun showTaskBlocker() {
        // 已经在盖着了（补刀那一轮）：什么都别做。**尤其不能把收工时间往后顺延**——
        // 挡板什么时候收由 [taskScreenStillUp] 说了算，不按时间
        if (taskBlocker != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = View(this).apply {
            setBackgroundColor(TASK_BLOCKER_COLOR)
            isClickable = true
            isFocusable = false
            // 吃掉落在这半秒里的每一下触摸。**计一下数**：这一下本来会点在任务列表的卡片上
            // （那种「点进去用了别的应用」的反馈就是从这里来的），记下来才看得出挡板有没有在干活，
            // 也才知道孩子到底有没有在戳那一屏
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    taskBlockerTaps++
                    logThrottled("blockerTap", "任务键：挡板吃掉一下点击（这一下本来会点在任务列表上）")
                }
                true
            }
        }
        for (type in listOf(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        )) {
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && !Settings.canDrawOverlays(this)) {
                continue
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            try {
                wm.addView(v, params)
                taskBlocker = v
                taskBlocked++
                taskBlockerNote = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                    "能盖住（无障碍浮层，免权限）"
                } else {
                    "能盖住（走悬浮窗权限那条路）"
                }
                Diag.log("guard", "任务键：盖挡板遮住任务列表，一直盖到那一屏真的走了（最多 ${TASK_BLOCKER_MAX_MS / 1000} 秒）")
                return
            } catch (e: Exception) {
                taskBlockerNote = "${e.javaClass.simpleName}: ${e.message}"
                Diag.log("guard", "任务键：挡板没盖上（type=$type）：$taskBlockerNote")
            }
        }
        taskBlockerNote = "加不上：$taskBlockerNote"
    }

    /** 收掉挡板。[reason] 只进日志，用来区分「那一屏走了」和「顶到时间预算上限」 */
    private fun removeTaskBlocker(reason: String) {
        val v = taskBlocker ?: return
        taskBlocker = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
            // 窗口已经跟着进程一起没了
        }
        Diag.log("guard", "任务键：挡板收掉（$reason）")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    companion object {
        /** 系统兜底桌面的包名（FallbackHome），见 [otherHomeApps] */
        private const val FALLBACK_HOME_PKG = "com.android.settings"

        /** 系统界面（状态栏、音量条、权限弹框）。它不是「孩子换了个应用」，不进窗口链 */
        private const val SYSTEM_UI_PKG = "com.android.systemui"

        /** 两次「弹回桌面」之间的最小间隔：挨着弹会闪屏 */
        private const val BOUNCE_GAP_MS = 600L

        /**
         * 刚退过的那一屏要是紧接着又报同一条事件，这段时间内不再退：那是同一个窗口在刷状态，
         * 而那一下返回键会落到现在已经换成别人的界面上（把孩子正用着的应用按退出了）。
         * **不是按时间放行的闸门**：孩子真按了任务键，中间一定隔着别的窗口露头，走不到这条
         */
        private const val TASK_KILL_ECHO_MS = 300L

        /** 处置过之后这一屏还赖着多久，就再来一次（那一下没生效时的兜底） */
        private const val TASK_KILL_RETRY_MS = 1_500L

        /** 盯住那一屏的间隔：多久去确认一下它走没走，见 [taskFollowUp] */
        private const val TASK_VERIFY_MS = 320L

        /**
         * 盯那一屏的时间预算，见 [taskFollowUp]。**正常路径下根本轮不到它**：那一屏一走
         * 挡板立刻就收了，实测真机/模拟器都在 1 秒以内。这个上限是给「我们的簿记过期了」
         * （系统不再发窗口事件，我们怎么都看不出它走没走）兜底的——那时候只能先收挡板，
         * 否则等于把孩子一直困在灰屏上。
         *
         * **它和旧版的区别是「谁说了算」**：旧版是 `postDelayed(收挡板, 2500ms)`，时间一到
         * 无条件收掉，哪怕那一屏还好端端盖在屏幕上（2026-09-22 真机报告就是这么把任务列表
         * 露出来的）；现在收不收只看 [taskScreenStillUp]，时间只用来表达「我们等不起」。
         */
        private const val TASK_BLOCKER_MAX_MS = 8_000L

        /** 挡板颜色。桌面是浅色 Material 3，三星任务列表也是浅底，用浅灰比黑屏不刺眼 */
        private const val TASK_BLOCKER_COLOR = 0xFFF1F2F6.toInt()

        /** 屏幕刚亮/刚灭之后多久内，别家桌面露头的窗口不当任务屏，见 [screenJustFlipped] */
        private const val SCREEN_FLIP_MS = 2_000L

        /**
         * 过渡窗口的类名（全小写比较），见 [isTransitionClass]。
         *
         * 这几个都是框架里的容器类：系统给窗口切换动画 / 浮层窗口报上来的就是它们，而不是界面。
         * 三星 One UI 桌面报的是 `android.widget.FrameLayout`（2026-09-22 真机报告里有原文），
         * 本应用自己的浮层窗口那条报的是 `android.view.View`。
         */
        private val TRANSITION_CLASSES = setOf(
            "android.widget.framelayout",
            "android.widget.linearlayout",
            "android.widget.relativelayout",
            "android.view.view",
        )

        /** [trail] 留几条窗口事件 */
        private const val TRAIL_MAX = 8

        /** 同一条「放行/放过」日志的重复抑制窗口，见 logThrottled */
        private const val ALLOW_LOG_GAP_MS = 10_000L

        /** 单次计时走表时每隔多少秒留一行心跳，家长据此确认「表确实在走」 */
        private const val TICK_LOG_EVERY_S = 30

        /** 已用秒数每隔多少秒落一次盘（进程被杀时最多丢这么久） */
        private const val PERSIST_EVERY_S = 5

        /**
         * 「退掉最近任务那一屏」之后多久之内，桌面露面还算成那一下造成的。
         * 太短会漏（落桌面有小延迟），太长会把他后来自己按的 Home 也吞掉
         */
        private const val TASK_RETURN_WINDOW_MS = 2_000L

        /**
         * 「最近任务」那一屏露头之后多久之内，桌面这次露面不算「从应用里逃回来」，见
         * [taskScreenRecent]。按任务键之后落回桌面通常就在一两秒内（模拟器实测 1.4 秒），
         * 给到 2.5 秒足够；再长就会把「按任务键进了应用、过一会儿又按 Home 出来」也一起吞掉
         */
        private const val TASK_SCREEN_WINDOW_MS = 2_500L

        /**
         * 判「桌面离开屏幕是不是那一屏压的」时往回放宽多少，见 [desktopLeftBecauseOfTaskScreen]。
         * 桌面 onStop 和那一屏的窗口事件差几十到几百毫秒，不放宽会漏判
         */
        private const val TASK_SCREEN_SLACK_MS = 1_200L

        /**
         * 桌面露头多久之内还算「刚露头」。超过这个时长说明孩子已经在桌面上站着了，
         * 那一次露头的现场就不该再被翻出来用（他站在桌面上按 Home 也不该弹框）
         */
        private const val LAUNCHER_FRESH_MS = 2_000L

        /** 当前活着的那个服务实例。计时状态就在它身上，挑战页和桌面通过下面几个口子找它 */
        @Volatile
        private var instance: GuardAccessibilityService? = null

        /**
         * 服务实例是否活着。和系统「无障碍」列表里那个开关是两回事：开关开着而服务被杀掉
         * （装新版、强行停止、厂商省电休眠）时这个仍是 false，桌面顶部要照实报出来。
         */
        fun isRunning(): Boolean = instance != null

        /** 乘法挑战页答完题后的结局 */
        fun onChallengeAnswered(ok: Boolean) {
            instance?.answerChallenge(ok)
        }

        /** 桌面回到前台：孩子已经离开了刚才那个应用，这一轮的表停在这里（剩余留着） */
        fun onDesktopShown() {
            instance?.let {
                // 孩子站在桌面上按任务键时，我们是靠把桌面叫回最前面来顶掉那一屏的——
                // 桌面回来了，当时盖上去的那块挡板就该收了（挡板是本应用自己的窗口，
                // onAccessibilityEvent 里那条「下一个界面露头」不认本应用自己的包名，收不掉它），
                // 「盯住那一屏」那一轮也跟着结束（那一屏要是又冒出来，会新起一轮）
                it.stopTaskFollowUp("桌面已经回到最前面")
                it.pauseSession("回到桌面")
            }
        }

        /**
         * 桌面问：「这一下露面是不是我刚退掉最近任务那一屏造成的？是的话他本该在哪个应用里」。
         * 取值即作废，见 [takeTaskReturn]。
         */
        fun consumeTaskReturn(): String? = instance?.takeTaskReturn()

        /** 自检报告里的一行：「退掉任务列表」这件事此刻在不在生效 */
        fun taskKillStateText(ctx: Context): String {
            val s = instance ?: return "✗ 没生效：无障碍服务没在运行"
            return s.taskKillOffReason()?.let { "✗ 没生效：$it" } ?: "✓ 生效中"
        }

        /**
         * 自检报告里的一行：「按任务键还能看到任务列表」到底卡在哪一步。
         *
         * 分两层报：**上面一条是按键这一层**（任务列表根本没出现，最理想），
         * **下面一条是「那一屏已经露头了再退」**（按键过滤没生效时的兜底）。
         * 两条的计数都要能对上，家长才看得出到底在用哪条路、卡在哪一步。
         */
        fun taskKillReport(): String {
            val s = instance ?: return "（无障碍服务没在运行，这些数字拿不到）\n"
            val sb = StringBuilder()
            sb.appendLine(
                "按键过滤（按任务键那一下直接吃掉，任务列表根本不出现）：" +
                    if (!s.keyFilterOn) "✗ 没申请到，只能靠下面那条路兜底"
                    else "已申请；本次运行吃掉 ${s.keySwallowed} 次"
            )
            sb.appendLine(
                "　这一路收到过的按键事件：共 ${s.keyEventsTotal} 个" +
                    if (s.keyEventsTotal == 0)
                        "——**一个都没有**。导航栏上的任务键（三键导航）和上滑手势（手势导航）都不是" +
                            "按键事件：导航栏是系统界面自己画的，点它是触摸，系统不会送到这里来。" +
                            "这条路在这台设备上用不上，只能靠下面的「挡板 + 把桌面叫回最前面/退屏」"
                    else "（keycode×次数：${s.keyCodesSeen.entries.joinToString("、") { "${it.key}×${it.value}" }}）"
            )
            if (s.taskSeen == 0 && s.taskSkippedOff == 0) {
                sb.appendLine("「最近任务」那一屏：服务运行以来还没露过头（说明上面那条路在起作用）")
                return sb.toString()
            }
            sb.appendLine(
                "服务运行以来：这一屏露头 ${s.taskSeen} 次、处理 ${s.taskKilled} 次" +
                    "（其中把儿童桌面叫回最前面 ${s.taskHomeCalled} 次、退掉这一屏留在当前应用 " +
                    "${s.taskKilled - s.taskHomeCalled} 次、补刀 ${s.taskRetried} 次）、" +
                    "因同一屏重复事件放过 ${s.taskSkippedEcho} 次、因管控没生效放过 ${s.taskSkippedOff} 次、" +
                    "因开关屏放过 ${s.taskSkippedScreen} 次、因是过渡窗口放过 ${s.taskSkippedTransition} 次"
            )
            sb.appendLine("最近 8 条窗口事件（从旧到新，本应用自己的窗口不记）：${s.trailText()}")
            sb.appendLine(
                "挡住任务列表的挡板（那一屏出现在屏幕上的那段时间里，孩子点不动上面任何东西）：" +
                    "本次运行盖上 ${s.taskBlocked} 次、吃掉 ${s.taskBlockerTaps} 下点击；${s.taskBlockerNote}"
            )
            sb.appendLine(
                "（「露头」应当等于「处理」；放过的次数多，说明当时管控没生效，或者系统忙到那一下没吃上。\n" +
                    "　叫回桌面＝孩子不在应用里（站在桌面上按的），守护直接把儿童桌面顶回最前面；\n" +
                    "　留在当前应用＝孩子在白名单应用里按的，发返回键退掉那一屏，他还在原来那个应用里。\n" +
                    "　补刀＝那一屏还赖在最前面时自动再动一下（连按两下时系统不发窗口事件，只能按时间补），\n" +
                    "　　只要还在盖着挡板就一直补，所以补刀次数多不等于有问题。\n" +
                    "　开关屏那几条、以及「因是过渡窗口放过」那一条，都是系统切窗口时的过渡窗口，不是孩子按的。\n" +
                    "　而「露头」本身就该是 0：按键被吃掉的话，这一屏压根不会出现）"
            )
            return sb.toString()
        }

        /** 桌面判「这一次露面要不要弹挑战框」的证据，见 [beforeLauncher] */
        fun beforeLauncher(launcherPkg: String): String? = instance?.windowBeforeLauncher(launcherPkg)

        /**
         * 刚退掉「最近任务」那一屏吗（见 [killTaskScreen]）。桌面判「这一次露面要不要弹挑战框」
         * 之前要问这一句：那一下露面是本应用自己动手顶掉那一屏造成的，孩子并没想回桌面。
         *
         * 和 [takeTaskReturn] 的区别：那个只在「有应用可以送回去」时给得出地址，孩子站在桌面上
         * 按任务键时（会话早停了）它是 null——恰恰是这一种以前会白弹一道「按返回键回到桌面」。
         */
        fun taskKillRecent(maxMs: Long = TASK_RETURN_WINDOW_MS): Boolean {
            val at = instance?.lastTaskKillAt ?: 0L
            return at != 0L && SystemClock.elapsedRealtime() - at <= maxMs
        }

        /**
         * 「最近任务」那一屏刚露过头吗（见 [taskScreenSeenAt]，不管退没退成）。
         * 桌面判挑战框前问这一句：那一屏只可能来自任务键，这一下露面不是「从应用里逃回来」，
         * 用 leftScreen 那类证据弹框就是白弹。
         */
        fun taskScreenRecent(maxMs: Long = TASK_SCREEN_WINDOW_MS): Boolean {
            val at = instance?.taskScreenSeenAt ?: 0L
            return at != 0L && SystemClock.elapsedRealtime() - at <= maxMs
        }

        /**
         * 桌面这一次「离开屏幕/离开前台」是不是「最近任务」那一屏压出来的。
         *
         * 按任务键时那一屏盖在桌面上，桌面照样 onStop/onPause——于是 leftScreen / leftFg
         * 这两条兜底证据看上去就像「他刚从别处回到桌面」，白弹一道题。判据是时刻对不对得上：
         * 桌面离开的那一下落在那一屏正在屏幕上的区间里，就是它压的，不是孩子逃回来的。
         *
         * 2026-09-21 模拟器实测（1.0.26）：站在桌面上按任务键，任务列表停留 8 秒，
         * 桌面露头时判定依据 leftScreen=2420ms → 弹了框；按时刻对区间就能认出来。
         */
        fun desktopLeftBecauseOfTaskScreen(at: Long): Boolean {
            val s = instance ?: return false
            if (s.taskScreenSeenAt == 0L || at == 0L) return false
            // 只比一个方向：那一屏是在「桌面离开屏幕」之后（或紧接着之前）露的头，就是它压的。
            // 不比另一个方向是有意的——桌面 onStop 和那一屏的窗口事件谁先谁后不一定
            // （2026-09-21 模拟器实测两种顺序都出现过），只卡一头就不怕先后颠倒
            return s.taskScreenSeenAt >= at - TASK_SCREEN_SLACK_MS
        }

        /** 自检报告用：那一屏最后一次露头是多久以前 */
        fun taskScreenAgo(): String {
            val at = instance?.taskScreenSeenAt ?: 0L
            if (at == 0L) return "（服务运行以来还没露过头）"
            return "${SystemClock.elapsedRealtime() - at}ms 前"
        }

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
            val off = Store.screenOffAt(ctx)
            sb.appendLine(
                "屏幕最后一次熄灭：${if (off == 0L || off > now) "（本次开机还没熄过）"
                else "${now - off}ms 前（熄屏期间记下的现场不作数，免得合盖再开盖被当成「从别处回到桌面」）"}"
            )
            // 这几条要是很多，说明本应用自己的浮层/页面在被当成「桌面露头」——
            // 那会把上面那条窗口链冲掉，桌面只能退到很旧的兜底证据上去判（白弹挑战框）
            sb.appendLine("本应用自己的窗口露头、但桌面没在最前面而略过：${s.ownWindowSkipped} 次")
            // 这几条不该多：输入法/状态栏插进链子里，桌面就认不出「孩子之前用的是哪个应用」
            sb.appendLine("输入法/系统界面被挡在窗口链外面：${s.chainSkipped} 次")
            return sb.toString()
        }

        /**
         * 自检报告里那一小节：哪些包被当成「系统界面」放行。权限弹框被弹回桌面时先看这里——
         * 名单不在这份报告里（说明是又一个没认出来的包名），就该像 2026-09-22 那样把包名补进来
         */
        fun systemUiReport(): String {
            val s = instance ?: return "无障碍服务没在运行，这段拿不到\n"
            return s.exempt.sorted().joinToString("、") +
                "；外加任何以 .permissioncontroller 结尾的包（各家 ROM 的权限弹框，见 systemUiPkg）\n"
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
            if (!Store.interceptionOn(ctx)) {
                sb.appendLine("当前会话：（不会计时——「启动拦截」总闸关着，整机不设防）")
                sb.appendLine("  → 要拦就在「家长设置 → 拦截」里打开总闸，或开一次「测试拦截」")
                sb.appendLine("屏幕顶部剩余时间小字：不会出现（没有会话就没有这行小字）")
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
            // 离开应用只是停表、剩余留着（见 pauseSession）。这一行是「他还剩多少」的答案：
            // 家长看到孩子一直在用同一批应用时，先来这里核对剩余对不对
            val usages = Store.sessionUsages(ctx)
            if (usages.isNotEmpty() && limit > 0) {
                sb.appendLine("各应用还剩多少（离开只停表，回来接着用）：")
                usages.toSortedMap().forEach { (k, v) ->
                    val left = (limit * 60 - v).coerceAtLeast(0)
                    val sign = if (left <= 0) "已用光（再进去就弹题）" else "还剩 ${left / 60}:${(left % 60).toString().padStart(2, '0')}"
                    sb.appendLine("  · $k：已用 ${v}s，$sign")
                }
            }
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
