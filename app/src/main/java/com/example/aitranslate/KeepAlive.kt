package com.example.aitranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 悬浮球关着时，HyperOS 会把后台的 Index 进程“冻结”，控制中心点框选后等收起面板的计时器就停了，
 * 要切回 App 才继续。点磁贴时临时挂一个前台服务，框选界面出来（或 8 秒后）就撤掉。
 */
class KeepAlive : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stopSelf() }

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("keep", "框选中", NotificationManager.IMPORTANCE_MIN))
            val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "keep") else @Suppress("DEPRECATION") Notification.Builder(this))
                .setSmallIcon(R.drawable.ic_tile).setContentTitle("Index 框选中…").build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(7, n)
        }
        h.removeCallbacks(stopper); h.postDelayed(stopper, 8000)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) { stopSelf() }
    override fun onDestroy() { h.removeCallbacks(stopper); super.onDestroy() }

    companion object {
        fun start(c: Context) = runCatching {
            val i = Intent(c, KeepAlive::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) = runCatching { c.stopService(Intent(c, KeepAlive::class.java)) }
    }
}
