package com.ccbridge.child_launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.random.Random

/**
 * 单次使用时长到点：在孩子正用着的那个应用之上，弹出一道一位数乘法。
 *
 * 答对——会话清零，孩子留在原应用里接着用，重新开始计时；
 * 答错——直接把他送回儿童桌面（这就是「无法继续使用」，也顺便给了他一条出路，
 * 不然解不出题就永远卡在这一页）。
 *
 * 和 Home 挑战一样只有一次机会：没有取消按钮、不吞返回键以外的退路、答错不给重答。
 * 用原生 Activity 而不是 Flutter 对话框——孩子当时在别的应用里，Flutter 的界面根本不在屏幕上。
 */
class SessionChallengeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        setContentView(R.layout.activity_session_challenge)

        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val a = 2 + Random.nextInt(8) // 2..9，保证是一位数
        val b = 2 + Random.nextInt(8)

        val question = findViewById<TextView>(R.id.challengeQuestion)
        val input = findViewById<EditText>(R.id.challengeInput)
        val hint = findViewById<TextView>(R.id.challengeHint)

        question.text = "$a × $b = ?"
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.requestFocus()

        findViewById<Button>(R.id.challengeOk).setOnClickListener {
            val v = input.text.toString().trim().toIntOrNull()
            if (v == a * b) pass(pkg) else deny(pkg)
        }
        Diag.log("session", "弹出乘法挑战（$pkg）：$a × $b")
    }

    /** 答对：会话清零重新计时，finish 后底下的应用自然回到前台，孩子接着用 */
    private fun pass(pkg: String) {
        Diag.log("session", "$pkg 的乘法挑战答对，单次时长清零重新计")
        Audit.record(Audit.CHALLENGE, pkg, "乘法答对 → 单次时长清零，孩子留在原应用接着用")
        GuardAccessibilityService.onChallengeAnswered(true)
        showing = false
        finish()
    }

    /** 答错：没通过，送回桌面，这一次不能再用了 */
    private fun deny(pkg: String) {
        Diag.log("session", "$pkg 的乘法挑战答错，送回儿童桌面")
        Audit.record(Audit.HOME, pkg, "乘法答错 → 把孩子送回儿童桌面，这次不能再用")
        GuardAccessibilityService.onChallengeAnswered(false)
        Store.noteGuardBounce(this) // 这一下回桌面是本应用弹的，别再让孩子做一道按 Home 的题
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(home)
        } catch (e: Exception) {
            Diag.log("session", "送回桌面失败：${e.javaClass.simpleName}: ${e.message}")
            Audit.record(Audit.HOME, pkg, "送回桌面失败：${e.javaClass.simpleName}: ${e.message}")
        }
        showing = false
        finish()
    }

    /** 这一页只能靠答题离开：返回键吞掉，也别让碰外面关掉 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    override fun onDestroy() {
        showing = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PKG = "pkg"

        /** 防止守护每秒重复拉起挑战页 */
        @Volatile
        var showing = false
    }
}
