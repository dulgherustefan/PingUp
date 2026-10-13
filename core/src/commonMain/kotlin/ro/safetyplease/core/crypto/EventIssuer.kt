package ro.safetyplease.core.crypto

/**
 * What an event key signs (PROTOCOL.md, sections 9 and 11), as tools/gen_staff_keys.py "event" and "issue" do: a
 * day's report key and SPS2 shift cards, each shift with a fresh key.
 */
class EventIssuer(private val crypto: Crypto, private val event: KeyPair) {
    fun reportKey(seed: ByteArray, from: Long, until: Long): Certificate =
        Certificate.issue(crypto, CertRole.REPORT_KEY, crypto.boxKeyPairFromSeed(seed).publicKey, from, until, "", event)

    /**
     * [reportSeed] and [legacyBoxSeed] are for consoles and role leads, who open reports; an anchor sits unattended
     * and gets neither, nor the day's [reportKey].
     */
    fun shift(
        role: StaffRole,
        name: String,
        from: Long,
        until: Long,
        zone: String = "",
        reportKey: Certificate? = null,
        reportSeed: ByteArray? = null,
        legacyBoxSeed: ByteArray? = null,
    ): ShiftCard {
        val keySeed = crypto.random(Crypto.SEED)
        val certificate = Certificate.issue(crypto, role.code, crypto.signKeyPairFromSeed(keySeed).publicKey, from, until, name, event)
        if (role == StaffRole.ANCHOR) return ShiftCard(certificate, keySeed, null, null, null, zone)
        return ShiftCard(certificate, keySeed, reportKey, reportSeed?.takeIf { reportKey != null }, legacyBoxSeed, zone)
    }
}
