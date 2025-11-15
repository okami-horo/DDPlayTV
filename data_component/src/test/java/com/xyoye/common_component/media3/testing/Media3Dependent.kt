package com.xyoye.common_component.media3.testing

/**
 * Shadow copy of the annotation used in common_component to avoid wiring a
 * circular dependency into data_component's unit tests. Once a shared
 * testing utilities module exists this duplicate should be removed.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Media3Dependent(
    val reason: String = ""
)
