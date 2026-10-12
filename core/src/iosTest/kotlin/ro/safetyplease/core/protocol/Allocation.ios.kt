package ro.safetyplease.core.protocol

// Kotlin/Native doesn't count a thread's allocations; the 32 KiB guard runs on the JVM
actual fun threadAllocatedBytes(): Long? = null
