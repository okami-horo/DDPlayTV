# Subtitle Backend Support Matrix

| Format | libass backend | Legacy canvas backend | Notes |
|--------|----------------|-----------------------|-------|
| ASS / SSA | ✓ Full render parity via libass | ✓ | Primary target for libass; preserves ASS 样式与字体 |
| SRT / SUB / TXT | ✗ Prompt to switch back | ✓ | 当选择 libass 时弹窗提示可切回旧后端 |
| VTT | ✗ Prompt to switch back | ✓ | 继续走 MixedSubtitle 旧路径 |
| PGS / SUP | ✗ Not supported | ✗ Not supported | 无自动降级，仍需外部转换 |
| Embedded bitmap subtitles | ✗ Not supported | ✗ Not supported | 需要另行提取/转换 |

Scope reminder: libass 仅在 ExoPlayer + TextureView/SurfaceView 路径启用，其他内核默认回退到旧后端。
