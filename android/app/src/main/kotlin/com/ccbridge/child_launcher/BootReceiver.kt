package com.ccbridge.child_launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 开机：累计一次“打开次数”，并在已开启守护时拉起计时服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Diag.attach(ctx)
        Store.onBoot(ctx)
        // 重启后没有「刚才在用的应用」，别让 Home 挑战没过时把孩子送进昨天的应用
        Store.clearLastForeign(ctx)
        Audit.record(Audit.BOOT, "", "设备开机：计数一次「打开」，尝试恢复守护与文件传输服务")
        // Android 12+ 后台启动前台服务需要 SYSTEM_ALERT_WINDOW 之类的豁免，故先判权限
        if (Store.guardEnabled(ctx) && Store.hasOverlay(ctx)) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
            } catch (e: Exception) {
                // 系统拒绝则忽略，用户下次进入桌面时会再次尝试——但得留一条，
                // 否则「开机后限时/守护没生效」在日志里完全看不出是开机这一下没起来
                Diag.log("gate", "开机拉起计时守护服务失败：${e.javaClass.simpleName}: ${e.message}")
                Audit.record(Audit.SERVICE, "计时守护", "开机拉起失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // 开机时文件传输服务如果是开着的，也一并恢复；起不来就等家长下次进桌面补
        if (Store.fileServerOn(ctx)) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, FileServerService::class.java))
            } catch (e: Exception) {
                Diag.log("http", "开机拉起文件传输服务失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
