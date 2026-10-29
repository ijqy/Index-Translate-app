package com.example.aitranslate

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** 透明中转页：拿录音权限 + 系统「录屏/投屏」授权（只用来收手机正在播放的声音），然后启动实时字幕 */
class CaptionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 29) { Toast.makeText(this, "实时字幕需要 Android 10 及以上", Toast.LENGTH_LONG).show(); finish(); return }
        if (CaptionService.running) { startService(Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP)); finish(); return }
        val need = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += android.Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += android.Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 5) else askProjection()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要「录音」权限才能收到视频/直播的声音", Toast.LENGTH_LONG).show(); finish(); return
        }
        askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 不传 MediaProjectionConfig：系统才会允许选「共享一个应用」，只收那个 App 的声音
        val ci = mpm.createScreenCaptureIntent()
        Toast.makeText(this, if (Build.VERSION.SDK_INT >= 34) "可以选「共享一个应用」只收那个 App 的声音" else "下一步点开始，只用来收手机播放的声音", Toast.LENGTH_LONG).show()
        startActivityForResult(ci, 6)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 6 && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptionService::class.java).putExtra("code", resultCode).putExtra("data", data))
        }
        finish(); overridePendingTransition(0, 0)
    }
}
