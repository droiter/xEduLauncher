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
        // Android 12+ 后台启动前台服务需要 SYSTEM_ALERT_WINDOW 之类的豁免，故先判权限
        if (Store.guardEnabled(ctx) && Store.hasOverlay(ctx)) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
            } catch (_: Exception) {
                // 系统拒绝则忽略，用户下次进入桌面时会再次尝试
            }
        }
        // 开机时文件传输服务如果是开着的，也一并恢复；起不来就等家长下次进桌面补
        if (Store.fileServerOn(ctx)) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, FileServerService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
