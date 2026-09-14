package com.ccbridge.child_launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * 前台守护，管两件事：
 *
 * 1. 非白名单应用露头（从通知点开、按任务键切回一个后台还在跑的应用）——立刻把桌面拉回来，
 *    孩子就没法借着「已经开着的应用」绕过管控。
 * 2. 「最近任务」那一屏——直接退掉，让孩子留在原来那个界面里。送回桌面等于换个方式逃出当前应用，
 *    而且一按任务键就回桌面本身也不是家长要的（他要的是「拒绝，但不换界面」）。
 *
 * 只监听窗口切换事件（typeWindowStateChanged），不读取任何窗口内容，
 * 也不需要 canRetrieveWindowContent，系统设置页里给家长的说明就是这个用途。
 */
class GuardAccessibilityService : AccessibilityService() {

    /** 上一次拦截的时刻，避免同一秒里连环弹造成闪屏 */
    private var lastBounceAt = 0L

    /** 系统界面里必须放行的部分：状态栏/通知面板、权限弹框、系统本身 */
    private val exempt = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** 系统上除本应用之外的桌面，只查一次：手势导航下「最近任务」由默认桌面渲染 */
    private var otherHomes: Set<String>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diag.log("guard", "前台守护服务已连接（无障碍）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""

        // 记下此刻最前面的是谁（包括自己的桌面）：桌面按 Home 要不要弹挑战框，就看这一行
        Store.noteForeground(this, pkg)
        if (pkg == packageName) return // 自己的桌面与密码页：不拦

        val ime = inputMethodPackages()
        val dialer = defaultDialer()
        rememberForeign(pkg, ime, dialer)
        if (allowed(pkg, cls, ime, dialer)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastBounceAt < 600L) return
        lastBounceAt = now

        if (isTaskSwitchScreen(pkg, cls)) {
            Diag.log(
                "guard",
                "拦截任务键（$pkg/${cls.substringAfterLast('.')}）→ 退掉这一屏，留在当前任务",
            )
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        Diag.log("guard", "拦截 $pkg/${cls.substringAfterLast('.')} → 回到桌面")
        // 这一下回桌面是本服务干的，不是孩子按的 Home：桌面那边记下来，别再弹挑战框
        Store.noteGuardBounce(this)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * 这一屏是不是「最近任务」。两种来源都要认：SystemUI 自带的最近任务页（三键导航），
     * 以及系统桌面——手势导航下多任务视图由默认桌面渲染，它一露头就说明孩子按了任务键。
     */
    private fun isTaskSwitchScreen(pkg: String, cls: String): Boolean {
        val c = cls.lowercase()
        if (c.contains("recents") || c.contains("overview")) return true
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

    override fun onInterrupt() = Unit

    /**
     * 记下孩子此刻在用的应用。他在这个应用里按 Home 想回桌面、挑战又没答对时，
     * 桌面会把他送回这里。系统界面/输入法/电话不算「在用的应用」。
     */
    private fun rememberForeign(pkg: String, ime: Set<String>, dialer: String?) {
        if (pkg in exempt || pkg in ime || pkg == dialer) return
        // 桌面露头是「按了任务键」，不是孩子真在用哪个应用，别记成「他刚才在用的」
        if (pkg in otherHomeApps()) return
        Store.noteForeign(this, pkg)
    }

    private fun allowed(pkg: String, cls: String, ime: Set<String>, dialer: String?): Boolean {
        if (!Store.frontGuard(this)) return true
        if (Store.parentFreeActive(this)) return true // 家长拿着第二个密码去系统设置办事
        if (pkg in exempt) {
            // 系统桌面在「最近任务」界面下就是任务键的宿主，这一屏要拦（怎么拦见 isTaskSwitchScreen）；
            // 其余 SystemUI 窗口（下拉通知栏、音量条）放行，否则家长也用不了
            return !(pkg == "com.android.systemui" && cls.lowercase().contains("recents"))
        }
        if (pkg in ime) return true
        // 电话要放行：来电界面被弹回桌面，孩子就接不了电话了
        if (pkg == dialer) return true
        if (!Store.isDefaultLauncher(this)) return true // 还没被设为默认桌面，拦了就是死循环
        return pkg in Store.allowed(this)
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
}
