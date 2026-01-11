package com.xyoye.danmaku

import master.flame.danmaku.danmaku.model.IDanmakus.ST_BY_TIME
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser

class EmptyDanmakuParser : BaseDanmakuParser() {
    override fun parse(): Danmakus = Danmakus(ST_BY_TIME, false, mContext.baseComparator)
}
