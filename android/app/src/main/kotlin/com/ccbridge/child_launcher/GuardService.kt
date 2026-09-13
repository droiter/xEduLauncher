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
        startForegroundCompat()
        running = true
        handler.postDelayed(tick, 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
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
