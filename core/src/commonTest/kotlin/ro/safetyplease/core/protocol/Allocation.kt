package ro.safetyplease.core.protocol

/** The bytes this thread has allocated so far, where the platform counts them (the JVM); null where it doesn't. */
expect fun threadAllocatedBytes(): Long?
