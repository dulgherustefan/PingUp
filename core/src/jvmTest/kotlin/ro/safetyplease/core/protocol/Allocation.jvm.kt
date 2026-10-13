package ro.safetyplease.core.protocol

import java.lang.management.ManagementFactory

private val meter = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
    ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
    ?: throw AssertionError("this JVM can't measure thread allocation, so the 32 KiB guard would pass anything")

actual fun threadAllocatedBytes(): Long? = meter.currentThreadAllocatedBytes
