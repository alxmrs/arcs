package kotlin.native.internal

import kotlin.annotation.AnnotationTarget.*

/**
 * Makes this function to be possible to call by given name from C++ part of runtime using C ABI.
 * The parameters are mapped in an implementation-dependent manner.
 *
 * The function to call from C++ can be a wrapper around the original function.
 *
 * If the name is not specified, the function to call will be available by its Kotlin unqualified name.
 *
 * This annotation is not intended for the general consumption and is public only for the launcher!
 */
@Target(
    FUNCTION,
    CONSTRUCTOR,
    PROPERTY_GETTER,
    PROPERTY_SETTER
)
@Retention(AnnotationRetention.BINARY)
public annotation class ExportForCppRuntime(val name: String = "")