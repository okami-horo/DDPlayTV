package com.xyoye.common_component.network.request

/**
 * Marker interface for domain exceptions that should be propagated as-is from [Request],
 * instead of being wrapped into [NetworkException].
 */
interface PassThroughException

