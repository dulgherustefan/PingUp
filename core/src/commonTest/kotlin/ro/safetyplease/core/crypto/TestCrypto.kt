package ro.safetyplease.core.crypto

/** libsodium, as each platform links it. */
expect val testCrypto: Crypto

/** The same, with [random] in place of randombytes_buf: fixed nonces for the golden vectors. */
expect fun cryptoWithRandom(random: (Int) -> ByteArray): Crypto

/** crypto_generichash (BLAKE2b) of [input] to [size] bytes; only the golden vectors' step-by-step sealed box uses it. */
expect fun genericHash(input: ByteArray, size: Int): ByteArray
