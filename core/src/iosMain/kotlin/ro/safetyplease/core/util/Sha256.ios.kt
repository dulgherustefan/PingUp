package ro.safetyplease.core.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(data: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { out ->
        if (data.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0))
        } else {
            data.usePinned { input -> CC_SHA256(input.addressOf(0), data.size.convert(), out.addressOf(0)) }
        }
    }
    return digest.asByteArray()
}
