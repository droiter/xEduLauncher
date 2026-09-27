package com.ccbridge.child_launcher

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 超时 / 超次数时全屏弹出的密码页。用原生 Activity 实现，
 * 无需第二个 Flutter 引擎，且能被前台服务从后台直接拉起。
 */
class LockActivity : Activity() {

    /** 这一页上密码输错了几次（只在本页活着时算），用来发现「有人在试密码」 */
    private var tries = 0

    /** 锁屏原因：time = 今日时长用完，count = 打开次数用完。onStart 的日志也要用，故存成字段 */
    private var reason = "time"

    private val ui = Handler(Looper.getMainLooper())

    /** 对话框下面那行「距离复位还有多久」；只有 reason=time 且设了今日上限时才挂 */
    private var refillView: TextView? = null

    /**
     * 复位倒计时，每秒走一下；到点把这一页自己关掉，孩子回到原来那个应用接着玩。
     *
     * **为什么由这一页自己走表**：守护服务 `step()` 在密码页盖着时是直接 return 的
     * （这几分钟谁都用不了，不该算进今日用量），所以它压根不会每秒去问 [Store.gateReason]，
     * 复位没人判。孩子干等着的这段时间正是复位该走的钟，只能在这里数。
     */
    private val refillTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val tv = refillView ?: return
            val left = Store.refillLeftSec(this@LockActivity)
            if (left == null) {
                tv.visibility = View.GONE
            } else {
                tv.text = refillLine(left)
                tv.visibility = View.VISIBLE
            }
            // 复位的副作用（清用量、记审计）都在 Store.maybeRefill 里，这里只管「到点了就出去」，
            // 不再单独记一条——同一件事在审计里出现两遍反而难对
            if (Store.maybeRefill(this@LockActivity)) {
                Diag.log("gate", "密码页：停够时间了，额度已复位，自动关掉这一页")
                finish()
                return
            }
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        setContentView(R.layout.activity_lock)

        reason = intent.getStringExtra(EXTRA_REASON) ?: "time"
        val icon = findViewById<TextView>(R.id.lockIcon)
        val title = findViewById<TextView>(R.id.lockTitle)
        val desc = findViewById<TextView>(R.id.lockDesc)
        val input = findViewById<EditText>(R.id.lockInput)
        val hint = findViewById<TextView>(R.id.lockHint)

        if (reason == "count") {
            icon.text = "🔒"
            title.text = "今天打开次数已用完"
            desc.text = "请输入家长密码后继续使用（打开次数清零，单次时长顺延 ${Store.graceMin(this)} 分钟）"
        } else {
            icon.text = "⏰"
            title.text = "今日使用时间已到"
            desc.text =
                "请输入家长密码以获得 ${Store.graceMin(this)} 分钟宽限：" +
                    "今日时长 +${Store.graceMin(this)} 分钟，单次时长也顺延 ${Store.graceMin(this)} 分钟"
        }

