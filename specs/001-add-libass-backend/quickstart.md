# Quickstart: libass Backend

## Prerequisites

- Prebuilt libass 0.17.3 `.so` placed under `player_component/libs` (provided)
- Build toolchain: AGP 7.3.1, Kotlin 1.7.21

## Build

```bash
./gradlew assembleDebug
```

## Enable libass Backend

1. Open Settings → Playback/Subtitle → Subtitle Renderer Backend
2. Select “libass 渲染后端”
3. Start a new playback session for the setting to take effect

## Test

- Use ASS/SSA samples with rich styles (karaoke, shadows, outlines)
- Validate:
  - Correct style preservation (font, size, outline, shadow)
  - Correct timing/positioning and z-order
  - Fallback dialog appears on init/render failure and switching works immediately

## Notes

- TextureView: overlay `SubtitleOverlayView` draws ARGB bitmap composed from libass `ASS_Image` chain
- SurfaceView: transparent overlay surface (`PixelFormat.TRANSLUCENT`, `setZOrderMediaOverlay(true)`) aligned to video rect
- Performance: skip rendering when libass `changed==0`; reuse buffers; clear canvas each frame

## Debug & Observability

- 开发者设置 → “字幕后端状态” 可查看当前会话后端、Surface 类型、首帧耗时、回退原因。
- “模拟回退到旧后端” 可用于测试 USER_REQUEST 回退路径（同时会刷新 RendererPreference.updatedAt）。
- Fallback 弹窗会记录弹出-确认耗时（LIBASS-Fallback 日志），用于校验≤2s 目标。
