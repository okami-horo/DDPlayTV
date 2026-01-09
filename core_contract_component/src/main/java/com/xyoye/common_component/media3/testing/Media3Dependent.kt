package com.xyoye.common_component.media3.testing

/**
 * Marker annotation for regression cases that must execute against the Media3 delegate.
 * Used by `scripts/testing/media3-regression-report.sh` to verify coverage.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Media3Dependent(
    val reason: String = ""
)