        // 对话框下面告诉人「还要等多久」：用满之后连续停用够久，额度会自己复位。
        // 只在 reason=time 时挂——打开次数那条有它自己的密码，跟额度复位不是一回事
        if (reason == "time" && Store.refillWaitMin(this) > 0) {
            val rv = findViewById<TextView>(R.id.lockRefill)
            val left = Store.refillLeftSec(this)
            if (left != null) {
                refillView = rv
                rv.text = refillLine(left)
                rv.visibility = View.VISIBLE
                Diag.log(
                    "gate",
                    "密码页下方显示复位倒计时：还剩 ${left}s" +
                        "（用满后连续停用 ${Store.refillWaitMin(this)} 分钟自动复位）",
                )
                ui.postDelayed(refillTick, 1000L)
            }
        }

        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        findViewById<Button>(R.id.lockOk).setOnClickListener { tryUnlock(reason, input, hint) }
        // 键盘上的确认键（✓ / 回车）也要能提交。这一页是整屏居中的，键盘一顶上来「确认」按钮
        // 就压在键盘底下了：家长输完密码按键盘上的 ↵ 只会把键盘收掉，什么都不会发生
        // （2026-09-20 模拟器实测），得再点一次按钮。两条路都通最省事。
        input.setOnEditorActionListener { _, _, _ ->
            tryUnlock(reason, input, hint)
            true
        }
        // 键盘顶上来时把整页往上抬：targetSdk 35+ 强制 edge-to-edge 之后，清单里写 adjustResize
        // 已经不管用了，得自己吃 IME 那个高度
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.lockRoot)) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        // 这一页只能靠密码出去，返回键一律吞掉——包括无障碍服务发的那种全局返回手势
        // （GLOBAL_ACTION_BACK 走的是手势那条路，实测能把这一页关掉；下面 onBackPressed 那一条
        // 只挡得住真的按返回键）。targetSdk 33 起要用这个回调才算数
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { Diag.log("gate", "密码页吞掉了一次返回（这一页只能靠密码关掉）") }
        }
        input.requestFocus()
    }

    /** 这一页真起来了（区别于 Store.showLock 里那次「拉起」尝试）：自检报告对不上时看这一条 */
    override fun onStart() {
        super.onStart()
        Diag.log("gate", "密码页已显示（reason=$reason）")
    }

    /**
     * [showing] 记的是「这一页此刻盖在最前面」。被压到后面就必须置回 false——
     * 只靠 onCreate/onDestroy 管它的话，孩子一按任务键/Home 把这一页埋到后台，它就一直
     * 赖着 true：守护从此以为「本应用自己的页面正开着」，再也不退任务屏、也不记窗口链
     * （2026-09-21 模拟器实测，见 GuardAccessibilityService 里那段注释）。
     */
    override fun onResume() {
        super.onResume()
        showing = true
    }

    override fun onPause() {
        showing = false
        super.onPause()
    }

    private fun tryUnlock(reason: String, input: EditText, hint: TextView) {
        if (input.text.toString() == Store.password(this)) {
            if (reason == "count") Store.resetOpenCount(this) else Store.grantGrace(this)
            // 宽限的另一半：孩子当时正在用的那个应用，单次时长也顺延同样久
            // （2026-09-25 owner：「宽限 10 分钟后，单次时长延长 10 分钟，打开次数不变，全天时长不变」）。
            // 顺延目标只有无障碍服务认得（它记着孩子在前台用的是哪个应用），服务没跑就是 null，
            // 那时也没有单次计时可言，日志里留个「没顺延」比静默强
            val graced = GuardAccessibilityService.grantSessionGrace(Store.graceMin(this) * 60)
            Diag.log(
                "gate",
                (if (reason == "count") "密码正确：打开次数已清零"
                else "密码正确：追加 ${Store.graceMin(this)} 分钟宽限") +
                    if (graced != null) "；单次时长顺延 ${Store.graceMin(this)} 分钟（$graced）"
                    else "；单次时长没顺延（看不出孩子在用哪个应用）",
            )
            Audit.record(
                Audit.LOCK,
                reason,
                (if (reason == "count") "密码正确 → 打开次数清零，继续使用"
                else "密码正确 → 追加 ${Store.graceMin(this)} 分钟宽限") +
                    if (graced != null) "，单次时长顺延 ${Store.graceMin(this)} 分钟（$graced）"
                    else "（单次时长没顺延）",
            )
            showing = false
            finish()
        } else {
            tries++
            hint.text = "密码不正确，请重试"
            input.setText("")
            // 孩子拿这一页试密码是这套东西最想看见的行为之一，按次数记（不记他输了什么，
            // 那是密码，没必要也不该留在日志里）
            Diag.log("gate", "密码不正确（第 $tries 次，reason=$reason）")
            Audit.record(Audit.LOCK, reason, "密码不正确第 $tries 次（有人在密码页上试）")
        }
    }

    /** 对话框下面那两行：先说规则，再说还要等多久 */
    private fun refillLine(left: Int): String {
        val t = when {
            left >= 3600 -> "${left / 3600} 小时 ${(left % 3600) / 60} 分"
            left >= 60 -> "${left / 60} 分 ${left % 60} 秒"
            else -> "$left 秒"
        }
        return "用满后连续停用 ${Store.refillWaitMin(this)} 分钟，今日额度自动复位\n" +
            "距离时间复位（可以重新玩）还有 $t"
    }

    /** 锁屏页吞掉返回键，防止直接退出 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    override fun onDestroy() {
        ui.removeCallbacks(refillTick)
        showing = false
        // 这一页盖在桌面上时，桌面被压出去的「离开过屏幕/前台」不算「他去了别处」——
        // 关页时把那两条现场作废，见 MainActivity.noteOwnPageClosed
        MainActivity.noteOwnPageClosed("密码页")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "reason"

        /** 防止服务每秒都重复拉起锁屏页 */
        @Volatile
        var showing = false
    }
}
