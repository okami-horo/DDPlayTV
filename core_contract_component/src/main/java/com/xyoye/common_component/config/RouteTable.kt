package com.xyoye.common_component.config

/**
 * Created by xyoye on 2020/9/21.
 *
 * 路由表
 */

object RouteTable {
    object App {
        const val Media3SessionServiceProvider = "/app/media3_session_service_provider"
        const val CinemaNavigation = "cinema_navigation"
    }

    object Anime {
        const val Search = "/anime/search"
        const val HomeFragment = "/anime/home_fragment"
        const val AnimeDetail = "/anime/anime_detail"
        const val AnimeSeason = "/anime/anime_season"
        const val AnimeFollow = "/anime/follow"
        const val AnimeTag = "/anime/tag"
        const val AnimeHistory = "/anime/history"
    }

    object Local {
        const val CinemaFragment = "/local/cinema_fragment"
        const val MetadataLibrary = "/local/metadata_library"
        const val MetadataSearch = "/local/metadata_search"
        const val MetadataDetail = "/local/metadata_detail"
        const val MetadataMatch = "/local/metadata_match"
        const val MediaFragment = "/local/media_fragment"
        const val BindExtraSource = "/local/bind_extra_source"
        const val PlayHistory = "/local/play_history"
        const val ShooterSubtitle = "/local/shooter_subtitle"
    }

    object User {
        const val AccountTools = "/user/account_tools"
        const val SettingsFragment = "/user/settings_fragment"
        const val MetadataSettings = "/user/metadata_settings"
        const val PersonalFragment = "/user/personal_fragment"
        const val DeveloperMenuService = "/user/developer_menu_service"
        const val UserSessionService = "/user/user_session_service"
        const val UserLogin = "/user/login"
        const val UserInfo = "/user/info"
        const val SettingPlayer = "/user/setting_player"
        const val SettingApp = "/user/setting_app"
        const val WebView = "/user/web_view"
        const val ScanManager = "/user/scan_manager"
        const val CacheManager = "/user/cache_manager"
        const val CommonManager = "/user/common_manager"
        const val AboutUs = "/user/about_us"
        const val License = "/user/license"
        const val SwitchTheme = "/user/switch_theme"
        const val SettingDeveloper = "/user/setting_developer"
    }

    object Player {
        const val Player = "/player/player_interceptor"
        const val CloudDanmuBlockService = "/player/cloud_danmu_block_service"
        const val PlaybackRequestService = "/player/playback_request_service"
    }

    object Stream {
        const val RemoteScan = "/stream/remote_scan"
        const val ScreencastReceiver = "/stream/screencast_receiver"
        const val ScreencastReceive = "/stream/screencast_receive"

        const val StorageFile = "/stream/storage_file"
        const val StorageFileProvider = "/stream/storage_file_provider"
        const val StoragePlus = "/stream/storage_plus"
    }
}
