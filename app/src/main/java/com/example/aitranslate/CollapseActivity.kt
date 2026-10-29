package com.example.aitranslate

import android.app.Activity
import android.os.Bundle

/** 只用来让系统收起控制中心/通知栏（startActivityAndCollapse 需要一个目标），立刻结束 */
class CollapseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); if (ShotA11y.instance != null) KeepAlive.start(this); finish(); overridePendingTransition(0, 0) }
}
