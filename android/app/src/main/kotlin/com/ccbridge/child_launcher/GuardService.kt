package com.ccbridge.child_launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 前台服务：每秒累计一次使用时长（亮屏时才计），
 * 达到当日上限后拉起 LockActivity 要求输入家长密码。
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /** 攒够 5 秒再落盘一次，避免每秒写 SharedPreferences */
    private var pending = 0

    private val tick = object : Runnable {
        override fun run() {
            step()
            if (running) handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 开机后本服务常常先于桌面起来，先接上 Diag：日志从这一刻起就能落到文件里
        // （家长在「桌面自检」里下载那份文件，看的就是这一段现场）
        Diag.attach(this)
        startForegroundCompat()
        running = true
        handler.postDelayed(tick, 1000L)
        Diag.log("gate", "计时守护服务已启动（每日用量统计 / 到点拉密码页）")
        Audit.record(Audit.SERVICE, "计时守护", "服务已启动，开始统计每日时长/次数")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        Diag.log("gate", "计时守护服务已停止（每日用量不再统计、到点也不锁屏）")
        Audit.record(Audit.SERVICE, "计时守护", "服务已停止：每日用量不再统计、到点不锁屏")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun step() {
        if (!Store.guardEnabled(this)) {
            stopSelf()
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return // 息屏不计时

        Store.rollDate(this)
        pending++
        if (pending >= 5) {
            Store.addUsedSeconds(this, pending)
            pending = 0
        }
        if (Store.gateReason(this) == "time") {
            Store.addUsedSeconds(this, pending)
            pending = 0
            Store.showLock(this, "time")
        }
    }

    private fun startForegroundCompat() {
        val chId = "guard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "使用时间守护", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val n: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("儿童桌面守护中")
            .setContentText("正在统计使用时间")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, n)
        }
    }
}
