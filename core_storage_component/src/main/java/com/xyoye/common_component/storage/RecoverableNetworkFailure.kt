package com.xyoye.common_component.storage

import com.xyoye.common_component.network.request.NetworkException
import retrofit2.HttpException
import java.io.IOException
import javax.net.ssl.SSLException

internal fun Throwable.isRecoverableNetworkFailure(): Boolean =
    when (this) {
        is SSLException -> false
        is IOException -> true
        is HttpException -> code() == 429 || code() in 500..599
        is NetworkException ->
            code in setOf(1001, 1002, 1003, 429) ||
                code in 500..599 ||
                (code == -1 && cause?.isRecoverableNetworkFailure() == true)
        else -> false
    }
