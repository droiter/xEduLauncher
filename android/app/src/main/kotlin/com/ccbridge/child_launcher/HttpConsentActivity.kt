package com.ccbridge.child_launcher

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

/**
 * 「有设备想连过来」的同意页。做成透明的原生 Activity 而不是 Flutter 对话框：
 * 家长点开文件传输服务的时候，屏幕上很可能是孩子正在用的应用，Flutter 界面根本不在前台。
 *
 * 只有「同意」之后，那台设备（按 IP 记）才能在浏览器里看到文件列表、下载日志、上传文件。
 * 一分钟没人理就自动当作拒绝，免得一个框长期压在屏幕上。
 */
class HttpConsentActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoDeny = Runnable { finishWith(false) }
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        setContentView(R.layout.activity_http_consent)

        val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
        findViewById<TextView>(R.id.consentIp).text = ip.ifBlank { "（未知设备）" }
        findViewById<Button>(R.id.consentAllow).setOnClickListener { finishWith(true) }
        findViewById<Button>(R.id.consentDeny).setOnClickListener { finishWith(false) }
        handler.postDelayed(autoDeny, AUTO_DENY_MS)
        Diag.log("http", "弹出同意页：$ip（${AUTO_DENY_MS / 1000} 秒不点就自动拒绝）")
    }

    private fun finishWith(allow: Boolean) {
        if (answered) return
        answered = true
        handler.removeCallbacks(autoDeny)
        if (allow) HttpGateway.approve(this) else HttpGateway.deny(this)
        showing = false
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finishWith(false)

    /**
     * [showing] 只在这一页正盖在最前面时为 true，被压到后台就置回 false——否则它一直赖着 true，
     * 守护会以为「本应用自己的页面正开着」，从此再也不退任务屏（2026-09-21 模拟器实测）。
     */
    override fun onResume() {
        super.onResume()
        showing = true
    }

    override fun onPause() {
        showing = false
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDeny)
        if (!answered) {
            // 被系统或用户以别的方式关掉：当拒绝处理，别让这一页悬着
            answered = true
            HttpGateway.deny(this)
        }
        showing = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IP = "ip"

        private const val AUTO_DENY_MS = 60_000L

        /** 防止每来一个请求就拉一个同意页 */
        @Volatile
        var showing = false
    }
}
