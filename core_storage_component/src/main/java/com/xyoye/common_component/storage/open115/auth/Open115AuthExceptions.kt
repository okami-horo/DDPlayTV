package com.xyoye.common_component.storage.open115.auth

import com.xyoye.common_component.network.request.PassThroughException

class Open115ReAuthRequiredException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause),
    PassThroughException

class Open115NotConfiguredException(
    message: String
) : RuntimeException(message),
    PassThroughException

