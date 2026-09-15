package com.ccbridge.child_launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 文件传输服务的前台服务外壳。之所以要前台服务：服务开着的时候家长随时可能从电脑上连过来，
 * 进程不能被系统回收；顺带把访问地址挂在通知里，家长一眼就能看到该在浏览器里敲什么。
 */
class FileServerService : Service() {

    override fun onCreate() {
        super.onCreate()
        Diag.attach(this)
        Diag.log("http", "文件传输服务进程启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Store.fileServerOn(this)) {
            HttpGateway.stop()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!HttpGateway.isRunning() && !HttpGateway.start(this)) {
            Diag.log("http", "服务起不来（端口被占），停掉前台服务")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        Diag.log("http", "文件传输服务进程结束")
        HttpGateway.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val chId = "files"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "文件传输", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val url = HttpGateway.urls(this).firstOrNull()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("文件传输已开启")
            .setContentText(url ?: "服务启动中…")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                (url ?: "服务启动中…") + "\n电脑浏览器打开这个地址，手机上点「同意」后即可下载日志、上传文件"
            ))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        /** 和 GuardService 的 1 区分开 */
        private const val NOTIF_ID = 2
    }
}
