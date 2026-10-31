package com.xyoye.data_component.enums

import com.xyoye.data_component.R
import com.xyoye.data_component.bean.SheetActionBean

/**
 * Created by xyoye on 2021/1/18.
 */

enum class MediaType(
    val value: String,
    val storageName: String,
    val cover: Int = 0
) {
    LOCAL_STORAGE(
        "local_storage",
        "本地媒体库",
        cover = R.drawable.ic_local_storage,
    ),

    OTHER_STORAGE(
        "other_storage",
        "外部媒体库",
        cover = R.drawable.ic_play_history,
    ),

    FTP_SERVER(
        "ftp_server",
        "FTP媒体库",
        cover = R.drawable.ic_ftp_storage,
    ),

    WEBDAV_SERVER(
        "webdav_server",
        "WebDav媒体库",
        cover = R.drawable.ic_webdav_storage,
    ),

    SMB_SERVER(
        "smb_server",
        "SMB媒体库",
        cover = R.drawable.ic_smb_storage,
    ),

    REMOTE_STORAGE(
        "remote_storage",
        "PC端媒体库",
        cover = R.drawable.ic_remote_storage,
    ),

    SCREEN_CAST(
        "screen_cast",
        "远程投屏",
        cover = R.drawable.ic_screencast,
    ),

    EXTERNAL_STORAGE(
        "external_storage",
        "设备存储库",
        cover = R.drawable.ic_external_storage,
    ),

    ALSIT_STORAGE(
        "alist_storage",
        "Alist存储库",
        cover = R.drawable.ic_alist_storage,
    ),

    BAIDU_PAN_STORAGE(
        "baidu_pan_storage",
        "百度网盘存储库",
        cover = R.drawable.ic_baidu_pan_storage,
    ),

    OPEN_115_STORAGE(
        "open_115_storage",
        "115 Open 存储库",
        cover = R.drawable.ic_open_115_storage,
    ),

    CLOUD_115_STORAGE(
        "cloud_115_storage",
        "115 Cloud 存储库",
        cover = R.drawable.ic_cloud_115_storage,
    );

    companion object {
        fun fromValue(value: String): MediaType =
            when (value) {
                "local_storage" -> LOCAL_STORAGE
                "ftp_server" -> FTP_SERVER
                "webdav_server" -> WEBDAV_SERVER
                "smb_server" -> SMB_SERVER
                "remote_storage" -> REMOTE_STORAGE
                "screen_cast" -> SCREEN_CAST
                "external_storage" -> EXTERNAL_STORAGE
                "alist_storage" -> ALSIT_STORAGE
                "baidu_pan_storage" -> BAIDU_PAN_STORAGE
                "open_115_storage" -> OPEN_115_STORAGE
                "cloud_115_storage" -> CLOUD_115_STORAGE
                else -> OTHER_STORAGE
            }
    }

    fun toAction() = SheetActionBean(this, storageName, cover)
}
