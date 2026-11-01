package com.xyoye.common_component.metadata

/** Shared wording for the library, recognition page and task history. */
fun metadataFailureText(reason: String?): String =
    when (reason) {
        "AMBIGUOUS" -> "找到多个同名作品，可在来源中补充年份后重新识别。"
        "NO_RESULTS" -> "未找到对应资料，请检查片名或稍后重新识别。"
        "NAME_INSUFFICIENT" -> "未能提取作品名，请检查文件名称。"
        "ABSOLUTE_EPISODE", "EPISODE_REQUIRED" -> "缺少明确季集编号。可使用 S01E03；连续集数不会自动换算。"
        "EPISODE_MISSING" -> "TMDB 中没有对应季集，原文件仍可播放。"
        "EXTRA_FILE" -> "花絮或预告片不自动关联正片资料。"
        "SEARCH_LIMIT" -> "候选过多，尚不能确定唯一作品。可补充年份或明确资料 ID。"
        "NETWORK", "SERVER" -> "资料服务暂不可访问，请稍后重试；已有资料仍保留。"
        "RATE_LIMIT" -> "资料服务请求过于频繁，请稍后重试。"
        "NOT_CONFIGURED", "AUTHENTICATION" -> "资料服务配置不可用，请检查应用版本；文件播放不受影响。"
        "NOT_FOUND" -> "资料条目已不可用，已有资料仍保留。"
        "SOURCE_UNSUPPORTED" -> "此来源暂不支持自动入库，文件浏览与播放不受影响。"
        "PERMISSION" -> "无法读取来源，请恢复访问权限后重试。"
        "DIRECTORY_INCOMPLETE" -> "目录未完整读取，已保存的资料保留，可重新读取原范围。"
        "PARENT_UNAVAILABLE" -> "当前文件已处理，无法确认父目录；同目录发现尚未完成。"
        "RESULT_EXPIRED", "FILE_CHANGED" -> "来源或文件已变更，请重新识别。"
        "INTERRUPTED" -> "任务已中断，下次播放或重试时重新读取原范围。"
        null -> "播放后会自动整理当前文件与同目录的同剧文件。已有观看进度保持不变。"
        else -> "来源或资料读取失败，请检查连接后重试；已有资料仍保留。"
    }
