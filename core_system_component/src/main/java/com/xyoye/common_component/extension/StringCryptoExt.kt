package com.xyoye.common_component.extension

import android.util.Base64
import com.xyoye.common_component.utils.EntropyUtils

fun String?.toMd5String(): String = EntropyUtils.string2Md5(this)

fun String.aesEncode(key: String? = null): String? = EntropyUtils.aesEncode(key, this, Base64.NO_WRAP)

fun String.aesDecode(key: String? = null): String? = EntropyUtils.aesDecode(key, this, Base64.NO_WRAP)
