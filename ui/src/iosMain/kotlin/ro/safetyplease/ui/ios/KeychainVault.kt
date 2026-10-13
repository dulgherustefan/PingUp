package ro.safetyplease.ui.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import ro.safetyplease.core.session.Vault

/**
 * The secrets in the Keychain, one generic password each under [service]. They open from the first unlock after a
 * restart, so the mesh can run in the background, and stay on this phone: no backup and no new phone gets them.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainVault(private val service: String) : Vault {
    override fun load(name: String): ByteArray? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = query(name, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { SecItemCopyMatching(it, result.ptr) }
        // before the first unlock nothing opens: saying the secret is gone would make the session replace the identity
        check(status != errSecInteractionNotAllowed) { "the Keychain is locked until the phone is unlocked once" }
        val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
        try {
            if (status != errSecSuccess) return@memScoped null
            val length = CFDataGetLength(data).toInt()
            if (length == 0) ByteArray(0) else CFDataGetBytePtr(data)!!.readBytes(length)
        } finally {
            CFRelease(data)
        }
    }

    override fun save(name: String, secret: ByteArray) {
        delete(name)
        val data = secret.usePinned { pinned ->
            CFDataCreate(null, if (secret.isEmpty()) null else pinned.addressOf(0).reinterpret(), secret.size.convert())
        }
        try {
            val status = query(name, kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) { SecItemAdd(it, null) }
            check(status == errSecSuccess) { "the Keychain didn't keep $name: $status" }
        } finally {
            CFRelease(data)
        }
    }

    override fun delete(name: String) {
        query(name) { SecItemDelete(it) }
    }

    override fun exists(name: String): Boolean = query(name, kSecMatchLimit to kSecMatchLimitOne) { SecItemCopyMatching(it, null) } == errSecSuccess

    /** Every secret of [service], as when the app is deleted. */
    fun deleteAll() {
        query(null) { SecItemDelete(it) }
    }

    override fun setAside(name: String) {
        val secret = load(name) ?: return delete(name)
        save("$name.lost", secret)
        delete(name)
    }

    /** Runs [block] with the query for [name], or for all of [service], plus [extra], and lets it go after. */
    private fun <T> query(name: String?, vararg extra: Pair<CFTypeRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val serviceRef = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)
        val accountRef = name?.let { CFStringCreateWithCString(null, it, kCFStringEncodingUTF8) }
        val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceRef)
            if (accountRef != null) CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
            for ((key, value) in extra) CFDictionaryAddValue(query, key, value)
            return block(query)
        } finally {
            CFRelease(query)
            accountRef?.let(::CFRelease)
            CFRelease(serviceRef)
        }
    }
}
