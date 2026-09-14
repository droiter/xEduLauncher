package com.ccbridge.child_launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * 前台守护：孩子按任务键（最近任务）切回一个已经在后台跑着的应用、或者从通知里点开应用时，
 * 那个应用会立刻出现在前台——这里盯的就是这一刻：只要它不是白名单里的应用，
 * 马上把桌面拉回来，孩子就没法借着「已经开着的应用」绕过管控。
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diag.log("guard", "前台守护服务已连接（无障碍）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        if (allowed(pkg, cls)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastBounceAt < 600L) return
        lastBounceAt = now
        Diag.log("guard", "拦截 ${pkg}/${cls.substringAfterLast('.')} → 回到桌面")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() = Unit

    private fun allowed(pkg: String, cls: String): Boolean {
        if (!Store.frontGuard(this)) return true
        if (pkg == packageName) return true // 自己的桌面与密码页
        if (Store.parentFreeActive(this)) return true // 家长拿着第二个密码去系统设置办事
        if (pkg in exempt) {
            // 系统桌面在「最近任务」界面下就是任务键的宿主，这一屏必须拦掉；
            // 其余 SystemUI 窗口（下拉通知栏、音量条）放行，否则家长也用不了
            return !(pkg == "com.android.systemui" && cls.lowercase().contains("recents"))
        }
        val ime = inputMethodPackages()
        if (pkg in ime) return true
        // 电话要放行：来电界面被弹回桌面，孩子就接不了电话了
        if (pkg == defaultDialer()) return true
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
