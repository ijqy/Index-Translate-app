package com.example.aitranslate

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/** 透明中转页：申请悬浮窗 / 截屏权限，然后让 FloatService 截屏框选 */
class CaptureActivity : Activity() {
    private var bubble = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bubble = intent.getBooleanExtra(EXTRA_BUBBLE, false)
        // 首选：无障碍截屏，和系统截屏一样直接框选，不用共享屏幕
        if (!bubble && Build.VERSION.SDK_INT >= 30) {
            val a = ShotA11y.instance
            if (a != null) { a.captureSoon(); finish(); overridePendingTransition(0, 0); return }
            if (!intent.getBooleanExtra(EXTRA_FORCE_PROJECTION, false)) {
                Toast.makeText(this, "在无障碍里打开「Index 框选翻译」，之后下拉点一下就能直接框选", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish(); return
            }
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请允许 Index “显示在其他应用上层”（用于框选），返回后再点一次", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }
        if (FloatService.running) {
            val i = Intent(this, FloatService::class.java)
                .setAction(if (bubble) FloatService.ACTION_BUBBLE else FloatService.ACTION_CAPTURE)
            startService(i); finish(); overridePendingTransition(0, 0); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val ci = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        startActivityForResult(ci, REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val si = Intent(this, FloatService::class.java)
                .putExtra("code", resultCode).putExtra("data", data)
                .putExtra(EXTRA_BUBBLE, bubble).putExtra("captureNow", !bubble)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
            if (bubble) Toast.makeText(this, "悬浮球已开启，点一下框选翻译", Toast.LENGTH_SHORT).show()
        }
        finish(); overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_BUBBLE = "bubble"
        const val EXTRA_FORCE_PROJECTION = "forceProjection"
        private const val REQ = 3
    }
}
