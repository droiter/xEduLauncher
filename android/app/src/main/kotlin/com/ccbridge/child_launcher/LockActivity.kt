package com.ccbridge.child_launcher

import android.app.Activity
import android.os.Build
import android.os.Bundle
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
            desc.text = "请输入家长密码后继续使用"
        } else {
            icon.text = "⏰"
            title.text = "今日使用时间已到"
            desc.text = "请输入家长密码以获得 ${Store.graceMin(this)} 分钟宽限时间"
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

    private fun tryUnlock(reason: String, input: EditText, hint: TextView) {
        if (input.text.toString() == Store.password(this)) {
            if (reason == "count") Store.resetOpenCount(this) else Store.grantGrace(this)
            Diag.log(
                "gate",
                if (reason == "count") "密码正确：打开次数已清零"
                else "密码正确：追加 ${Store.graceMin(this)} 分钟宽限",
            )
            Audit.record(
                Audit.LOCK,
                reason,
                if (reason == "count") "密码正确 → 打开次数清零，继续使用"
                else "密码正确 → 追加 ${Store.graceMin(this)} 分钟宽限",
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

    /** 锁屏页吞掉返回键，防止直接退出 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    override fun onDestroy() {
        showing = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "reason"

        /** 防止服务每秒都重复拉起锁屏页 */
        @Volatile
        var showing = false
    }
}
