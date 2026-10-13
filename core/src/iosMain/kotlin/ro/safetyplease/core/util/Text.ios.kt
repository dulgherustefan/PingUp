package ro.safetyplease.core.util

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun String.decomposed(): String = (this as NSString).decomposedStringWithCanonicalMapping
