对标“成熟 TV 播放器/浏览器”
- TV 端滚动建议做“keyline 对齐”（焦点尽量保持在中线/固定位置），Leanback 体系通常用 windowAlignmentOffsetPercent=50f 这类思路；你们
    现在的“顶格对齐”会更容易让人迷失上下文。
- 把“焦点态显示”与“选中态/点击态”分离：触控端只保留 pressed/ripple，遥控端才显示 focus ring（你现在已经更接近这个方向了）。

mpv 视频帧回调驱动字幕渲染
- mpv(vo_android/hwdec) 增加 JNI 逐帧回调