package com.xyoye.common_component.bilibili.live.danmaku

import android.graphics.Color
import com.xyoye.data_component.data.bilibili.LiveDanmakuEvent
import org.json.JSONArray
import org.json.JSONObject

object LiveDanmakuCommandParser {
    fun parseCommand(jsonBody: String): LiveDanmakuEvent? {
        if (jsonBody.isBlank()) return null
        val root = runCatching { JSONObject(jsonBody) }.getOrNull() ?: return null
        val cmd = root.optString("cmd").orEmpty()
        if (cmd.isBlank()) return null

        return when {
            cmd.startsWith("DANMU_MSG") -> parseDanmuMsg(root)
            else -> null
        }
    }

    private fun parseDanmuMsg(root: JSONObject): LiveDanmakuEvent.Danmaku? {
        val info = root.optJSONArray("info") ?: return null

        val text = info.optString(1).orEmpty()
        if (text.isBlank()) return null

        val info0 = info.optJSONArray(0)
        val modeValue = info0?.optInt(1, 1) ?: 1
        val mode =
            when (modeValue) {
                5 -> LiveDanmakuEvent.DanmakuMode.TOP
                4 -> LiveDanmakuEvent.DanmakuMode.BOTTOM
                else -> LiveDanmakuEvent.DanmakuMode.SCROLL
            }

        val colorDec = (info0?.optLong(3, 0xFFFFFF) ?: 0xFFFFFF).toInt()
        val color = Color.argb(255, (colorDec shr 16) and 0xFF, (colorDec shr 8) and 0xFF, colorDec and 0xFF)

        val timestampMs = info0?.optLong(4, 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis()

        val (userId, userName) = parseUserInfo(info)
        val recommendScore = parseRecommendScore(info0)

        return LiveDanmakuEvent.Danmaku(
            text = text,
            mode = mode,
            color = color,
            timestampMs = timestampMs,
            recommendScore = recommendScore.coerceIn(0, 10),
            userId = userId,
            userName = userName,
        )
    }

    private fun parseUserInfo(info: JSONArray): Pair<Long, String> {
        val sender = info.optJSONArray(2)
        val userId = sender?.optLong(0, 0L) ?: 0L
        val userName = sender?.optString(1).orEmpty()
        return userId to userName
    }

    private fun parseRecommendScore(info0: JSONArray?): Int {
        val extObj = info0?.optJSONObject(15) ?: return 0
        val extraRaw = extObj.optString("extra").orEmpty()
        if (extraRaw.isBlank()) return 0
        val extraObj = runCatching { JSONObject(extraRaw) }.getOrNull() ?: return 0
        return extraObj.optInt("recommend_score", 0)
    }
}

