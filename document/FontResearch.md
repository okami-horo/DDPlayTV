Kodi

  - xbmc/cores/VideoPlayer/DVDSubtitles/DVDSubtitlesLibass.cpp:95-156 中初始化 libass 时会调用 ass_set_fonts_dir 将用户字体目录 special://home/
    media/Fonts/（KODI::UTILS::FONT::FONTPATH::USER）注册到引擎，同时遍历系统字体 special://xbmc/media/Fonts/ 以及临时字体 special://temp/fonts/，
    逐个将 .ttf/.otf 内容读入内存后 ass_add_font 给 libass，并最终 ass_set_fonts(..., ASS_FONTPROVIDER_AUTODETECT, ...) 让这些字体参与渲染。这意味
    着只要把远端/附件字体复制到临时目录，Kodi 就能当成本地字体使用。
  - 官方文档也定义了 SYSTEM = "special://xbmc/media/Fonts/", USER = "special://home/media/Fonts/", TEMP = "special://temp/fonts/"（https://
    xbmc.github.io/docs.kodi.tv/master/kodi-base/da/d61/...），说明 temp/fonts 专门用来接收运行期字体。
  - GitHub issue #23294 描述了 Android 版 Kodi 在扫描库或播放 .mkv 时会自动把附件字体解包到 Android/data/org.xbmc.kodi/files/.kodi/temp/fonts，即
    使视频来自网络，也通过这种“抽取→缓存→注册”的方式给 libass 提供可用字体。从该问题也能看出 Kodi 会维护一个字体数据库，删除缓存后需要重新抽取。

  Jellyfin

  - issue #13914 说明 Jellyfin 服务器在执行“内嵌 ASS 字幕烧录”时依赖 AttachmentExtractor 把 .mkv 附件字体写入 /cache/attachments/<id>，然后在调用
    ffmpeg 时通过 fontsdir 参数把该临时目录传给 libass；如果先在浏览器端播放一次、触发缓存，下次 Android 客户端即使远程播放也能直接复用这些字体。
    Jellyfin 的策略与 Kodi 类似：远端源→提前下载字体→提供本地路径给渲染引擎。

  mpv

  - man.archlinux.org/man/extra/mpv/mpv.1.en:5285-5288 清楚写到 --sub-fonts-dir=<path>：mpv 会把指定目录下的字体文件加载进 libass 供字幕渲染（默认
    是 ~/.config/mpv/fonts/subfont.ttf）。因此只要能把远端字体提前同步到一个本地目录，就能让 mpv 播放任意来源的流时使用这些字体。
  - 《Load Local Subtitles While MPV Plays Remote Videos》（https://www.nite07.com/en/posts/mpv-remote-video-local-sub/）给出一套实践：用 rclone
    在 LibassBridge.setFonts() 中把缓存路径加入搜索目录。对于不便下载整包的场景，可以参照 mpv 的方案，通过挂载或流式方式在本地制造一个“伪目录”供
    libass 扫描。
  - 额外注意缓存清理策略（Kodi 的 issue 就暴露了缓存过大会占满空间），以及在首次播放/切换云端源时确保字体已经下载完毕再初始化字幕，以免出现
    Jellyfin 那种“AttachmentExtractor 未生效导致首次播放没有字体”的问题。