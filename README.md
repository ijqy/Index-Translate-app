# Index 翻译（Android）

基于 B 站 Index-Translate 免费接口的 Android 翻译 App，纯 Kotlin、无第三方依赖。

## 功能
- 文本翻译：约 150 种语言，自动检测原文，流式输出
- 按要求翻译：语气、格式、术语表、自定义指令
- 图片 / 拍照 / 框选 / 全屏截图翻译（Android 11+ 走无障碍截图，无需共享屏幕）
- 悬浮球：单击弹出可拖动迷你小窗（输入、粘贴、语音、框选、全屏、拍照），长按直接框选
- 下拉快捷磁贴「Index 框选翻译」
- 语音翻译（Index-Echo：语音转文字 / 语音转语音）、长文档翻译
- 系统文字选择菜单「AI译」

## 构建
```
./gradlew assembleDebug
```
需要 JDK 17 和 Android SDK（compileSdk 34）。
