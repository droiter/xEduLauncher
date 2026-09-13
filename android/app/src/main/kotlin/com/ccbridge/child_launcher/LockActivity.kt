package com.ccbridge.child_launcher

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * 超时 / 超次数时全屏弹出的密码页。用原生 Activity 实现，
 * 无需第二个 Flutter 引擎，且能被前台服务从后台直接拉起。
 */
class LockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        setContentView(R.layout.activity_lock)

        val reason = intent.getStringExtra(EXTRA_REASON) ?: "time"
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
        input.requestFocus()
    }

    private fun tryUnlock(reason: String, input: EditText, hint: TextView) {
        if (input.text.toString() == Store.password(this)) {
            if (reason == "count") Store.resetOpenCount(this) else Store.grantGrace(this)
            showing = false
            finish()
        } else {
            hint.text = "密码不正确，请重试"
            input.setText("")
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
