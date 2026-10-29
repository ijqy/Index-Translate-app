package com.example.aitranslate

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 下拉通知栏里的快捷磁贴：点一下 → 收起面板 → 框选屏幕区域翻译 */
@android.annotation.TargetApi(24)
class QuickTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (FloatService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Index"
            if (Build.VERSION.SDK_INT >= 29) subtitle = null
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val a = ShotA11y.instance
        if (a != null && Build.VERSION.SDK_INT >= 30) {
            // 无障碍负责截屏；这里只负责把控制中心收起来（三种方式一起上，HyperOS 也能收）
            KeepAlive.start(this)   // 防止进程被系统冻结，悬浮球关着也能立刻出框选
            a.captureFromTile()
            collapse(Intent(this, CollapseActivity::class.java))
            return
        }
        collapse(Intent(this, CaptureActivity::class.java))
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapse(target: Intent) {
        val i = target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, i.component.hashCode(), i,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
        }
        ShotA11y.collapseStatusBar(this)
    }
}
